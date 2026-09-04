/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.highlight.HighlightGroup
import com.maddyhome.idea.vim.sign.PlacedSign
import com.maddyhome.idea.vim.sign.VimSignDisplay

/**
 * `:sign`, over VS Code's decorations.
 *
 * Two halves, drawn by two different mechanisms, because VS Code has no single "put this in the
 * gutter" API for an extension:
 *
 *  - `linehl` is a whole-line decoration, which is exactly what `isWholeLine` means and is the
 *    attribute Vim's own documentation calls the most useful one.
 *  - `text` is a gutter *icon*, because `gutterIconPath` is the only way into that column. The two
 *    characters are drawn into an SVG and handed over as a `data:` URI, so a sign defined as `>>`
 *    arrives as a picture of `>>`. It is not a font the editor chose, which is the honest cost of
 *    the column being closed.
 *
 * `numhl` is not drawn. It colours the *line number*, and VS Code gives an extension no way to
 * colour one line's number differently from the rest - `editorLineNumber.foreground` is a theme
 * colour for the whole gutter. Recorded rather than silently dropped: `:sign list` still shows it.
 *
 * A decoration type per appearance rather than per sign, keyed the way the `:match` painter keys
 * its own, so redefining a sign makes a new type and the old one is emptied rather than left on
 * screen. VS Code disposes these with the extension, and a new one per repaint would leak.
 */
internal class VsCodeSignDisplay : VimSignDisplay {

  private val styles = mutableMapOf<String, TextEditorDecorationType>()

  override fun showSigns(editor: VimEditor, signs: List<PlacedSign>) {
    val vsCode = editor as? VsCodeEditor ?: return

    // Higher priority wins the gutter when two signs land on one line, which is what Vim's
    // sign-priority decides. Sorting ascending and letting the later one overwrite is the same
    // answer as picking the maximum, and it keeps the line highlight of both.
    val byStyle = mutableMapOf<String, MutableList<Range>>()
    for (sign in signs.sortedBy { it.priority }) {
      val key = keyFor(sign)
      styles.getOrPut(key) { decorationFor(sign) }
      byStyle.getOrPut(key) { mutableListOf() }.add(lineRange(vsCode, sign.line))
    }

    for ((key, style) in styles) {
      vsCode.nativeEditor.setDecorations(style, (byStyle[key] ?: emptyList()).toTypedArray())
    }
  }

  /** Everything that affects how this sign looks, so a redefinition gets a type of its own. */
  private fun keyFor(sign: PlacedSign): String =
    "${sign.text}|${sign.textHighlight}|${sign.lineHighlight}"

  private fun decorationFor(sign: PlacedSign): TextEditorDecorationType {
    val options: dynamic = js("({})")
    options.isWholeLine = true
    colourOf(sign.lineHighlight)?.let { options.backgroundColor = it }
    sign.text?.let { options.gutterIconPath = UriFactory.parse(gutterIcon(it, colourOf(sign.textHighlight))) }
    return window.createTextEditorDecorationType(options)
  }

  /**
   * The colour to paint with, or null to leave it alone.
   *
   * A group `:highlight` defined resolves to a real background; one nobody defined resolves to
   * null, and the theme mapping the `:match` painter uses is right for a *match* and wrong here -
   * a sign whose group was never defined should not silently arrive in the find colour. So an
   * undefined group means "no colour", and the sign still shows its text.
   */
  private fun colourOf(group: HighlightGroup?): String? {
    val attributes = group?.attributes ?: return null
    return attributes.background ?: attributes.foreground
  }

  /**
   * Two characters as a picture, because `gutterIconPath` wants an image and nothing else.
   *
   * A `utf8` data URI rather than base64: `encodeURIComponent` is in every JavaScript runtime and
   * `btoa` is not in all of them, and the SVG is short enough that the encoding costs nothing.
   */
  private fun gutterIcon(text: String, colour: String?): String {
    val fill = colour ?: "currentColor"
    val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16">""" +
      """<text x="0" y="12" font-family="monospace" font-size="12" fill="$fill">""" +
      escape(text) +
      "</text></svg>"
    return "data:image/svg+xml;utf8," + encodeURIComponent(svg)
  }

  /**
   * The sign text is the user's, and `<` in an SVG is markup rather than a character.
   *
   * `&` and `<` only, which is XML's own minimal set for text content. A `>` needs no escaping
   * there, and escaping it anyway would turn Vim's own example sign - `>>` - into six characters
   * of entity for nothing.
   */
  private fun escape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")

  private fun lineRange(editor: VsCodeEditor, line: Int): Range {
    // Vim counts lines from one and VS Code from zero, and a sign on a line past the end of a file
    // that has since been shortened would otherwise throw rather than simply not showing.
    val zeroBased = (line - 1).coerceIn(0, (editor.nativeEditor.document.lineCount - 1).coerceAtLeast(0))
    return Range(Position(zeroBased, 0), Position(zeroBased, 0))
  }
}

private external fun encodeURIComponent(value: String): String
