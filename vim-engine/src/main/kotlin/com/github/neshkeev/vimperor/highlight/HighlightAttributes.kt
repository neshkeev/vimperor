/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.highlight
/**
 * What `:highlight` decided a group looks like, in terms a host can paint without knowing Vim.
 *
 * Vim keeps three sets of attributes per group - `term`, `cterm` and `gui` - because one syntax
 * file has to serve a vt100, a colour xterm and a GUI. Neither of this fork's hosts is any of
 * those: both draw into a themed editor with real colours. So the three are collapsed here, once,
 * on the way out, and every colour has already become `#rrggbb` - a host never sees the word
 * `DarkCyan` or the number `81`, and never has to decide what they mean.
 *
 * [foreground], [background] and [special] are null for "leave the editor's own alone", which is
 * both what Vim's `NONE` means and what Vim's `fg` and `bg` mean here: those two name the `Normal`
 * group's colours, and in a host whose `Normal` is the theme's the honest answer is to not paint.
 */
data class HighlightAttributes(
  val foreground: String? = null,
  val background: String? = null,
  /** `guisp`/`ctermul` - the colour of the underline, which Vim keeps apart from the text's. */
  val special: String? = null,
  val bold: Boolean = false,
  val italic: Boolean = false,
  val standout: Boolean = false,
  val reverse: Boolean = false,
  val strikethrough: Boolean = false,
  val underline: UnderlineStyle = UnderlineStyle.NONE,
) {

  /**
   * True when this group paints nothing, which is a real state and not an absent one.
   *
   * `:highlight Search NONE` and `:highlight clear Search` both arrive here: Vim's word for it is
   * "disable the highlighting for one highlight group", and it is explicitly *not* the same as
   * never having mentioned the group. A host that has its own idea of what `Search` looks like
   * must use it for the second and not for the first.
   */
  val paintsNothing: Boolean get() = this == NONE

  companion object {
    val NONE = HighlightAttributes()
  }
}

/**
 * Vim's six underlines, which are six because terminals grew four more after the first two.
 *
 * A host that has fewer draws the nearest it has; Vim says the same about itself, and falls back
 * to a plain underline when the terminal cannot do the rest.
 */
enum class UnderlineStyle {
  NONE,
  STRAIGHT,
  CURL,
  DOUBLE,
  DOTTED,
  DASHED,
}

/**
 * A highlight group on its way to a host: the name that was asked for, and what it resolved to.
 *
 * [attributes] is null when `:highlight` has never been told anything about this name - which is
 * the usual case, since `Search` and `ErrorMsg` are Vim's own groups and a config that never
 * defines them still names them. Null means "you decide", and both hosts answer it with the
 * nearest colour in the loaded theme. Non-null means the user said what they wanted, including
 * when what they wanted was nothing.
 */
data class HighlightGroup(val name: String, val attributes: HighlightAttributes?)
