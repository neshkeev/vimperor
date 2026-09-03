/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.group

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimMatchHighlighter
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.newapi.ij

/**
 * `:match`, over IntelliJ's markup model.
 *
 * The highlighters are held per editor and per channel and replaced whole on every repaint, which
 * is what `:match` being a *standing* highlight costs: the pattern is re-run after each keystroke,
 * so the old ranges have to go before the new ones arrive or the editor fills with stale markup.
 *
 * The colours come from the IDE's own scheme rather than from literals - `EditorColors.SEARCH` for
 * Vim's `Search`, the error stripe for `ErrorMsg` - so a match looks like the editor's own
 * highlighting in whatever theme is loaded, and a name this fork does not know falls back to the
 * search colour rather than failing. This fork has no `:highlight` to define a group, so the names
 * are only ever Vim's standard ones.
 */
internal class IjMatchHighlighter : VimMatchHighlighter {

  private val painted = mutableMapOf<Editor, MutableMap<Int, List<RangeHighlighter>>>()

  override fun showMatches(editor: VimEditor, channel: Int, group: String, ranges: List<TextRange>) {
    val ij = editor.ij
    removeChannel(ij, channel)

    val attributes = ij.colorsScheme.getAttributes(keyFor(group)) ?: return
    val highlighters = ranges.map { range ->
      ij.markupModel.addRangeHighlighter(
        range.startOffset.coerceIn(0, ij.document.textLength),
        range.endOffset.coerceIn(0, ij.document.textLength),
        // Below the selection, so that selecting matched text still reads as selected.
        HighlighterLayer.SELECTION - 2,
        attributes,
        HighlighterTargetArea.EXACT_RANGE,
      )
    }
    painted.getOrPut(ij) { mutableMapOf() }[channel] = highlighters
  }

  override fun clearMatches(editor: VimEditor, channel: Int) {
    removeChannel(editor.ij, channel)
  }

  private fun removeChannel(editor: Editor, channel: Int) {
    val forEditor = painted[editor] ?: return
    forEditor.remove(channel)?.forEach { editor.markupModel.removeHighlighter(it) }
    if (forEditor.isEmpty()) painted.remove(editor)
  }

  /** Vim's highlight group, as the nearest thing in the IDE's colour scheme. */
  private fun keyFor(group: String): TextAttributesKey = when (group) {
    "Search" -> EditorColors.SEARCH_RESULT_ATTRIBUTES
    "IncSearch" -> EditorColors.SEARCH_RESULT_ATTRIBUTES
    // `CodeInsightColors` rather than `EditorColors` for these: the errors-and-warnings keys are
    // the inspection highlighting's, which is where the IDE keeps a red squiggle and a yellow one.
    "ErrorMsg" -> CodeInsightColors.ERRORS_ATTRIBUTES
    "WarningMsg" -> CodeInsightColors.WARNINGS_ATTRIBUTES
    "Todo" -> CodeInsightColors.TODO_DEFAULT_ATTRIBUTES
    "Underlined" -> EditorColors.IDENTIFIER_UNDER_CARET_ATTRIBUTES
    "Visual" -> EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES
    "MatchParen" -> CodeInsightColors.MATCHED_BRACE_ATTRIBUTES
    else -> EditorColors.SEARCH_RESULT_ATTRIBUTES
  }
}
