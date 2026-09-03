/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.highlight.HighlightGroup

/**
 * Painting `:match`, which is the one kind of highlighting Vim lets a *user* ask for by pattern.
 *
 * Three channels because Vim has three - `:match`, `:2match` and `:3match` - and they are separate
 * so that a plugin can use one without stepping on the user's. A host keeps them apart the way it
 * keeps `'hlsearch'` apart from a selection: one set of decorations per channel, replaced whole.
 *
 * What arrives is a resolved [HighlightGroup] rather than a bare name, and the two halves of it are
 * answered differently. When `:highlight` has defined the group, the colours are the user's and a
 * host paints exactly them. When it has not - which is the usual case, since a config that writes
 * `:match Search /x/` has rarely also written `:highlight Search` - the attributes are null and the
 * host maps the name to the nearest thing in its own theme, per [KNOWN_GROUPS]. A colour taken from
 * the theme is readable in the theme, which a literal never is, so the fallback is the good answer
 * and not a placeholder.
 */
interface VimMatchHighlighter {

  /** Paints [ranges] on [channel] (1, 2 or 3) in the colours of Vim's highlight [group]. */
  fun showMatches(editor: VimEditor, channel: Int, group: HighlightGroup, ranges: List<TextRange>)

  /** Takes everything off [channel]. `:match none` and `:match` with nothing else. */
  fun clearMatches(editor: VimEditor, channel: Int)

  companion object {
    /**
     * The Vim highlight groups worth mapping, which is the handful people actually name.
     *
     * Not a complete list of Vim's - it is a list of the ones a `:match` in a config reaches for,
     * and everything else falls back rather than failing. A group `:highlight` has defined never
     * reaches this list at all: the definition wins whether or not the name is here.
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
  override fun showMatches(editor: VimEditor, channel: Int, group: HighlightGroup, ranges: List<TextRange>) {}
  override fun clearMatches(editor: VimEditor, channel: Int) {}
}
