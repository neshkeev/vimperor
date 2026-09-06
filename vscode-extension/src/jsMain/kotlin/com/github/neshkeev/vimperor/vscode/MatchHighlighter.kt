/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.VimEditor
import com.github.neshkeev.vimperor.api.VimMatchHighlighter
import com.maddyhome.idea.vim.common.TextRange
import com.github.neshkeev.vimperor.highlight.HighlightAttributes
import com.github.neshkeev.vimperor.highlight.HighlightGroup
import com.github.neshkeev.vimperor.highlight.UnderlineStyle

/**
 * `:match`, over VS Code's decorations.
 *
 * One decoration type per appearance, made on first use and kept: a decoration type is a resource
 * VS Code disposes with the extension, and making a new one for every repaint would leak one per
 * keystroke - `:match` repaints after each key, which is what makes it a *standing* highlight
 * rather than a snapshot.
 *
 * Per *appearance* rather than per group, because `:highlight` can change what a group looks like
 * while a `:match` on it is showing. The key carries the resolved attributes, so redefining the
 * group makes a new type and the next repaint paints the new colours. There are only ever as many
 * types as there were distinct definitions, which is a handful.
 *
 * Two colours to choose between, and the choice is the user's. A group `:highlight` defined is
 * painted in exactly those colours; a group nobody defined takes a *theme* colour from
 * [VsCodeThemeColors], because a literal that reads well in Dark+ is invisible in Light+ and the
 * name in a config carries no colour with it.
 */
internal class VsCodeMatchHighlighter : VimMatchHighlighter {

  private val styles = mutableMapOf<String, TextEditorDecorationType>()

  override fun showMatches(editor: VimEditor, channel: Int, group: HighlightGroup, ranges: List<TextRange>) {
    val vsCode = editor as? VsCodeEditor ?: return
    if (group.attributes?.paintsNothing == true) {
      clearMatches(editor, channel)
      return
    }

    val key = keyFor(channel, group)
    // Everything this channel used to look like has to be taken off first. Only the type that is
    // about to be painted gets its ranges replaced, so a definition that changed would otherwise
    // leave the old colours on screen with nothing left holding them.
    clearChannel(vsCode, channel, except = key)
    val style = styles.getOrPut(key) { decorationFor(group) }
    vsCode.nativeEditor.setDecorations(style, ranges.map { it.toRange(vsCode) }.toTypedArray())
  }

  override fun clearMatches(editor: VimEditor, channel: Int) {
    val vsCode = editor as? VsCodeEditor ?: return
    clearChannel(vsCode, channel, except = null)
  }

  private fun clearChannel(editor: VsCodeEditor, channel: Int, except: String?) {
    for ((key, style) in styles) {
      if (key.startsWith("$channel:") && key != except) editor.nativeEditor.setDecorations(style, emptyArray())
    }
  }

  /** The channel plus everything that affects how this looks, so a redefinition gets its own type. */
  private fun keyFor(channel: Int, group: HighlightGroup): String =
    "$channel:${group.name}:${group.attributes}"

  private fun decorationFor(group: HighlightGroup): TextEditorDecorationType {
    val options: dynamic = js("({})")
    val defined = group.attributes
    if (defined == null) {
      val colour = VsCodeThemeColors.VIM_HIGHLIGHT_GROUPS[group.name] ?: VsCodeThemeColors.FIND_MATCH_HIGHLIGHT
      options.backgroundColor = ThemeColor(colour)
    } else {
      defined.applyTo(options)
    }
    return window.createTextEditorDecorationType(options)
  }

  /**
   * A `:highlight` definition as VS Code's decoration options, which are CSS underneath.
   *
   * That is what makes Vim's four extra underlines free here - `undercurl` is `underline wavy` and
   * a browser has drawn that for twenty years - and it is also why `standout` is missing. Standout
   * is a terminal's inverse-video-ish attribute with no CSS or editor counterpart; a config that
   * asks for it gets whatever else it asked for, and nothing invented for that word.
   */
  private fun HighlightAttributes.applyTo(options: dynamic) {
    // Vim's `reverse` swaps the two colours. Both hosts do it by swapping the fields rather than at
    // draw time, so it works when the colours were named and does nothing when they were not.
    val swap = reverse && (foreground != null || background != null)
    val fore = if (swap) background else foreground
    val back = if (swap) foreground else background

    if (fore != null) options.color = fore
    if (back != null) options.backgroundColor = back
    if (bold) options.fontWeight = "bold"
    if (italic) options.fontStyle = "italic"

    val decorations = mutableListOf<String>()
    when (underline) {
      UnderlineStyle.NONE -> {}
      UnderlineStyle.STRAIGHT -> decorations.add("underline")
      UnderlineStyle.CURL -> decorations.add("underline wavy")
      UnderlineStyle.DOUBLE -> decorations.add("underline double")
      UnderlineStyle.DOTTED -> decorations.add("underline dotted")
      UnderlineStyle.DASHED -> decorations.add("underline dashed")
    }
    // `guisp` colours the underline and not the text, which is exactly what CSS puts last.
    val underlineColour = special
    if (decorations.isNotEmpty() && underlineColour != null) decorations.add(underlineColour)
    if (strikethrough) decorations.add("line-through")
    if (decorations.isNotEmpty()) options.textDecoration = decorations.joinToString(" ")
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
