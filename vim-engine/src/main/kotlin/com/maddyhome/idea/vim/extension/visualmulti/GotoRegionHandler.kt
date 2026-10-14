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
import com.maddyhome.idea.vim.common.Direction
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.multiplecursors.occurrenceSearch
import com.maddyhome.idea.vim.extension.multiplecursors.selectedRange

/**
 * Makes the next or the previous region the current one, without adding any. Wraps around.
 *
 * The current region is the primary caret, and the search carries on from it in the same direction.
 * This is the second thing that needed `VimEditor.makePrimaryCaret`: walking the current region is
 * the whole of what `[` and `]` do, and nothing in the engine could move the primary before.
 */
internal class GotoRegionHandler(private val direction: Direction) : ExtensionHandler {

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    repeat(operatorArguments.count1) {
      gotoRegion(editor)
    }
  }

  private fun gotoRegion(editor: VimEditor) {
    val carets = editor.carets()
    if (carets.size < 2) return

    val current = carets.indexOfFirst { it.isPrimary }
    if (current < 0) return
    val target = carets[(current + direction.toInt()).mod(carets.size)]
    val region = editor.makePrimaryCaret(target).selectedRange
    editor.occurrenceSearch = editor.occurrenceSearch?.copy(lastOccurrence = region, direction = direction)
  }
}
