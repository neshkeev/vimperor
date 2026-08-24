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
  private val runCommand: (String, () -> Unit) -> Unit = ::executeVsCodeCommand,
  /** Where the `:` and `/` prompts are drawn. The status bar, in a real window. */
  commandLineDisplay: CommandLineDisplay = NoCommandLineDisplay,
) : HostCommandRunner {

  private val vimInjector = VsCodeInjector(sink, this, commandLineDisplay)

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
    vimInjector.functionService.registerHandlers()
  }

  /** The Vim editor for [textEditor], created on first sight and reused after. */
  fun editorFor(textEditor: TextEditor): VsCodeEditor {
    val identity = identityOf(textEditor)
    val existing = editors[identity]
    if (existing != null && existing.nativeEditor === textEditor) return existing

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
    val identity = identityOf(textEditor)
    editors.remove(identity)?.let { vimInjector.unregister(it) }
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
    for (stroke in keys) {
      handler.handleKey(editor, stroke, VsCodeExecutionContext, state)
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

  override fun run(command: String) {
    pending++
    runCommand(command) {
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
   * Only when nothing is pending: a selection change caused by the engine's own flush would
   * otherwise be read back as if the user had made it.
   */
  fun selectionChanged(textEditor: TextEditor) {
    val editor = editors[identityOf(textEditor)] ?: return
    if (editor.nativeEditor !== textEditor) return
    if (pending > 0) return
    editor.syncCaretsFromEditor()
    if (editor.followSelectionIntoMode()) {
      // The mode changed without a key causing it, and `KeyHandler` is holding state that assumed
      // the old one - a partial command, a pending count. Entering visual mode behind its back and
      // then pressing `d` makes it try to go operator-pending *from* visual, which the engine
      // rejects outright.
      KeyHandler.getInstance().reset(editor)
    }
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
private fun executeVsCodeCommand(command: String, onDone: () -> Unit) {
  commands.executeCommand(command).then { onDone() }
}

/**
 * Nothing to carry. `ExecutionContext` is how IntelliJ threads its `DataContext` through the
 * engine; VS Code has no such object, and the engine reaches for it only when handing control back
 * to the host.
 */
internal object VsCodeExecutionContext : com.maddyhome.idea.vim.api.ExecutionContext {
  override val context: Any get() = TODO("VS Code host has no execution context")
}
