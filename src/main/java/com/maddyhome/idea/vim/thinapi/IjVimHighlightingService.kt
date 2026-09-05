/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.thinapi

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.vim.api.models.Color
import com.intellij.vim.api.models.HighlightId
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.newapi.ij
import java.awt.Font
import java.awt.Color as AwtColor

internal class IjHighlightId(
  internal var ijHighlighter: RangeHighlighter,
) : HighlightId

private fun Color.toAwtColor(): AwtColor = AwtColor(r, g, b, a)

class IjVimHighlightingService : VimHighlightingService {
  override fun addHighlighter(
    editor: VimEditor,
    startOffset: Int,
    endOffset: Int,
    backgroundColor: Color?,
    foregroundColor: Color?,
  ): HighlightId {
    val ijEditor = editor.ij

    val attributes = TextAttributes(
      foregroundColor?.toAwtColor(),
      backgroundColor?.toAwtColor(),
      ijEditor.colorsScheme.getColor(EditorColors.CARET_COLOR),
      EffectType.SEARCH_MATCH,
      Font.PLAIN,
    )

    val iJHighlighter = ijEditor.markupModel.addRangeHighlighter(
      startOffset,
      endOffset,
      HighlighterLayer.SELECTION,
      attributes,
      HighlighterTargetArea.EXACT_RANGE,
    )

    val highlighter = IjHighlightId(iJHighlighter)
    return highlighter
  }

  /**
   * The scheme's own search-result background where none was named, cached until the theme changes.
   *
   * This is what `highlightedyank` used to read for itself, and reading it is the whole reason it
   * had IntelliJ in it. The cache and its invalidation come with it - see [HighlightColorResetter],
   * which is registered on IntelliJ's look-and-feel listener.
   */
  override fun addSearchHighlighter(
    editor: VimEditor,
    startOffset: Int,
    endOffset: Int,
    backgroundColor: Color?,
    foregroundColor: Color?,
  ): HighlightId = addHighlighter(
    editor,
    startOffset,
    endOffset,
    backgroundColor ?: defaultSearchBackground(),
    foregroundColor,
  )

  override fun removeHighlighter(editor: VimEditor, highlightId: HighlightId) {
    val ijEditor = editor.ij
    ijEditor.markupModel.removeHighlighter((highlightId as IjHighlightId).ijHighlighter)
  }
}

private var cachedSearchBackground: Color? = null

private fun defaultSearchBackground(): Color? {
  cachedSearchBackground?.let { return it }
  val awt = EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES.defaultAttributes.backgroundColor ?: return null
  return Color(awt.red, awt.green, awt.blue, awt.alpha).also { cachedSearchBackground = it }
}

/** A new theme has different colours, so the one that was cached is no longer the right answer. */
internal class HighlightColorResetter : LafManagerListener {
  override fun lookAndFeelChanged(source: LafManager) {
    cachedSearchBackground = null
  }
}