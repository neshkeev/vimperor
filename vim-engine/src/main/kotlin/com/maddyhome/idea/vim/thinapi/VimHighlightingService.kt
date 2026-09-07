/*
 * Copyright 2003-2025 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.thinapi

import com.intellij.vim.api.models.Color
import com.intellij.vim.api.models.HighlightId
import com.maddyhome.idea.vim.api.VimEditor

interface VimHighlightingService {
  fun addHighlighter(
    editor: VimEditor,
    startOffset: Int,
    endOffset: Int,
    backgroundColor: Color?,
    foregroundColor: Color?,
  ): HighlightId

  fun removeHighlighter(editor: VimEditor, highlightId: HighlightId)

  /**
   * The same, but where a colour is `null` the host paints a search match in its own colours.
   *
   * `addHighlighter`'s `null` means "no colour", which is the right answer for the thin API - an
   * extension that names one colour and not the other means it. `highlightedyank` asks a different
   * question: it wants whatever *this editor* highlights a search result in, and no `Color` can
   * carry that. IntelliJ's is `TEXT_SEARCH_RESULT_ATTRIBUTES` from the current scheme; VS Code's is
   * the theme key `editor.findMatchHighlightBackground`, which is not a value at all but a name it
   * resolves when it paints - so neither can be reduced to a hex and handed back.
   *
   * The default here keeps a host that has no such notion honest: it means "no colour", exactly as
   * before.
   */
  fun addSearchHighlighter(
    editor: VimEditor,
    startOffset: Int,
    endOffset: Int,
    backgroundColor: Color?,
    foregroundColor: Color?,
  ): HighlightId = addHighlighter(editor, startOffset, endOffset, backgroundColor, foregroundColor)
}