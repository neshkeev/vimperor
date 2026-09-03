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
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimMatchHighlighter
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.highlight.HighlightAttributes
import com.maddyhome.idea.vim.highlight.HighlightGroup
import com.maddyhome.idea.vim.highlight.UnderlineStyle
import com.maddyhome.idea.vim.newapi.ij
import java.awt.Color
import java.awt.Font

/**
 * `:match`, over IntelliJ's markup model.
 *
 * The highlighters are held per editor and per channel and replaced whole on every repaint, which
 * is what `:match` being a *standing* highlight costs: the pattern is re-run after each keystroke,
 * so the old ranges have to go before the new ones arrive or the editor fills with stale markup.
 *
 * Two sources of colour, and which one is used is the user's choice rather than this class's. A
 * group `:highlight` has defined is built from that definition, hex and all, because a config that
 * wrote `guibg=#503030` meant that colour and not an approximation of it. A group nobody has
 * defined - which is most of them, including every `:match Search` in a config that never mentioned
 * `:highlight` - comes from the IDE's own scheme instead, so a match looks like the editor's own
 * highlighting in whatever theme is loaded.
 */
internal class IjMatchHighlighter : VimMatchHighlighter {

  private val painted = mutableMapOf<Editor, MutableMap<Int, List<RangeHighlighter>>>()

  override fun showMatches(editor: VimEditor, channel: Int, group: HighlightGroup, ranges: List<TextRange>) {
    val ij = editor.ij
    removeChannel(ij, channel)

    val attributes = attributesFor(ij, group) ?: return
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

  /**
   * Null means paint nothing, which happens for `:highlight {group} NONE` and for a scheme that
   * has no attributes under the key - the second is rare and the first is a thing users ask for.
   */
  private fun attributesFor(editor: Editor, group: HighlightGroup): TextAttributes? {
    val defined = group.attributes ?: return editor.colorsScheme.getAttributes(keyFor(group.name))
    if (defined.paintsNothing) return null
    return defined.toIj()
  }

  /**
   * A `:highlight` definition as IntelliJ's own attributes.
   *
   * `reverse` is the one that cannot be done here the way Vim does it. Vim swaps the foreground and
   * background at draw time, so it works even when neither was named; IntelliJ wants two colours in
   * two fields, and the colours to swap are the ones the editor is currently using for text under
   * a theme this cannot read per-token. So a `reverse` with both colours given swaps them, and a
   * `reverse` with neither is left alone rather than guessed at.
   */
  private fun HighlightAttributes.toIj(): TextAttributes {
    val swap = reverse && (foreground != null || background != null)
    val fore = colour(if (swap) background else foreground)
    val back = colour(if (swap) foreground else background)

    val effect = when (underline) {
      UnderlineStyle.NONE -> if (strikethrough) EffectType.STRIKEOUT else null
      UnderlineStyle.CURL -> EffectType.WAVE_UNDERSCORE
      UnderlineStyle.DOUBLE -> EffectType.BOLD_LINE_UNDERSCORE
      UnderlineStyle.DOTTED, UnderlineStyle.DASHED -> EffectType.BOLD_DOTTED_LINE
      UnderlineStyle.STRAIGHT -> EffectType.LINE_UNDERSCORE
    }
    // Vim keeps the underline's colour apart from the text's; when it was not given, the effect is
    // drawn in the text colour, which is what IntelliJ does with a null effect colour anyway.
    val effectColour = if (effect == null) null else colour(special) ?: fore

    var style = Font.PLAIN
    if (bold) style = style or Font.BOLD
    if (italic) style = style or Font.ITALIC

    return TextAttributes(fore, back, effectColour, effect, style)
  }

  private fun colour(hex: String?): Color? =
    hex?.let { Color(it.removePrefix("#").toInt(16), false) }

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
