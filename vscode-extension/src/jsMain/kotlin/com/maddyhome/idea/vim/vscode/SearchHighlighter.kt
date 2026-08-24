/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.common.TextRange

/**
 * Paints search matches, `'hlsearch'` and the `:s///c` confirmation.
 *
 * VS Code's decorations replace rather than accumulate: setting a type's ranges discards whatever
 * that type painted before, and there is no way to remove a single one. So clearing a highlight is
 * setting its ranges to none, and each kind of highlight needs its own type - matches, the current
 * match, and the range `:s///c` is asking about all have to be paintable and clearable apart.
 *
 * Separated from the injector so a test can read what would be painted without a VS Code window.
 */
interface Highlighter {
  /** Paints [ranges] as ordinary search matches, replacing the previous set. */
  fun showMatches(editor: VsCodeEditor, ranges: List<TextRange>)

  /** Paints the one match the caret is on, which Vim colours differently from the rest. */
  fun showCurrentMatch(editor: VsCodeEditor, range: TextRange?)

  /** Paints the range `:s///c` is asking about, and returns the handle that unpaints it. */
  fun showConfirmation(editor: VsCodeEditor, range: TextRange): () -> Unit

  fun clear(editor: VsCodeEditor)

  /** Whether anything is currently painted, which `:nohlsearch` and the engine both ask. */
  fun isShowingAnything(): Boolean

  /** For a host with nothing to paint on. */
  object None : Highlighter {
    override fun showMatches(editor: VsCodeEditor, ranges: List<TextRange>) {}
    override fun showCurrentMatch(editor: VsCodeEditor, range: TextRange?) {}
    override fun showConfirmation(editor: VsCodeEditor, range: TextRange): () -> Unit = {}
    override fun clear(editor: VsCodeEditor) {}
    override fun isShowingAnything(): Boolean = false
  }
}

/**
 * The real one, over VS Code's decorations.
 *
 * The colours are theme colours rather than literals, so a match looks the way the editor's own
 * find looks in whatever theme the user has. A hardcoded yellow is unreadable in half of them.
 */
class DecorationHighlighter : Highlighter {

  private val matchStyle = decorationColoured("editor.findMatchHighlightBackground")
  private val currentMatchStyle = decorationColoured("editor.findMatchBackground")
  private val confirmationStyle = decorationColoured("editor.selectionHighlightBackground")

  private var painted = 0

  override fun showMatches(editor: VsCodeEditor, ranges: List<TextRange>) {
    painted = ranges.size
    editor.nativeEditor.setDecorations(matchStyle, ranges.map { it.toVsCodeRange(editor) }.toTypedArray())
  }

  override fun showCurrentMatch(editor: VsCodeEditor, range: TextRange?) {
    val ranges = range?.let { arrayOf(it.toVsCodeRange(editor)) } ?: emptyArray()
    editor.nativeEditor.setDecorations(currentMatchStyle, ranges)
  }

  override fun showConfirmation(editor: VsCodeEditor, range: TextRange): () -> Unit {
    editor.nativeEditor.setDecorations(confirmationStyle, arrayOf(range.toVsCodeRange(editor)))
    return { editor.nativeEditor.setDecorations(confirmationStyle, emptyArray()) }
  }

  override fun clear(editor: VsCodeEditor) {
    painted = 0
    editor.nativeEditor.setDecorations(matchStyle, emptyArray())
    editor.nativeEditor.setDecorations(currentMatchStyle, emptyArray())
  }

  override fun isShowingAnything(): Boolean = painted > 0

  /**
   * A decoration painted in one of the editor's own find colours.
   *
   * The options are a plain JavaScript object because that is what VS Code takes - there is no
   * class to construct - but the colour goes through the declared [ThemeColor] binding rather than
   * a `require` in a string, so the shape is checked by the compiler and not by a runtime failure.
   */
  private fun decorationColoured(themeColorId: String): TextEditorDecorationType {
    val options: dynamic = js("({})")
    options.backgroundColor = ThemeColor(themeColorId)
    return window.createTextEditorDecorationType(options)
  }
}

/**
 * Offsets to a VS Code range, through the *buffer* rather than the document.
 *
 * Highlighting runs while a command is in flight - `:s` paints its confirmation before the edit
 * lands - so the document may still hold the old text, and `positionAt` would answer about that.
 */
private fun TextRange.toVsCodeRange(editor: VsCodeEditor): Range {
  val start = editor.offsetToBufferPosition(startOffset)
  val end = editor.offsetToBufferPosition(endOffset)
  return Range(Position(start.line, start.column), Position(end.line, end.column))
}
