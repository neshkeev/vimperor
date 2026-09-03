/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.group

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.highlight.HighlightGroup
import com.maddyhome.idea.vim.newapi.ij
import com.maddyhome.idea.vim.sign.PlacedSign
import com.maddyhome.idea.vim.sign.VimSignDisplay
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * `:sign`, over IntelliJ's markup model.
 *
 * One `RangeHighlighter` per sign, over the whole line, carrying two things: the `linehl` colour as
 * its attributes, and the `text` as a gutter icon. Replaced whole on each call, which is the model
 * the engine hands over and the one the markup model wants.
 *
 * The icon is drawn rather than loaded, because Vim's sign text is one or two characters and there
 * is no such image to load. [TextIcon] paints them in the editor's own font at the gutter's size,
 * so `>>` looks like `>>` and not like an approximation of it.
 *
 * `numhl` is not drawn. It colours the *line number*, and IntelliJ's line numbers come from the
 * gutter component rather than from the markup model, so an extension cannot colour one of them.
 * Carried rather than dropped: `:sign list` still reports it.
 */
internal class IjSignDisplay : VimSignDisplay {

  private val painted = mutableMapOf<Editor, List<RangeHighlighter>>()

  override fun showSigns(editor: VimEditor, signs: List<PlacedSign>) {
    val ij = editor.ij
    painted.remove(ij)?.forEach { ij.markupModel.removeHighlighter(it) }
    if (signs.isEmpty()) return

    val added = signs.mapNotNull { sign ->
      val line = sign.line - 1
      if (line < 0 || line >= ij.document.lineCount) return@mapNotNull null

      // Above the selection layer, unlike `:match`: a sign is a mark *about* the line rather than
      // a highlight of its text, and it should stay visible while the line is selected.
      ij.markupModel.addRangeHighlighter(
        ij.document.getLineStartOffset(line),
        ij.document.getLineEndOffset(line),
        HighlighterLayer.ADDITIONAL_SYNTAX + sign.priority,
        lineAttributes(sign.lineHighlight),
        HighlighterTargetArea.LINES_IN_RANGE,
      ).apply {
        sign.text?.let { gutterIconRenderer = SignGutterRenderer(it, colourOf(sign.textHighlight)) }
      }
    }
    painted[ij] = added
  }

  /**
   * The line's colour, or null to leave it alone.
   *
   * A group nobody defined resolves to null and stays null here, which is a different answer from
   * the one `:match` gives. A match must be visible or the command did nothing; a sign whose
   * `linehl` names an undefined group has simply not been told to colour the line, and inventing a
   * colour for it would paint over a file the moment a config mentioned a group this fork has never
   * heard of.
   */
  private fun lineAttributes(group: HighlightGroup?): TextAttributes? {
    val attributes = group?.attributes ?: return null
    if (attributes.paintsNothing) return null
    return TextAttributes(colourOf(group), colour(attributes.background), null, null, Font.PLAIN)
  }

  private fun colourOf(group: HighlightGroup?): Color? = colour(group?.attributes?.foreground)

  private fun colour(hex: String?): Color? = hex?.let { Color(it.removePrefix("#").toInt(16), false) }
}

/**
 * The sign's text, as the icon the gutter wants.
 *
 * `equals` and `hashCode` are the reason this is a class rather than a lambda: IntelliJ compares
 * gutter renderers to decide whether the gutter needs repainting, and two renderers that draw the
 * same thing have to compare equal or the gutter flickers on every markup change.
 */
private class SignGutterRenderer(private val text: String, private val colour: Color?) : GutterIconRenderer() {
  override fun getIcon(): Icon = TextIcon(text, colour)
  override fun getTooltipText(): String = text
  override fun equals(other: Any?): Boolean =
    other is SignGutterRenderer && other.text == text && other.colour == colour

  override fun hashCode(): Int = 31 * text.hashCode() + (colour?.hashCode() ?: 0)
}

/** One or two characters, drawn at the size the gutter gives an icon. */
private class TextIcon(private val text: String, private val colour: Color?) : Icon {
  override fun getIconWidth(): Int = WIDTH
  override fun getIconHeight(): Int = HEIGHT

  override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
    val g = graphics.create()
    try {
      (g as? java.awt.Graphics2D)?.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
      )
      g.color = colour ?: component?.foreground ?: Color.GRAY
      g.font = Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE)
      val metrics = g.fontMetrics
      // Centred rather than left-aligned, because a one-character sign next to a two-character one
      // reads as a column when they share a centre line and as a ragged edge when they do not.
      val left = x + (WIDTH - metrics.stringWidth(text)) / 2
      g.drawString(text, left, y + (HEIGHT + metrics.ascent - metrics.descent) / 2)
    } finally {
      g.dispose()
    }
  }

  private companion object {
    const val WIDTH = 16
    const val HEIGHT = 16
    const val FONT_SIZE = 12
  }
}
