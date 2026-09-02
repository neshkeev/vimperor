/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimCommandLineCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimModalInput
import com.maddyhome.idea.vim.api.VimModalInputBase
import com.maddyhome.idea.vim.api.VimModalInputService
import com.maddyhome.idea.vim.key.interceptors.VimInputInterceptor

/**
 * A prompt that answers one keystroke at a time - `:s///c`'s "Replace with X (y/n/a/q/l)?".
 *
 * The same mistake was waiting here as at the command line, and it is worth naming twice: a modal
 * input is not a dialog that collects an answer and hands it back. It is a label on screen plus an
 * interceptor, and `ModalInputConsumer` asks on *every* keystroke whether one is open and routes
 * the key to it. Nothing about that is asynchronous, so `showInputBox` was never the shape of it -
 * which is what "showInputBox is asynchronous" had been standing in for here since the beginning.
 *
 * There is no text buffer, which is the difference from [StatusBarCommandLine]: `y` is an answer,
 * not a character being typed. The caret exists because the interface has one and Vim draws a block
 * at the end of the prompt; it never moves.
 */
internal class StatusBarModalInput(
  override var inputInterceptor: VimInputInterceptor,
  override val label: String,
  private val display: CommandLineDisplay,
  private val onDeactivate: () -> Unit,
) : VimModalInputBase() {

  override val caret: VimCommandLineCaret = Caret()

  override fun deactivate(refocusOwningEditor: Boolean, resetCaret: Boolean) {
    onDeactivate()
    display.hide()
  }

  /** The prompt takes the keys itself; there is no separate widget to move focus to. */
  override fun focus() {}

  fun render() {
    // One keystroke and no line to edit, so there is no caret to draw.
    display.show(label, null)
  }

  private class Caret : VimCommandLineCaret {
    override var offset: Int = 0
  }
}

/**
 * Opens and closes prompts, and remembers which one is open.
 *
 * `getCurrentModalInput` is asked before every keystroke is interpreted, so "none is open" has to
 * be cheap and exactly true - a stale prompt here would swallow every key in the editor.
 */
internal class VsCodeModalInputService(private val display: CommandLineDisplay) : VimModalInputService {

  private var active: StatusBarModalInput? = null

  override fun getCurrentModalInput(): VimModalInput? = active

  override fun create(
    editor: VimEditor,
    context: ExecutionContext,
    label: String,
    inputInterceptor: VimInputInterceptor,
  ): VimModalInput {
    active?.deactivate(refocusOwningEditor = false, resetCaret = false)
    val input = StatusBarModalInput(inputInterceptor, label, display) { active = null }
    active = input
    input.render()
    return input
  }

  /**
   * Reading a key from inside a running command, which is what `getchar()` and IdeaVim's bundled
   * extensions are built on. IntelliJ can do it because it can spin a modal event loop and block;
   * JavaScript has one thread and no way to stop it, so a host here cannot answer at all. The
   * default does nothing, and this says why rather than pretending.
   */
  fun reset() {
    active = null
  }
}
