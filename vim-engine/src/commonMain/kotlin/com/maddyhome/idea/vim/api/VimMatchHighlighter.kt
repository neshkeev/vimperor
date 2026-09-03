/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

import com.maddyhome.idea.vim.common.TextRange

/**
 * Painting `:match`, which is the one kind of highlighting Vim lets a *user* ask for by pattern.
 *
 * Three channels because Vim has three - `:match`, `:2match` and `:3match` - and they are separate
 * so that a plugin can use one without stepping on the user's. A host keeps them apart the way it
 * keeps `'hlsearch'` apart from a selection: one set of decorations per channel, replaced whole.
 *
 * The group name is Vim's, and this fork has no `:highlight` command to define one - so what a host
 * does with `Search` or `ErrorMsg` is map it to the nearest colour it already has, and fall back to
 * its find-match colour for a name it does not know. That is a real mapping rather than an
 * invention: every name in [KNOWN_GROUPS] has an obvious counterpart in both editors, and a colour
 * taken from the theme is readable in the theme, which a literal never is.
 */
interface VimMatchHighlighter {

  /** Paints [ranges] on [channel] (1, 2 or 3) in the colours of Vim's highlight [group]. */
  fun showMatches(editor: VimEditor, channel: Int, group: String, ranges: List<TextRange>)

  /** Takes everything off [channel]. `:match none` and `:match` with nothing else. */
  fun clearMatches(editor: VimEditor, channel: Int)

  companion object {
    /**
     * The Vim highlight groups worth mapping, which is the handful people actually name.
     *
     * Not a complete list of Vim's - it is a list of the ones a `:match` in a config reaches for,
     * and everything else falls back rather than failing.
     */
    val KNOWN_GROUPS: Set<String> = setOf(
      "Search", "IncSearch", "ErrorMsg", "WarningMsg", "Todo", "Underlined", "Visual", "MatchParen",
    )
  }
}

/**
 * A host that does not paint, which is the honest default rather than a stub.
 *
 * `:match` sets its pattern either way - the state is the engine's - so a host without this still
 * answers `:match` without an error and simply shows nothing. That is worse than painting and much
 * better than an exception from a config line.
 */
object NoMatchHighlighting : VimMatchHighlighter {
  override fun showMatches(editor: VimEditor, channel: Int, group: String, ranges: List<TextRange>) {}
  override fun clearMatches(editor: VimEditor, channel: Int) {}
}
