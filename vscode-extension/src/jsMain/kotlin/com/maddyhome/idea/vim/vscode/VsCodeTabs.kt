/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.group.TabService
import kotlin.math.abs

/**
 * `:tabclose`, `:tabonly`, `:tabmove` - the tabs, which VS Code also calls tabs.
 *
 * The last three ex commands on the list, and the only ones that need to *read* the workbench
 * rather than tell it to do something. `window.tabGroups` is the rare part of it that answers
 * synchronously, which is what makes these possible: `:tabclose` with no argument closes the
 * current tab, but with a count it closes the Nth, and neither the count nor "the current one" can
 * be turned into a command without knowing where things are.
 *
 * A Vim tab page holds a whole window layout and a VS Code tab holds one editor, so `:tabonly` here
 * closes the other editors in the group rather than collapsing a layout. That is the same trade
 * `<C-W>` makes; see [VsCodeWindowGroup].
 */
internal class VsCodeTabs(private val host: HostCommandRunner) : TabService {

  private fun run(command: String) = host.run(command, waitForIt = false)

  private val tabs get() = window.tabGroups.activeTabGroup.tabs

  override fun getTabCount(context: ExecutionContext): Int = tabs.size

  override fun getCurrentTabIndex(context: ExecutionContext): Int =
    tabs.indexOfFirst { it.isActive }.coerceAtLeast(0)

  /**
   * Closes the tab at [indexToDelete] and leaves [indexToSelect] in front.
   *
   * VS Code closes the *active* editor rather than one named by number, so the tab to be closed has
   * to be brought forward first. The two commands are dispatched in order and VS Code runs them in
   * order, which is the whole of the sequencing this needs - neither changes a document, so there
   * is nothing for the key queue to protect.
   */
  override fun removeTabAt(indexToDelete: Int, indexToSelect: Int, context: ExecutionContext) {
    if (indexToDelete != getCurrentTabIndex(context)) selectTab(indexToDelete) ?: return
    run("workbench.action.closeActiveEditor")
    selectTab(if (indexToSelect > indexToDelete) indexToSelect - 1 else indexToSelect)
  }

  /**
   * `:tabmove N`.
   *
   * VS Code moves an editor one place at a time, so this is the distance expressed as steps. There
   * is no command that takes a destination.
   */
  override fun moveCurrentTabToIndex(index: Int, context: ExecutionContext) {
    val steps = index - getCurrentTabIndex(context)
    if (steps == 0) return
    val command =
      if (steps > 0) "workbench.action.moveEditorRightInGroup" else "workbench.action.moveEditorLeftInGroup"
    repeat(abs(steps)) { run(command) }
  }

  override fun closeAllExceptCurrentTab(context: ExecutionContext) = run("workbench.action.closeOtherEditors")

  /** VS Code names the first nine tabs with a command each and stops; past that there is nothing. */
  private fun selectTab(index: Int): Unit? {
    if (index < 0 || index > 8) return null
    run("workbench.action.openEditorAtIndex${index + 1}")
    return Unit
  }
}
