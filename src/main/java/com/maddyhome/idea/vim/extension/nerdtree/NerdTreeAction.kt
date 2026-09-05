/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.nerdtree

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.ui.SwingActionDelegate
import com.intellij.ui.treeStructure.Tree
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.newapi.runIjAction

/**
 * Defines the actual behavior of actions in NERDTree
 */
class NerdTreeAction(val action: (AnActionEvent, Tree) -> Unit) {
  companion object {
    /** See [runIjAction], which this was and where the ex commands' half of NERDTree left it. */
    fun callAction(editor: VimEditor?, name: String) = runIjAction(editor, name)

    /**
     * Creates an [NerdTreeAction] that executes an IntelliJ action identified by its ID.
     *
     * @param id A string representing the ID of the action to execute.
     * @return An [NerdTreeAction] that runs the specified action when triggered.
     */
    fun ij(id: String) = NerdTreeAction { event, _ -> callAction(null, id) }

    /**
     * Creates an [NerdTreeAction] that delegates to the JTree's Swing ActionMap.
     */
    fun swing(swingActionId: String) =
      NerdTreeAction { _, tree -> SwingActionDelegate.performAction(swingActionId, tree) }
  }
}
