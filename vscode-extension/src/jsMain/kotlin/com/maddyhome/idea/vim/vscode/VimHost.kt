/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.VimExternalOpener
import com.maddyhome.idea.vim.autocmd.AutoCmdEvent
import com.maddyhome.idea.vim.api.VimProcessGroup
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimOutputPanelService
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.state.mode.SelectionType

/**
 * The running extension: keys in, edits out.
 *
 * Everything up to here could be tested without VS Code ever existing. This is the part that has to
 * be plugged into a live editor - which key arrives how, which editor it belongs to, and when the
 * buffer is written back - and it is deliberately thin, because the engine already knows what to do
 * with a keystroke.
 */
class VimHost(
  private val sink: MessageSink = MessageSink.Discarding,
  /**
   * How a VS Code command is run. Injectable so that tests can drive the asynchronous path without
   * a real extension host - the default is the real thing.
   */
  private val runCommand: (String, Array<Any?>, (Boolean) -> Unit) -> Unit = ::executeVsCodeCommand,
  /** Where the `:` and `/` prompts are drawn. The status bar, in a real window. */
  commandLineDisplay: CommandLineDisplay = NoCommandLineDisplay,
  /** Where search matches are painted. Decorations, in a real window. */
  highlighter: Highlighter = Highlighter.None,
  /** Vim's `"+` register. In-memory unless a real one is supplied. */
  private val clipboard: SystemClipboard = SystemClipboard.InMemory(),
  /** Where `:registers` and `:marks` print. An output channel, in a real window. */
  outputPanel: VimOutputPanelService = DiscardingOutputPanel,
  /** How `:!` runs a shell command. Node's `spawnSync`, unless a test supplies something else. */
  processes: VimProcessGroup = NodeProcessGroup(),
  /** How `gx` opens a URL. The operating system's own handler, in a real window. */
  opener: VimExternalOpener = VsCodeExternalOpener(),
) : HostCommandRunner {

  private val vimInjector =
    VsCodeInjector(sink, this, commandLineDisplay, highlighter, clipboard, outputPanel, processes, opener)

  /**
   * Editors by buffer identity rather than by object.
   *
   * VS Code hands out a new `TextEditor` for the same document more often than is obvious - moving
   * a file to a split is enough - and keying by the object would give that document a second Vim
   * editor with its own mode and its own idea of where the carets are.
   */
  private val editors: MutableMap<String, VsCodeEditor> = mutableMapOf()

  fun start() {
    injector = vimInjector
    // The engine owns the builtin command trie but does not fill it. Without this the key handler
    // recognises nothing, and every keystroke is silently discarded.
    engineCommandProvider.getCommands().forEach { vimInjector.keyGroup.registerCommandAction(it) }
    // ...and the handful of Vim commands the engine leaves to its host. See [VsCodeCommandProvider].
    VsCodeCommandProvider.getCommands().forEach { vimInjector.keyGroup.registerCommandAction(it) }
    vimInjector.functionService.registerHandlers()
  }

  /**
   * Runs the user's `.ideavimrc`, if there is one.
   *
   * After [start] and before any key, because the file is where mappings and options come from and
   * a key handled before it would use the defaults. Bracketed by `startInitVimRc`/`endInitVimRc`
   * so the option group can tell "the user set this in their config" from "the user set this
   * later" - `:set` reports the difference, and some options only take effect at startup.
   *
   * Returns the path that was run, or null if there was no file to run.
   */
  fun loadVimRc(editor: VimEditor, environment: (String) -> String? = ::environmentVariable): String? {
    val files = NodeFileSystem()
    val path = findVimRc(files, environment) ?: return null
    vimInjector.optionGroup.startInitVimRc()
    try {
      injector.vimscriptExecutor.executeFile(path, editor, fileIsIdeaVimRcConfig = true)
    } catch (e: Throwable) {
      // A broken config must not stop the extension from working. Vim itself carries on after an
      // error in the vimrc, and a user with a typo in one line still wants the other twenty.
      sink.error("IdeaVim: " + path + " could not be run: " + e.message)
    } finally {
      vimInjector.optionGroup.endInitVimRc()
    }
    return path
  }

  /**
   * The editor a startup command runs against: the one on screen, or the fallback window.
   *
   * `onStartupFinished` fires before VS Code has focused a restored editor, so `activeTextEditor`
   * is often null at exactly the moment the `.ideavimrc` should be read - and a window opened on a
   * folder rather than a file has no editor at all. The fallback window exists for this: somewhere
   * for the engine to run a command when there is no window yet.
   */
  fun startupEditor(textEditor: TextEditor?): VimEditor =
    textEditor?.let { editorFor(it) } ?: vimInjector.fallbackWindow

  /** The Vim editor for [textEditor], created on first sight and reused after. */
  fun editorFor(textEditor: TextEditor): VsCodeEditor {
    val identity = identityOf(textEditor)
    val existing = editors[identity]
    if (existing != null && existing.nativeEditor === textEditor) {
      // Re-registering marks it as the one with focus, which is the point: whichever editor a key
      // was typed into is the one the user is looking at.
      vimInjector.register(existing)
      return existing
    }

    // Same document, different `TextEditor` object: the old wrapper points at an editor VS Code is
    // no longer using, so it is replaced rather than repaired. Mode and caret state go with it,
    // which is the same reset a user sees when moving between windows.
    val editor = VsCodeEditor(textEditor)
    existing?.let { vimInjector.unregister(it) }
    editors[identity] = editor
    vimInjector.register(editor)
    return editor
  }

  fun forget(textEditor: TextEditor) {
    forgetDocument(textEditor.document)
  }

  /**
   * A document VS Code has closed, and the editor state that went with it.
   *
   * Nothing called `forget` until this existed, so the host kept every editor of every file opened
   * in the session. Memory is the least of it: every marker in a buffer is moved on every edit,
   * `'hlsearch'` paints every editor the engine knows about, and `getFocusedEditor` falls back to
   * the last one registered - which could be a file closed an hour ago.
   */
  fun forgetDocument(document: TextDocument) {
    val identity = "${document.uri.scheme}://${document.uri.path}"
    editors.remove(identity)?.let { vimInjector.unregister(it) }
    if (lastActiveEditor?.document?.uri?.path == document.uri.path) lastActiveEditor = null
  }

  /** A document written to disk, which is Vim's `BufWritePost`. */
  fun documentSaved(document: TextDocument) {
    val identity = "${document.uri.scheme}://${document.uri.path}"
    fire(AutoCmdEvent.BufWritePost, editors[identity])
  }

  private fun identityOf(textEditor: TextEditor): String =
    "${textEditor.document.uri.scheme}://${textEditor.document.uri.path}"

  /**
   * A printable character, straight from VS Code's `type` command.
   *
   * [com.maddyhome.idea.vim.api.VimStringParser.stringToKeys] rather than `parseKeys`, because this
   * is text the user typed: `parseKeys` reads `<` as the start of a key notation, so typing a
   * less-than sign would begin a keystroke that never finishes.
   */
  fun type(textEditor: TextEditor, text: String) {
    handle(textEditor, injector.parser.stringToKeys(text))
  }

  /** A key named the way Vim names it - `<Esc>`, `<C-W>` - from a keybinding rather than typing. */
  fun key(textEditor: TextEditor, notation: String) {
    handle(textEditor, injector.parser.parseKeys(notation))
  }

  // ---- Autocommand events.
  //
  // What a host owes `:autocmd`: the registry is the engine's, and the events are VS Code's. Only
  // the ones VS Code actually reports are fired - there is no point inventing a `BufWritePre` this
  // host cannot see coming.

  /** The editor `BufLeave` will name, remembered because VS Code has stopped calling it active. */
  private var lastActiveEditor: TextEditor? = null

  /** VS Code changed the active editor: `BufLeave` for the old one, `BufEnter` for the new. */
  fun activeEditorChanged(editor: TextEditor?) {
    val left = lastActiveEditor
    lastActiveEditor = editor
    if (left != null && left !== editor) fire(AutoCmdEvent.BufLeave, editorFor(left))
    if (editor != null) fire(AutoCmdEvent.BufEnter, editorFor(editor))
  }

  /** The window gained or lost focus, which is Vim's `FocusGained` and `FocusLost`. */
  fun windowFocusChanged(focused: Boolean) {
    val event = if (focused) AutoCmdEvent.FocusGained else AutoCmdEvent.FocusLost
    fire(event, lastActiveEditor?.let { editorFor(it) })
  }

  /**
   * Runs the autocommands for an event, and writes out whatever they changed.
   *
   * The flush is not optional and is easy to forget: an autocommand is a Vim command like any
   * other, and every other route into the engine here ends in one - this is a second door into the
   * same room. Without it `:autocmd BufEnter * :normal ix` edits the engine's buffer and VS Code
   * never hears about it.
   */
  private fun fire(event: AutoCmdEvent, editor: VsCodeEditor?) {
    injector.autoCmd.handleEvent(event, editor?.getPath(), editor)
    editor?.flush()
  }

  /** What the engine asked to have replayed. See `SingleThreadedApplication.postKey`. */
  private fun postedKeys(): List<com.maddyhome.idea.vim.key.VimKeyStroke> =
    (injector.application as? PostingApplication)?.takePostedKeys() ?: emptyList()

  private fun handle(textEditor: TextEditor, keys: List<com.maddyhome.idea.vim.key.VimKeyStroke>) {
    if (pending > 0) {
      // A VS Code command the engine asked for has not finished. Running these keys now would
      // compute them against text the command is about to change, and the command would then land
      // on top - so they wait, in order, rather than racing it.
      queued += { handle(textEditor, keys) }
      return
    }

    val editor = editorFor(textEditor)
    // Someone else may have edited the document since the last command - the user with a mouse, a
    // formatter, a language server. The engine reads the buffer, so it has to be told first.
    if (editor.buffer.syncIfDocumentMoved()) editor.syncCaretsFromEditor()

    val handler = KeyHandler.getInstance()
    val state = handler.keyHandlerState
    val remaining = keys.toMutableList()
    while (remaining.isNotEmpty()) {
      val stroke = remaining.removeAt(0)
      handler.handleKey(editor, stroke, VsCodeExecutionContext, state)
      // A key the engine asked to have handled after this one - `<C-V>065x` ends its literal on the
      // `x`, which is not part of it. Ahead of the rest, because Vim replays it immediately.
      remaining.addAll(0, postedKeys())
    }
    editor.flush { applied ->
      if (!applied) {
        // The document moved between the command running and its edit landing, so what the engine
        // computed was against text that is no longer there. Saying so beats writing over it.
        sink.error("IdeaVim: the document changed while a command was running, so it was not applied.")
      }
    }
  }

  // ---- Commands that only VS Code can run.
  //
  // Undo is the first of these and the reason the queue exists. VS Code's `undo` is a command, it
  // resolves a promise, and there is no way to ask what it did - so the engine's `u` cannot be
  // answered from what the host knows now. What it *can* do is stop pretending the next keystroke
  // is independent of it.

  private var pending: Int = 0
  private val queued: MutableList<() -> Unit> = mutableListOf()

  /** Whether a host command is in flight, so keys are waiting rather than running. */
  val isWaitingOnHost: Boolean get() = pending > 0

  /**
   * Runs a VS Code command, and holds the user's keys until it lands if it might change the text.
   *
   * [waitForIt] is the whole of the decision. Undo, redo and reformatting rewrite the document
   * behind the engine's back, so anything typed before they land would be computed against text
   * that is about to be replaced - those wait. Folding, splitting a window, changing tab, jumping
   * to a definition change what is on screen and not what is in the buffer, and holding the
   * keyboard for them would only make the editor feel slow.
   *
   * The failure branch matters more than it looks. A command that does not exist rejects rather
   * than resolving - one typo in an `<Action>` mapping - and a waiter that only listened for
   * success would leave `pending` above zero and every later keystroke queued behind a command
   * that is never coming back.
   */
  override fun run(command: String, arguments: Array<Any?>, waitForIt: Boolean) {
    if (!waitForIt) {
      runCommand(command, arguments) { succeeded ->
        if (!succeeded) sink.error("IdeaVim: VS Code has no command '$command'.")
      }
      return
    }
    pending++
    runCommand(command, arguments) { succeeded ->
      if (!succeeded) sink.error("IdeaVim: VS Code has no command '$command'.")
      pending--
      if (pending == 0) hostCommandsFinished()
    }
  }

  private fun hostCommandsFinished() {
    // The command changed the document, and it changed it without going through the buffer - so
    // every editor has to be re-read before anything else looks at one.
    for (editor in editors.values) {
      if (editor.buffer.syncIfDocumentMoved()) editor.syncCaretsFromEditor()
    }
    val waiting = queued.toList()
    queued.clear()
    waiting.forEach { it() }
  }

  /**
   * Takes VS Code's selections as the carets, which is what happens when the user clicks or drags.
   *
   * Only when nothing is pending, and only when VS Code says the user did it - see the `kind` check
   * below, which is the part that matters and the part a stub host cannot have, because a stub
   * fires no events at all.
   */
  fun selectionChanged(textEditor: TextEditor, kind: Int?) {
    val editor = editors[identityOf(textEditor)] ?: return
    if (editor.nativeEditor !== textEditor) return
    if (pending > 0) return
    // Only a change VS Code attributes to the user. `kind` is null for one it did not attribute -
    // an edit moving its own caret, or an extension writing `TextEditor.selections` - and both of
    // those are this host's own writing coming back. Reading them was the reason `O` opened a line
    // above and left the caret below it, and the reason a blockwise Visual selection came apart one
    // column per line: the block's anchor was replaced by whatever VS Code last reported for the
    // line the caret happened to be on.
    if (kind == null) return
    editor.syncCaretsFromEditor()
    if (editor.followSelectionIntoMode()) {
      // The mode changed without a key causing it, and `KeyHandler` is holding state that assumed
      // the old one - a partial command, a pending count. Entering visual mode behind its back and
      // then pressing `d` makes it try to go operator-pending *from* visual, which the engine
      // rejects outright.
      KeyHandler.getInstance().reset(editor)
    }
  }

  /**
   * Re-reads the system clipboard, which is worth doing whenever it may have changed elsewhere.
   *
   * The window regaining focus is the moment that matters: a user copying in a browser and coming
   * back is the case `"+p` has to get right, and by the time they press the key the answer has
   * arrived.
   */
  fun refreshClipboard() {
    clipboard.refresh()
  }

  /**
   * What the last keystroke left behind, for `ideavim.trace`.
   *
   * The mode, every caret with its selection, and what was handed to VS Code - which is the set of
   * facts that took four rounds of guessing to get at for one bug in a real window. A user who can
   * turn this on can report a window-only problem in one message.
   */
  fun describeState(textEditor: TextEditor): String {
    val editor = editors[identityOf(textEditor)] ?: return "${modeName()} (no editor registered)"
    val carets = editor.carets().joinToString(", ") { caret ->
      val where = if (caret.hasSelection()) "${caret.offset}[${caret.selectionStart}..${caret.selectionEnd}]" else "${caret.offset}"
      where + (if (caret.isPrimary) "*" else "") + "/col" + caret.vimLastColumn
    }
    val pushed = textEditor.selections.joinToString(", ") {
      "(${it.anchor.line},${it.anchor.character})-(${it.active.line},${it.active.character})"
    }
    return "${modeName()} carets=[$carets] pushed=[$pushed]"
  }

  /** Vim's mode, named the way Vim names it on the last line. */
  fun modeName(): String {
    val mode = injector.vimState.mode
    return when (mode) {
      is Mode.INSERT -> "INSERT"
      is Mode.REPLACE -> "REPLACE"
      // Vim distinguishes the three visual kinds on screen, and they behave differently enough
      // that a user needs to see which one they are in.
      is Mode.VISUAL -> "VISUAL${suffixFor(mode.selectionType)}"
      is Mode.SELECT -> "SELECT${suffixFor(mode.selectionType)}"
      is Mode.OP_PENDING -> "OP PENDING"
      is Mode.CMD_LINE -> "COMMAND"
      is Mode.NORMAL -> "NORMAL"
    }
  }

  private fun suffixFor(selectionType: SelectionType): String = when (selectionType) {
    SelectionType.CHARACTER_WISE -> ""
    SelectionType.LINE_WISE -> " LINE"
    SelectionType.BLOCK_WISE -> " BLOCK"
  }
}

/** Node's environment, which is the user's - `HOME`, `XDG_CONFIG_HOME` and the rest. */
private fun environmentVariable(name: String): String? = injector.systemInfoService.getenv(name)

/** For a host that has nowhere to draw a command line yet. */
internal object NoCommandLineDisplay : CommandLineDisplay {
  override fun show(text: String) {}
  override fun hide() {}
}

/**
 * Runs a VS Code command through the real extension host.
 *
 * `executeCommand` resolves with whatever the command returned, which for `undo` is nothing useful
 * - the callback is about *when*, not about what.
 */
private fun executeVsCodeCommand(command: String, arguments: Array<Any?>, onDone: (Boolean) -> Unit) {
  commands.executeCommand(command, *arguments).then({ onDone(true) }, { onDone(false) })
}

/**
 * Nothing to carry. `ExecutionContext` is how IntelliJ threads its `DataContext` through the
 * engine; VS Code has no such object, and the engine reaches for it only when handing control back
 * to the host.
 */
internal object VsCodeExecutionContext : com.maddyhome.idea.vim.api.ExecutionContext {
  override val context: Any get() = TODO("VS Code host has no execution context")
}
