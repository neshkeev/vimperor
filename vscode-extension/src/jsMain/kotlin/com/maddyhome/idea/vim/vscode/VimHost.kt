/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.VimMatchHighlighter
import com.maddyhome.idea.vim.sign.VimSignDisplay
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
  /** Where `:match` is painted. Decorations, in a real window; a recorder, in a test. */
  matchHighlighter: VimMatchHighlighter = VsCodeMatchHighlighter(),
  /** Where `:sign` is drawn. Gutter icons and line decorations, in a real window. */
  signDisplay: VimSignDisplay = VsCodeSignDisplay(),
) : HostCommandRunner {

  private val vimInjector =
    VsCodeInjector(
      sink, this, commandLineDisplay, highlighter, clipboard, outputPanel, processes, opener, matchHighlighter,
      signDisplay,
    )

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
    // Before the `.ideavimrc` runs, because an option that is not registered yet is
    // `E518: Unknown option` rather than an option with no effect. Not from `VsCodeInjector`'s own
    // `init`, which is too early: `Options` is a Kotlin object that has not been initialised at
    // that point, and `addOption` on it goes to `undefined` - the same declaration-order rule that
    // `VsCodeEditor.pushedSelections` and the tutor's host both had to be moved for.
    VsCodeOptions.initialise()
    watchLineNumbers()
  }

  /**
   * Runs the user's config, if there is one: `~/.vimperorrc` for choice, `~/.ideavimrc` otherwise.
   * [findVimRc] has the full order and the reason for it.
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
      sink.error("Vimperor: " + path + " could not be run: " + e.message)
    } finally {
      vimInjector.optionGroup.endInitVimRc()
    }
    // The config is where `'number'` and `'relativenumber'` are set, and it runs before any key is
    // pressed, so nothing else would apply them until one was.
    editors.values.forEach { applyEditorOptions(it) }
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
    editors[identity] = editor
    // Registered before the old one is retired, not after. The new editor takes its window-local
    // options from whichever editor was active - and retiring the old one first made that the
    // fallback window, whose options are the defaults, so a replaced editor lost `'relativenumber'`
    // and everything else the config had set. Retiring it afterwards is safe: `unregister` only
    // moves the active editor if the one going away was it, and by then it is not.
    vimInjector.register(editor)
    existing?.let { vimInjector.unregister(it) }
    // A window-local option applies to a window that did not exist when it was set. `'number'` and
    // `'relativenumber'` come from the `.ideavimrc`, which runs once, and every editor opened after
    // it has to be told - the change listener only fires when the value changes.
    applyEditorOptions(editor)
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
        sink.error("Vimperor: the document changed while a command was running, so it was not applied.")
      }
    }
    // Vim owns the gutter and the language mode, so the host writes them whenever Vim's answer
    // has changed - which the option listeners alone cannot guarantee. See [watchLineNumbers].
    applyEditorOptions(editor)
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
  override fun run(command: String, arguments: Array<Any?>, waitForIt: Boolean, afterwards: () -> Unit) {
    if (!waitForIt) {
      runCommand(command, arguments) { succeeded ->
        if (!succeeded) sink.error("Vimperor: VS Code has no command '$command'.")
      }
      return
    }
    pending++
    // Held until the re-read below, not run here: at this point the buffer still holds the text
    // from before the command, and the whole reason a caller wants this hook is to place a caret
    // in the text the command produced.
    landed += afterwards
    runCommand(command, arguments) { succeeded ->
      if (!succeeded) sink.error("Vimperor: VS Code has no command '$command'.")
      pending--
      if (pending == 0) hostCommandsFinished()
    }
  }

  /** What to do once the document is back in step - see [run]. */
  private val landed: MutableList<() -> Unit> = mutableListOf()

  private fun hostCommandsFinished() {
    // The command changed the document, and it changed it without going through the buffer - so
    // every editor has to be re-read before anything else looks at one.
    for (editor in editors.values) {
      if (editor.buffer.syncIfDocumentMoved()) editor.syncCaretsFromEditor()
      // A command may leave its own selection behind - the formatter does - and the engine is not
      // in visual mode by the time it lands.
      editor.dropSelectionLeftByCommand()
    }
    // After the re-read and before the held keys: a caret placed here is what the user sees, and a
    // queued keystroke has to act on it rather than on where the caret was left by the command.
    val after = landed.toList()
    landed.clear()
    after.forEach { it() }

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
   * What VS Code answered when asked what commands it has, for `:action` and `:actionlist`.
   *
   * Asked once, at activation, because `commands.getCommands` returns a promise and both of those
   * have to answer during a keystroke. See [VsCodeActionExecutor].
   */
  fun rememberActions(ids: Collection<String>) = vimInjector.rememberActions(ids)

  /**
   * The chords bound to those commands, for `:actionlist`'s second column.
   *
   * Handed over a source at a time rather than all at once: the extensions' manifests are readable
   * the moment activation runs, the user's file is a synchronous disk read, and VS Code's own
   * defaults arrive over a promise. See [KeybindingTable] for why that order does not decide which
   * of them wins.
   */
  val keybindings: KeybindingTable get() = vimInjector.keybindings

  /**
   * The system paste chord at the `:` and `/` prompts - `Cmd+V`, or `Ctrl+Shift+V` elsewhere.
   *
   * Vim's own way to do this is `<C-R>+`, which inserts the clipboard register into the command
   * line, and that is exactly what this sends: the engine already implements it, with the register
   * arriving as the argument to `<C-R>` rather than through `getchar()`, so there is nothing here
   * but the gesture. What VS Code adds is that `Cmd+V` is not a key an extension can be handed
   * as a keystroke - it is a keybinding, and one that has to be claimed only while the prompt is
   * open, or it would take paste away from the editor.
   *
   * The clipboard is re-read first, and this is the one place that is worth a promise. Everywhere
   * else a register read happens mid-keystroke and has to answer from the mirror; a paste is a
   * gesture of its own, so it can wait for the true answer - which is what makes pasting something
   * copied in the integrated terminal work, where the window never lost focus and the mirror was
   * never refreshed.
   *
   * [onDone] runs after the keys have been handled, for the trace and the mode indicator.
   */
  fun pasteIntoCommandLine(textEditor: TextEditor, onDone: () -> Unit = {}) {
    clipboard.refresh {
      // The `when` clause on the keybinding is what keeps this out of the editor's way, so by the
      // time it fires the mode is already CMD_LINE. Checked again because a stale context key would
      // otherwise turn a paste into `<C-R>` in Normal mode, which is redo.
      if (injector.vimState.mode is Mode.CMD_LINE) key(textEditor, "<C-R>+")
      onDone()
    }
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
    // The viewport as the extension can see it. `visibleRanges` is a snapshot VS Code refreshes
    // when it paints, and every scroll command reasons from it - so when scrolling misbehaves, the
    // first question is whether this says what the window looks like. An empty array is worth
    // seeing too: the fallbacks for it claim the whole file is on screen.
    val ranges = textEditor.visibleRanges
    val view = if (ranges.isEmpty()) "none" else ranges.joinToString(",") { "${it.start.line}..${it.end.line}" }
    // What this host asked the view to do while the key was being handled, and in what order. When
    // the window disagrees with where Vim thinks it is, this is the evidence.
    val asked = if (editor.scrollLog.isEmpty()) "" else " scrolls=[" + editor.scrollLog.joinToString(" ") + "]"
    editor.scrollLog.clear()
    val believed = editor.believedTopLine?.let { " believedTop=$it" } ?: ""
    return "${modeName()} carets=[$carets] pushed=[$pushed] view=[$view] of ${editor.lineCount()}$believed$asked"
  }

  /**
   * The caret's shape, which is how Vim tells you which mode you are in.
   *
   * Vim's own default `guicursor` is `n-v-c:block,i-ci:ver25,r-cr:hor20`: a block in Normal, Visual
   * and on the command line, a vertical bar in Insert, an underline in Replace. That is what this
   * maps, and it is the one editor option a Vim emulator has any business writing - the shape *is*
   * the mode indicator, and a user reads it a hundred times a minute without looking at the status
   * bar.
   *
   * Operator-pending is a block too. Vim leaves it alone there and so does this: `d` is not a mode
   * you are typing in, it is a command half-finished.
   */
  fun cursorStyleFor(mode: String): Int = when {
    mode == "INSERT" -> TextEditorCursorStyle.Line
    mode == "REPLACE" -> TextEditorCursorStyle.Underline
    else -> TextEditorCursorStyle.Block
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
  override fun show(text: String, caret: Int?) {}
  override fun showMatches(line: String?) {}
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
