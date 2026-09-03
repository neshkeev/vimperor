/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimMatchHighlighter
import com.maddyhome.idea.vim.common.TextRange

/**
 * `:match`, over VS Code's decorations.
 *
 * One decoration type per Vim highlight group, made on first use and kept: a decoration type is a
 * resource VS Code disposes with the extension, and making a new one for every repaint would leak
 * one per keystroke - `:match` repaints after each key, which is what makes it a *standing*
 * highlight rather than a snapshot.
 *
 * The three channels are kept apart by painting each in its own decoration type even when two of
 * them name the same group, because `setDecorations` replaces every range of a type at once:
 * sharing one type between `:match` and `:2match` would make each repaint erase the other.
 */
internal class VsCodeMatchHighlighter : VimMatchHighlighter {

  private val styles = mutableMapOf<String, TextEditorDecorationType>()

  override fun showMatches(editor: VimEditor, channel: Int, group: String, ranges: List<TextRange>) {
    val vsCode = editor as? VsCodeEditor ?: return
    val style = styleFor(channel, group)
    vsCode.nativeEditor.setDecorations(style, ranges.map { it.toRange(vsCode) }.toTypedArray())
  }

  override fun clearMatches(editor: VimEditor, channel: Int) {
    val vsCode = editor as? VsCodeEditor ?: return
    // Every style this channel has ever used, because the group can change between calls and the
    // ranges of the old one would otherwise stay on screen with nothing left to clear them.
    for ((key, style) in styles) {
      if (key.startsWith("$channel:")) vsCode.nativeEditor.setDecorations(style, emptyArray())
    }
  }

  private fun styleFor(channel: Int, group: String): TextEditorDecorationType =
    styles.getOrPut("$channel:$group") {
      val colour = VsCodeThemeColors.VIM_HIGHLIGHT_GROUPS[group] ?: VsCodeThemeColors.FIND_MATCH_HIGHLIGHT
      val options: dynamic = js("({})")
      options.backgroundColor = ThemeColor(colour)
      window.createTextEditorDecorationType(options)
    }

  /**
   * Offsets to a VS Code range, through the buffer rather than the document.
   *
   * The same reason the search highlighter does it: this runs while a command may still be in
   * flight, so the document can hold the old text and `positionAt` would answer about that.
   */
  private fun TextRange.toRange(editor: VsCodeEditor): Range {
    val start = editor.offsetToBufferPosition(startOffset)
    val end = editor.offsetToBufferPosition(endOffset)
    return Range(Position(start.line, start.column), Position(end.line, end.column))
  }
}
