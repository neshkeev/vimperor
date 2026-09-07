/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.intellij.vim.api.models.Color
import com.intellij.vim.api.models.HighlightId
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.thinapi.VimHighlightingService

/**
 * A coloured range an extension asked for, and the last of the thin API's services this host had
 * not built.
 *
 * `Transaction.addHighlight` is the only caller in the engine, and it is what an extension reaches
 * for to say "this is the text I just acted on" - `highlightedyank`'s flash, `vim-exchange`'s mark
 * on the region waiting to be swapped. IntelliJ answers it with a `RangeHighlighter`; here it is a
 * decoration, which is the same idea with CSS underneath.
 *
 * ## One decoration type per highlight, unlike `:match`
 *
 * `VsCodeMatchHighlighter` keeps *one type per appearance* and reuses it, because `:match` repaints
 * a standing highlight after every keystroke and a type per repaint would leak one per key. This is
 * the opposite shape: a highlight here is created once, lives until someone removes it, and
 * `setDecorations` replaces every range a type holds - so two highlights sharing a type could not
 * be removed independently. A type each, disposed on removal, is what makes the ids mean anything.
 *
 * The contract is therefore the caller's to keep: a highlight nobody removes holds a decoration
 * type until the extension is unloaded. That is IntelliJ's contract too, where an unreleased
 * `RangeHighlighter` outlives the same way.
 */
internal class VsCodeHighlightingService : VimHighlightingService {

  /** The id an extension holds, which is the decoration type it names, and nothing else. */
  private class DecorationId(val type: TextEditorDecorationType) : HighlightId

  override fun addHighlighter(
    editor: VimEditor,
    startOffset: Int,
    endOffset: Int,
    backgroundColor: Color?,
    foregroundColor: Color?,
  ): HighlightId {
    // `#RRGGBBAA` is CSS's own eight-digit hex, so a colour with alpha needs no conversion - which
    // is the form `highlightedyank` writes, and the reason `Color` carries the hex rather than
    // four numbers.
    return add(editor, startOffset, endOffset, foregroundColor) { options ->
      backgroundColor?.let { options.backgroundColor = it.hexCode }
    }
  }

  private fun add(
    editor: VimEditor,
    startOffset: Int,
    endOffset: Int,
    foregroundColor: Color?,
    background: (dynamic) -> Unit,
  ): HighlightId {
    val vsCode = editor as VsCodeEditor
    val options: dynamic = js("({})")
    background(options)
    foregroundColor?.let { options.color = it.hexCode }

    val type = window.createTextEditorDecorationType(options)
    vsCode.nativeEditor.setDecorations(type, arrayOf(rangeOf(vsCode, startOffset, endOffset)))
    return DecorationId(type)
  }

  /**
   * The theme's own find-match colour where none was named.
   *
   * A `ThemeColor` rather than a hex, which is why this cannot be answered by handing the caller a
   * `Color`: `editor.findMatchHighlightBackground` is a name VS Code resolves when it paints, so it
   * follows the user's theme and a literal would not. `VsCodeMatchHighlighter` falls back the same
   * way for a `:highlight` group nobody defined, and for the same reason.
   */
  override fun addSearchHighlighter(
    editor: VimEditor,
    startOffset: Int,
    endOffset: Int,
    backgroundColor: Color?,
    foregroundColor: Color?,
  ): HighlightId = add(editor, startOffset, endOffset, foregroundColor) { options ->
    if (backgroundColor != null) {
      options.backgroundColor = backgroundColor.hexCode
    } else {
      options.backgroundColor = ThemeColor(VsCodeThemeColors.FIND_MATCH_HIGHLIGHT)
    }
  }

  override fun removeHighlighter(editor: VimEditor, highlightId: HighlightId) {
    val id = highlightId as? DecorationId ?: return
    val vsCode = editor as? VsCodeEditor
    // Clearing before disposing rather than relying on the dispose: VS Code repaints on dispose,
    // but only for editors it still knows about, and an editor that has since been closed would
    // otherwise be asked about a type that is already gone.
    vsCode?.nativeEditor?.setDecorations(id.type, emptyArray())
    id.type.dispose()
  }

  /**
   * Offsets to a VS Code range, through the buffer rather than the document.
   *
   * The same reason the match and search highlighters do it: this can run while an edit is still in
   * flight, and the document would answer about the text before it.
   */
  private fun rangeOf(editor: VsCodeEditor, startOffset: Int, endOffset: Int): Range {
    val start = editor.offsetToBufferPosition(startOffset)
    val end = editor.offsetToBufferPosition(endOffset)
    return Range(Position(start.line, start.column), Position(end.line, end.column))
  }
}
