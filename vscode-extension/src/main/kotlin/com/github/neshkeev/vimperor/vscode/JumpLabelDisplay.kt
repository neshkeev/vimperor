/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.github.neshkeev.vimperor.highlight.HighlightGroup
import com.github.neshkeev.vimperor.label.JumpLabel
import com.github.neshkeev.vimperor.label.VimJumpLabelDisplay
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.common.TextRange

/**
 * Jump labels over VS Code's decorations: letters where letters were.
 *
 * VS Code gives an extension no way to draw a character *over* another one. What it has is a
 * decoration's `before` attachment, which is inserted text - laid out in the line, so a label pushes
 * the rest of the line along by its own width, and every later label on that line lands beside its
 * target instead of on it.
 *
 * So this does what VSCodeVim's easymotion does (MIT): it takes the attachment out of the layout with
 * CSS that VS Code does not offer. The `margin` is copied into a style string as it is, so a margin
 * of `0 -1ch 0 0; position: absolute` carries a second declaration in after it. The character
 * underneath is painted transparent by another decoration, and the label reads as its replacement.
 *
 * **That relies on undocumented behaviour, and only a real window can check it.** The stub these
 * tests run in records options and draws nothing, so it cannot tell a label over its character from
 * one beside it. If VS Code starts escaping the margin, labels still appear and the text shifts
 * sideways: wrong, but legible and obvious.
 *
 * ## Colours
 *
 * Each label arrives with its `:highlight` group resolved. A group the user defined is painted
 * exactly as `:match` would paint it; one they never mentioned is a badge in a theme colour pair from
 * [VsCodeThemeColors.JUMP_LABEL_GROUPS]. The first version drew vim-easymotion's red text and nothing
 * else, and on VS Code's dark background it was hardly visible - a text colour is only as legible as
 * whatever it lands on, and a badge brings its own background.
 *
 * Label decorations carry their colours per range, because a `before` attachment can. The shading
 * cannot: a range's own options reach only its attachments, so a text colour has to be on the
 * decoration *type*, and there is one shade type per appearance, as `:match` keeps.
 */
internal class VsCodeJumpLabelDisplay : VimJumpLabelDisplay {

  /** The characters a label covers, painted out so the label reads in their place. */
  private val coveredType: TextEditorDecorationType by lazy {
    decorationType { it.color = "transparent" }
  }

  /** The labels; empty, because each range brings its own text and colours. */
  private val labelType: TextEditorDecorationType by lazy { decorationType {} }

  /** Shading, one type per appearance of `EasyMotionShade`. See the class comment. */
  private val shadeTypes = mutableMapOf<String, TextEditorDecorationType>()

  override fun showLabels(editor: VimEditor, labels: List<JumpLabel>, shaded: List<TextRange>, shade: HighlightGroup) {
    val vsCode = editor as? VsCodeEditor ?: return
    val text = editor.text()

    val covered = mutableListOf<Range>()
    val drawn = mutableListOf<Any>()
    for (label in labels) {
      val shown = label.keys.take(2)
      // A label never reaches onto the next line. On an empty line, or at the very end, there is
      // nothing to cover and the label is simply drawn there.
      val coverable = (0 until shown.length).takeWhile { index ->
        val at = label.offset + index
        at < text.length && text[at] != '\n'
      }.count()
      if (coverable > 0) covered += rangeOf(vsCode, label.offset, label.offset + coverable)
      drawn += labelDecoration(rangeOf(vsCode, label.offset, label.offset), shown, label.highlight)
    }

    val native = vsCode.nativeEditor
    paintShade(native, shade, shaded.map { rangeOf(vsCode, it.startOffset, it.endOffset) })
    native.setDecorations(coveredType, covered.toTypedArray())
    native.setDecorations(labelType, drawn.toTypedArray())
  }

  override fun clearLabels(editor: VimEditor) {
    val native = (editor as? VsCodeEditor)?.nativeEditor ?: return
    for (type in shadeTypes.values) native.setDecorations(type, emptyArray<Range>())
    native.setDecorations(coveredType, emptyArray<Range>())
    native.setDecorations(labelType, emptyArray<Range>())
  }

  /** What VS Code says is on screen, which - unlike the engine's top and bottom - leaves folds out. */
  override fun visibleLines(editor: VimEditor): List<IntRange> {
    val native = (editor as? VsCodeEditor)?.nativeEditor ?: return super.visibleLines(editor)
    val last = editor.lineCount() - 1
    val ranges = native.visibleRanges
    if (ranges.isEmpty() || last < 0) return super.visibleLines(editor)
    return ranges.map { it.start.line.coerceIn(0, last)..it.end.line.coerceIn(0, last) }
  }

  /**
   * The searched text, dimmed, in whichever type paints [shade] - and every other shade type emptied,
   * so a `:highlight EasyMotionShade` changed between two jumps does not leave the old dimming behind.
   *
   * `:highlight EasyMotionShade NONE` paints nothing, which is Vim's meaning for a disabled group.
   */
  private fun paintShade(native: TextEditor, shade: HighlightGroup, ranges: List<Range>) {
    val attributes = shade.attributes
    val key = attributes?.toString() ?: THEME
    val type = if (attributes?.paintsNothing == true) {
      null
    } else {
      shadeTypes.getOrPut(key) {
        decorationType { options ->
          if (attributes == null) options.color = ThemeColor(VsCodeThemeColors.LINE_NUMBER) else attributes.applyTo(options)
        }
      }
    }
    for ((each, shadeType) in shadeTypes) {
      native.setDecorations(shadeType, if (shadeType === type && each == key) ranges.toTypedArray() else emptyArray())
    }
  }

  private fun labelDecoration(range: Range, keys: String, highlight: HighlightGroup): Any {
    val before: dynamic = js("({})")
    before.contentText = keys
    val attributes = highlight.attributes
    if (attributes == null) {
      val (background, foreground) = VsCodeThemeColors.JUMP_LABEL_GROUPS[highlight.name]
        ?: (VsCodeThemeColors.BADGE_BACKGROUND to VsCodeThemeColors.BADGE_FOREGROUND)
      before.backgroundColor = ThemeColor(background)
      before.color = ThemeColor(foreground)
      before.fontWeight = "bold"
    } else {
      // Exactly as the user defined it, including `NONE` - which leaves the label in the editor's
      // own text colour, still readable, since the character it covers is painted out.
      attributes.applyTo(before)
    }
    // The trick described in the class comment. A negative margin as well as `absolute`, as VSCodeVim
    // has it, so a renderer that honoured the margin and not the position would still leave the text
    // where it was.
    before.margin = "0 -${keys.length}ch 0 0; position: absolute"
    before.height = "100%"

    val renderOptions: dynamic = js("({})")
    renderOptions.before = before
    val decoration: dynamic = js("({})")
    decoration.range = range
    decoration.renderOptions = renderOptions
    return decoration.unsafeCast<Any>()
  }

  private fun decorationType(configure: (dynamic) -> Unit): TextEditorDecorationType {
    val options: dynamic = js("({})")
    configure(options)
    return window.createTextEditorDecorationType(options)
  }

  /** Offsets to a VS Code range, through the buffer, for the reason the highlighters give. */
  private fun rangeOf(editor: VsCodeEditor, startOffset: Int, endOffset: Int): Range {
    val start = editor.offsetToBufferPosition(startOffset)
    val end = editor.offsetToBufferPosition(endOffset)
    return Range(Position(start.line, start.column), Position(end.line, end.column))
  }

  private companion object {
    /** The key for a shade nobody defined, which takes the theme's colour. */
    const val THEME = "theme"
  }
}
