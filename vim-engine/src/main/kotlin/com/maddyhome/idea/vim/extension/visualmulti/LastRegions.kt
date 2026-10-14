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
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.multiplecursors.OccurrenceSearch
import com.maddyhome.idea.vim.extension.multiplecursors.occurrenceSearch
import com.maddyhome.idea.vim.extension.multiplecursors.selectRegions
import com.maddyhome.idea.vim.extension.multiplecursors.selectedRange

/**
 * The regions of the last session, by offset as vim-visual-multi keeps them - so they are wrong once
 * the text has changed, which is why the reselect drops any that no longer fit the buffer.
 */
private class LastRegions(val regions: List<TextRange>, val search: OccurrenceSearch?)

private val lastRegions: MutableMap<String, LastRegions> = mutableMapOf()

/** Drops every buffer's remembered regions, for `set novisual-multi`. */
internal fun forgetLastRegions(): Unit = lastRegions.clear()

internal fun saveLastRegions(editor: VimEditor) {
  val regions = editor.carets().map { it.selectedRange }.filter { it.endOffset > it.startOffset }
  if (regions.isNotEmpty()) {
    lastRegions[editor.getPath() ?: ""] = LastRegions(regions, editor.occurrenceSearch)
  }
}

/** Selects the regions of the last session again, and carries on its search. */
internal class ReselectLastHandler : ExtensionHandler {

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    val last = lastRegions[editor.getPath() ?: ""] ?: return
    val textLength = editor.text().length
    val regions = last.regions.filter { it.endOffset <= textLength }
    if (regions.isEmpty()) return

    editor.selectRegions(regions)
    editor.occurrenceSearch = last.search
  }
}
