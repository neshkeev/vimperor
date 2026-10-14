/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.visualmulti

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.multiplecursors.enterCharacterwiseVisualMode
import com.maddyhome.idea.vim.extension.multiplecursors.leaveVisualMode
import com.maddyhome.idea.vim.group.visual.vimSetSelection
import com.maddyhome.idea.vim.state.mode.inVisualMode

/**
 * Switches between the regions, which are Visual selections, and plain cursors in Normal mode.
 *
 * As in vim-visual-multi, a region becomes a cursor where its selection started, and a cursor
 * becomes a region of one character.
 */
internal class SwitchModeHandler : ExtensionHandler {

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    if (editor.inVisualMode) {
      collapseRegionsToCursors(editor)
    } else {
      cursorsToRegions(editor)
    }
  }

  private fun cursorsToRegions(editor: VimEditor) {
    enterCharacterwiseVisualMode(editor)
    editor.nativeCarets().forEach { it.vimSetSelection(it.offset) }
  }
}

/**
 * Leaves Visual mode with a cursor where each region's selection started, which is what
 * vim-visual-multi does on the way to cursor mode.
 *
 * The starts are read *before* leaving Visual mode, because leaving it drops the selections - and
 * they are read into a list beside the caret rather than off the carets afterwards, since a caret is
 * a value here and the ones in hand would be describing a selection that no longer exists.
 */
internal fun collapseRegionsToCursors(editor: VimEditor) {
  val selectionStarts = editor.nativeCarets().map { it to it.vimSelectionStart }
  leaveVisualMode(editor)
  selectionStarts.forEach { (caret, start) -> caret.moveToOffset(start) }
}
