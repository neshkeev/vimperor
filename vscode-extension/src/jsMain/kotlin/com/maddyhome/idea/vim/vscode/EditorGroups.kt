/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.group.WindowGroupBase
import kotlin.math.abs

/**
 * `<C-W>` - Vim's windows, which VS Code calls editor groups.
 *
 * The two are not the same thing and pretending otherwise would be worse than admitting it. A Vim
 * window is a view onto a buffer and the layout is a tree of splits; a VS Code editor group is a
 * column or row of tabs, and only one tab in each is visible. What they share is the part `<C-W>`
 * is about: there is more than one place to be, they are arranged in two dimensions, and you can
 * split, close and move between them. So `<C-W>s`, `<C-W>v`, `<C-W>c`, `<C-W>o` and the four
 * direction keys all land on a VS Code command that does the nearest true thing.
 *
 * Where the model runs out is `:split file` - Vim opens a named file in the new window, and VS Code
 * splits whatever is already there. Rather than open the file into the wrong place, that says so.
 *
 * None of these wait. They rearrange what is on screen without touching a buffer, so holding the
 * user's keystrokes for them would only make the editor feel slow - and the next key is going to a
 * different editor anyway.
 */
internal class VsCodeWindowGroup(private val host: HostCommandRunner) : WindowGroupBase() {

  private fun run(command: String) = host.run(command, waitForIt = false)

  override fun selectWindowInRow(caret: VimCaret, context: ExecutionContext, relativePosition: Int, vertical: Boolean) {
    // Positive is down when vertical and right when not - see the `<C-W>` actions, which negate the
    // count for `h` and `k` rather than passing a direction.
    val command = when {
      vertical && relativePosition > 0 -> "workbench.action.focusBelowGroup"
      vertical -> "workbench.action.focusAboveGroup"
      relativePosition > 0 -> "workbench.action.focusRightGroup"
      else -> "workbench.action.focusLeftGroup"
    }
    repeat(maxOf(1, abs(relativePosition))) { run(command) }
  }

  override fun selectNextWindow(context: ExecutionContext) = run("workbench.action.focusNextGroup")

  override fun selectPreviousWindow(context: ExecutionContext) = run("workbench.action.focusPreviousGroup")

  /**
   * `<C-W>` with a count - go to window number N.
   *
   * VS Code numbers its groups with eight separate commands rather than one that takes an argument,
   * because `executeCommand` here carries no arguments. Past the eighth there is nothing to call.
   */
  override fun selectWindow(context: ExecutionContext, index: Int) {
    val ordinals = listOf("First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth")
    val ordinal = ordinals.getOrNull(index - 1) ?: return
    run("workbench.action.focus${ordinal}EditorGroup")
  }

  override fun splitWindowHorizontal(context: ExecutionContext, filename: String, focusNew: Boolean) =
    split("workbench.action.splitEditorDown", filename)

  override fun splitWindowVertical(context: ExecutionContext, filename: String, focusNew: Boolean) =
    split("workbench.action.splitEditorRight", filename)

  private fun split(command: String, filename: String) {
    if (filename.isNotEmpty()) {
      // `:split file`. Splitting and then opening the file is two commands and the second needs an
      // argument, which is exactly what this host cannot pass yet.
      val editor = injector.editorGroup.getEditors().firstOrNull() ?: return
      injector.messages.showErrorMessage(editor, "IdeaVim: splitting with a file name is not supported yet.")
      return
    }
    run(command)
  }

  override fun closeCurrentWindow(context: ExecutionContext) = run("workbench.action.closeEditorsAndGroup")

  override fun closeAllExceptCurrent(context: ExecutionContext) = run("workbench.action.closeEditorsInOtherGroups")

  override fun closeAll(context: ExecutionContext) = run("workbench.action.closeAllGroups")
}
