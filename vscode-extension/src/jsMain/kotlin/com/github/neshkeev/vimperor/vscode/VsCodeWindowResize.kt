/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimWindowResizeService
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.resize.ResizeArgument
import kotlin.math.abs

/**
 * `<C-W>+`, `<C-W>-`, `<C-W><`, `<C-W>>`, `<C-W>_`, `<C-W>|`, `<C-W>=` and `:resize`, over VS
 * Code's editor groups.
 *
 * Two of the three argument shapes come across and one does not, and which is which follows from a
 * single fact: **VS Code resizes in steps it chooses, and will not be told a size.** Its commands
 * are "make this view bigger" and "make it smaller", with no unit and no way to ask what the
 * current size is.
 *
 * So a relative change - `<C-W>3+` - becomes three steps, which is right in kind if not in
 * magnitude: Vim's unit is a row of text and VS Code's is whatever it feels like, but "press it
 * three times" is what the user asked for and what they would have done by hand. Maximising has a
 * command of its own. An absolute size does not translate at all, and this says so rather than
 * guessing at a number of steps that would get close - see [refuseAbsolute].
 *
 * The size is the *editor group's*, which is the closest thing this host has to a Vim window. It
 * follows that `<C-W>=` is `evenEditorWidths`: VS Code has no command for evening the heights, so
 * `<C-W>=` does half of what Vim's does. Saying which half beats leaving it unbuilt.
 */
internal class VsCodeWindowResize(private val hostCommands: HostCommandRunner) : VimWindowResizeService {

  override fun resizeCurrentWindowHeight(editor: VimEditor, argument: ResizeArgument) {
    when (argument) {
      is ResizeArgument.Relative -> step(
        argument.count,
        VsCodeCommands.INCREASE_VIEW_HEIGHT,
        VsCodeCommands.DECREASE_VIEW_HEIGHT,
      )

      // Not `toggleEditorWidths`' vertical twin, because there isn't one: maximising a group takes
      // both dimensions at once, which is more than `<C-W>_` asks for and the nearest thing there is.
      is ResizeArgument.Maximize -> run(VsCodeCommands.MAXIMIZE_EDITOR_GROUP)
      is ResizeArgument.Absolute -> refuseAbsolute(editor, "rows")
    }
  }

  override fun resizeCurrentWindowWidth(editor: VimEditor, argument: ResizeArgument) {
    when (argument) {
      is ResizeArgument.Relative -> step(
        argument.count,
        VsCodeCommands.INCREASE_VIEW_WIDTH,
        VsCodeCommands.DECREASE_VIEW_WIDTH,
      )

      is ResizeArgument.Maximize -> run(VsCodeCommands.TOGGLE_EDITOR_WIDTHS)
      is ResizeArgument.Absolute -> refuseAbsolute(editor, "columns")
    }
  }

  /** `<C-W>=`. Widths only; VS Code has no command for the heights. */
  override fun equalizeWindows(editor: VimEditor) {
    run(VsCodeCommands.EVEN_EDITOR_WIDTHS)
  }

  private fun step(count: Int, larger: String, smaller: String) {
    val command = if (count >= 0) larger else smaller
    repeat(abs(count)) { run(command) }
  }

  /**
   * What `:resize 20` and `<C-W>20_` get, which is a message rather than an approximation.
   *
   * The number is in rows or columns of text and VS Code takes neither. Turning twenty rows into
   * "press bigger four times" would be a guess whose error nobody could see, and Vim's own answer
   * when a window cannot be the size asked for is to say so.
   */
  private fun refuseAbsolute(editor: VimEditor, units: String) {
    injector.messages.showErrorMessage(
      editor,
      "Vimperor: this host resizes in steps, so a window cannot be set to an exact number of $units. " +
        "Use the relative forms - :resize +3, <C-W>3+ - or <C-W>_ to maximise.",
    )
  }

  // Nothing here waits: resizing changes what is on screen and not what is in the buffer, so
  // holding the user's keys for it would only make the editor feel slow.
  private fun run(command: String) = hostCommands.run(command, waitForIt = false)
}
