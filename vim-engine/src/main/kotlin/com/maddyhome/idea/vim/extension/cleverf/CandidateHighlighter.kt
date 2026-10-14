/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.cleverf

import com.github.neshkeev.vimperor.highlight.Highlights
import com.intellij.vim.api.models.Color
import com.intellij.vim.api.models.HighlightId
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector

/**
 * Highlights the characters the current search can jump to, like clever-f's `g:clever_f_mark_char`.
 * Only one set of candidates is shown at a time.
 *
 * ## Why these are not red
 *
 * The plugin's own group is `CleverFDefaultLabel`, which is red, bold and underlined, and IdeaVim paints exactly
 * that. `easymotion` started there too and had to move: a *text* colour is only as legible as whatever it lands on,
 * and `#ff0000` on VS Code's default dark background is hard to see at all. So the user's `:highlight` wins when
 * there is one, and otherwise this asks the host for the colours it draws a search match in - which are the theme's,
 * and therefore readable in the theme.
 */
internal class CandidateHighlighter {
  private var editor: VimEditor? = null
  private val highlighters = mutableListOf<HighlightId>()

  fun show(editor: VimEditor, offsets: List<Int>) {
    clear()
    if (offsets.isEmpty()) return
    this.editor = editor
    // Resolved on every show, so a `:highlight CleverFDefaultLabel` written between two searches is seen by the
    // second one. The same rule `easymotion` follows for its labels.
    val defined = Highlights.group(LABEL_GROUP).attributes
    val background = defined?.background?.let(::Color)
    val foreground = defined?.foreground?.let(::Color)
    offsets.mapTo(highlighters) {
      injector.highlightingService.addSearchHighlighter(editor, it, it + 1, background, foreground)
    }
  }

  fun clear() {
    val editor = this.editor
    if (editor != null) highlighters.forEach { injector.highlightingService.removeHighlighter(editor, it) }
    highlighters.clear()
    this.editor = null
  }

  private companion object {
    /** clever-f's own highlight group, so a config that styles the plugin styles this too. */
    const val LABEL_GROUP = "CleverFDefaultLabel"
  }
}
