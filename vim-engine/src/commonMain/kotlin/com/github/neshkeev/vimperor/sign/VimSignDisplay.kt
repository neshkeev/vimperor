/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.sign
import com.maddyhome.idea.vim.api.VimEditor
import com.github.neshkeev.vimperor.highlight.HighlightGroup

/**
 * One sign, ready to be drawn: where it is and what it looks like, with nothing left to look up.
 *
 * The three highlight groups arrive resolved, so a host paints the user's colours when `:highlight`
 * defined them and its own theme's when nobody did - the same bargain `:match` makes, for the same
 * reason. Null means the definition did not ask for that one at all.
 */
data class PlacedSign(
  val id: Int,
  val group: String,
  /** Vim's line number, counting from one. */
  val line: Int,
  /** One or two characters for the gutter, or null for a sign that only colours its line. */
  val text: String?,
  val textHighlight: HighlightGroup?,
  val lineHighlight: HighlightGroup?,
  val numberHighlight: HighlightGroup?,
  val priority: Int,
)

/**
 * Drawing `:sign` - a mark in the gutter and a colour on the line.
 *
 * The whole list for one file arrives each time and replaces what was there, which is the same
 * model `:match` uses and the one both hosts' decoration APIs already have. A host that draws only
 * part of this is not failing: Vim's `numhl` colours the line *number*, and an editor that has no
 * way to colour a line number separately from its gutter simply does not.
 */
interface VimSignDisplay {

  /** Replaces every sign shown in [editor] with [signs]. An empty list clears them. */
  fun showSigns(editor: VimEditor, signs: List<PlacedSign>)
}

/**
 * A host that draws no signs, which is the honest default rather than a stub.
 *
 * `:sign` still defines and places - that state is the engine's, and `:sign place` still lists what
 * is there - so a host without this answers every `:sign` command without an error and shows
 * nothing in the gutter.
 */
object NoSignDisplay : VimSignDisplay {
  override fun showSigns(editor: VimEditor, signs: List<PlacedSign>) {}
}
