/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.CommandLineCompletion
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimCommandLine
import com.maddyhome.idea.vim.api.VimCommandLineCaret
import com.maddyhome.idea.vim.api.VimCommandLineService
import com.maddyhome.idea.vim.api.VimCommandLineServiceBase
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.key.VimKeyStroke

/**
 * Vim's command line, rendered wherever the host can draw a line of text.
 *
 * This looked like the hardest thing left, on the reasoning that a prompt means `showInputBox` and
 * `showInputBox` resolves a promise. That was wrong about what a Vim command line is. It is not a
 * dialog that collects a string and hands it back - it is a text buffer the *engine* owns, keystroke
 * by keystroke, with its own caret, history and mode. `KeyHandler` routes keys to it while
 * `CMD_LINE` mode is active and calls [handleKey] with whatever it did not consume itself.
 *
 * So the host supplies a string and somewhere to show it, and both of those are synchronous. The
 * asynchronous API was never needed - it was the wrong shape for the problem, not a hard version of
 * the right one.
 */
internal class StatusBarCommandLine(
  override val editor: VimEditor,
  private val label: String,
  initialText: String,
  override val inputProcessing: ((String) -> Unit)?,
  override val finishOn: Char?,
  private val display: CommandLineDisplay,
  /** `'incsearch'`, which needs the pattern as typed so far - and this is what has it. */
  private val preview: IncsearchPreview? = null,
) : VimCommandLine {

  private var content: StringBuilder = StringBuilder(initialText)
  private var promptCharacter: Char? = null

  override val caret: VimCommandLineCaret = Caret(initialText.length) { render() }

  override val text: String get() = content.toString()

  override fun getLabel(): String = label

  override var isReplaceMode: Boolean = false
    private set

  override fun toggleReplaceMode() {
    isReplaceMode = !isReplaceMode
  }

  override var lastEntry: String? = null

  override var activeCompletion: CommandLineCompletion? = null

  override fun isExCommand(): Boolean = label == ":"

  /**
   * What Vim shows on its last line: the label, the text, and the prompt character that stands in
   * for a half-typed digraph or a pending `<C-R>`.
   */
  override fun getRenderedText(): String = buildString {
    append(label)
    append(content)
    promptCharacter?.let { append(it) }
  }

  /**
   * Where the caret is in [getRenderedText], or null when there is nothing to draw.
   *
   * Null while a prompt character is showing, because that character *is* the caret: Vim draws the
   * `"` of a pending `<C-R>` where the cursor was, and a second marker beside it would say the
   * cursor is somewhere it is not.
   */
  private fun caretInRenderedText(): Int? =
    if (promptCharacter != null) null else label.length + caret.offset.coerceIn(0, content.length)

  override val modelessSelection: String get() = ""

  override fun insertText(offset: Int, string: String) {
    val at = offset.coerceIn(0, content.length)
    if (isReplaceMode) {
      val end = minOf(at + string.length, content.length)
      content.deleteRange(at, end)
    }
    content.insert(at, string)
    render()
  }

  /**
   * Replaces the whole line, which is how history recall works: `<Up>` puts an older command in
   * place of what was typed. [updateLastEntry] is false while browsing, so that returning to the
   * bottom of the history brings back what the user had actually written.
   */
  override fun setText(string: String, updateLastEntry: Boolean) {
    if (updateLastEntry) lastEntry = text
    content = StringBuilder(string)
    caret.offset = content.length
    render()
  }

  /**
   * Removes text, and moves the caret with it if it was behind what went.
   *
   * The caret is the host's here. IntelliJ's command line is a text field, which moves its own
   * caret when its document shrinks, so the engine's delete actions do not touch it - and this left
   * the caret past the end of the text, where the *second* backspace in a row threw. One had been
   * tested and two had not.
   */
  override fun deleteText(offset: Int, length: Int) {
    val from = offset.coerceIn(0, content.length)
    val to = (from + length).coerceIn(from, content.length)
    content.deleteRange(from, to)
    if (caret.offset > from) caret.offset = maxOf(from, caret.offset - (to - from))
    render()
  }

  /**
   * A key the key handler did not consume. Vim's command line takes printable characters as text
   * and leaves everything else to the mappings and actions that ran first.
   */
  override fun handleKey(key: VimKeyStroke) {
    val character = key.keyChar
    if (character.code == 0 || character.code == 65535) return
    insertText(caret.offset, character.toString())
    caret.offset += 1
  }

  override fun setPromptCharacter(promptCharacter: Char) {
    this.promptCharacter = promptCharacter
    render()
  }

  override fun clearPromptCharacter() {
    promptCharacter = null
    render()
  }

  override fun clearCurrentAction() {
    injector.commandLine.getActiveCommandLine()?.let { }
  }

  override val isAbbreviationInvalidated: Boolean get() = false

  override fun deactivate(refocusOwningEditor: Boolean, resetCaret: Boolean) {
    // Before hiding, and whether the search ran or was cancelled: what should be on screen next is
    // the search group's answer either way, and the preview is in the way of it.
    preview?.finish(editor, resetCaret)
    display.hide()
  }

  /** Vim's command line takes the keys itself; there is no separate widget to move focus to. */
  override fun focus() {}

  fun render() {
    display.show(getRenderedText(), caretInRenderedText())
    preview?.update(editor, label, text)
  }

  /**
   * The command line's caret, which redraws when it moves.
   *
   * Nothing else would. The engine's command-line motions - `<Left>`, `<Home>`, `<S-Right>` and the
   * rest - are pure assignments to this offset, because IntelliJ's command line is a text field
   * that draws its own caret and needs no telling. A status bar draws a string, so the string has
   * to change when the caret does.
   */
  private class Caret(offset: Int, private val onMove: () -> Unit) : VimCommandLineCaret {
    override var offset: Int = offset
      set(value) {
        val moved = field != value
        field = value
        if (moved) onMove()
      }
  }
}

/**
 * Where the command line is drawn. Separated so a test can read it without a status bar.
 *
 * The caret is passed apart from the text rather than drawn into it, because where it goes is the
 * display's business: a status bar can only splice a character in, and something drawing this into
 * the editor one day could paint a real block. Null means there is nothing to draw - see
 * [StatusBarCommandLine.caretInRenderedText].
 */
interface CommandLineDisplay {
  fun show(text: String, caret: Int?)
  fun hide()
}

/**
 * The `:` and `/` prompts, on the status bar - the closest thing VS Code has to Vim's last line.
 *
 * The caret is a character spliced into the string, because a status bar item is text and nothing
 * else: it takes no styling, so a block drawn over the character under the cursor - which is what
 * Vim does - is not available. A thin bar between characters is what is left, and it is what every
 * other editor draws anyway.
 *
 * It also makes a space at the end of the line visible, which it was not: `:e ` and `:e` looked the
 * same on the status bar and only one of them was going to open a file.
 */
internal class StatusBarPrompt(private val item: StatusBarItem) : CommandLineDisplay {
  override fun show(text: String, caret: Int?) {
    item.text = if (caret == null) text else {
      val at = caret.coerceIn(0, text.length)
      text.substring(0, at) + CARET + text.substring(at)
    }
    item.show()
  }

  override fun hide() {
    item.text = ""
    item.hide()
  }

  private companion object {
    /** U+258F, a one-eighth block: thin enough to read as a caret and not as something typed. */
    const val CARET = "\u258f"
  }
}

/**
 * Opens and closes command lines, and remembers which one is open.
 *
 * `VimCommandLineServiceBase` does the rest - `:` and `/` both come through [createPanel], and the
 * difference between them is the label.
 */
internal class VsCodeCommandLineService(
  private val display: CommandLineDisplay,
  private val highlighter: Highlighter = Highlighter.None,
) : VimCommandLineServiceBase() {

  private var active: StatusBarCommandLine? = null

  override fun createPanel(
    editor: VimEditor,
    context: ExecutionContext,
    label: String,
    initText: String,
  ): VimCommandLine {
    val commandLine = StatusBarCommandLine(editor, label, initText, null, null, ClosingDisplay(), IncsearchPreview(highlighter))
    active = commandLine
    commandLine.render()
    return commandLine
  }

  override fun getActiveCommandLine(): VimCommandLine? = active

  /** One line, wherever it is drawn. Vim's command line is on the last row and never grows here. */
  override fun getActiveCommandLineHeight(): Int = if (active != null) 1 else 0

  override fun fullReset() {
    active?.deactivate(refocusOwningEditor = false, resetCaret = false)
    active = null
  }

  override fun readInputAndProcess(
    vimEditor: VimEditor,
    context: ExecutionContext,
    prompt: String,
    finishOn: Char?,
    processing: (String) -> Unit,
  ) {
    val commandLine = StatusBarCommandLine(vimEditor, prompt, "", processing, finishOn, ClosingDisplay())
    active = commandLine
    commandLine.render()
  }

  /** Clears [active] when the command line it belongs to closes, so nothing outlives its prompt. */
  private inner class ClosingDisplay : CommandLineDisplay {
    override fun show(text: String, caret: Int?) = display.show(text, caret)

    override fun hide() {
      active = null
      display.hide()
    }
  }
}
