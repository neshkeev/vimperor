/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.VimIndentConfig
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.options.NumberOption
import com.maddyhome.idea.vim.options.OptionAccessScope

/**
 * How wide an indent is and what it is made of.
 *
 * ## Why this reads options and not the editor
 *
 * It used to read `editor.options` outright, on the reasoning that IdeaVim asks IntelliJ's code
 * style rather than reading `'expandtab'`, so the engine has no such options and a host must answer
 * for itself. Half of that is right: deferring to VS Code is what makes `>>` in this port agree with
 * pressing Tab in the same file without Vim, and it must go on being true for a user who has set
 * nothing.
 *
 * The other half was a bug of exactly the kind `'wrap'` was. `'expandtab'`, `'tabstop'` and
 * `'shiftwidth'` are in almost every `~/.vimrc` ever written, this host *declared* all three so that
 * such a config would load, and then nothing read them - so `set shiftwidth=2` shifted by four and
 * said nothing about it.
 *
 * Both halves hold if the options are *seeded from the editor* when the user has not set them, which
 * is what `seedIndent` does. Then reading the option here is reading VS Code's answer in the
 * ordinary case, and reading the user's when they gave one.
 *
 * ## Three numbers, not one
 *
 * The old comment said `'tabstop'` and `'shiftwidth'` were "the same thing, which is what VS Code
 * has one number for". That was never true of Vim and is no longer true of VS Code either:
 * `'tabstop'` is what a tab character *draws as*, `'shiftwidth'` is what `>>` *moves by*, and
 * `TextEditorOptions` has had `indentSize` beside `tabSize` since 1.85. Conflating them made
 * `>>` in a file of 8-column tabs shift by eight.
 *
 * Vim's own fallbacks are kept: `shiftwidth=0` means "use `'tabstop'`", and `softtabstop=0` means
 * the same for what a Tab keypress covers.
 */
internal class VsCodeIndentConfig(private val editor: VsCodeEditor) : VimIndentConfig {

  private fun number(option: NumberOption): Int =
    injector.optionGroup.getOptionValue(option, OptionAccessScope.EFFECTIVE(editor)).value

  /** What a tab character draws as, which is the only width tab arithmetic may use. */
  private val tabWidth: Int
    get() = number(VsCodeOptions.tabstop).takeIf { it > 0 } ?: DEFAULT_WIDTH

  /** What one level of indent is: `'shiftwidth'`, or `'tabstop'` when it is zero, as in Vim. */
  private val shiftWidth: Int
    get() = number(VsCodeOptions.shiftwidth).takeIf { it > 0 } ?: tabWidth

  /** Whether this file indents with tab characters. `'expandtab'` is the API's way round. */
  val usesTabs: Boolean
    get() = !injector.optionGroup
      .getOptionValue(VsCodeOptions.expandtab, OptionAccessScope.EFFECTIVE(editor))
      .asBoolean()

  /**
   * How many columns a Tab pressed in [column] covers: the distance to the next stop, never zero.
   *
   * `'softtabstop'` when it is set, which is the option that exists precisely to make Tab move by
   * something other than a tab character's width - and `'tabstop'` otherwise, which is Vim's own
   * rule for `softtabstop=0`.
   */
  fun toNextTabStop(column: Int): Int {
    val stop = number(VsCodeOptions.softtabstop).takeIf { it > 0 } ?: tabWidth
    return stop - (column % stop)
  }

  override fun getIndentSize(depth: Int): Int = shiftWidth * depth

  override fun createIndentByDepth(depth: Int): String = createIndentBySize(getIndentSize(depth))

  /**
   * [size] columns of whitespace, as tabs and spaces the way this file writes them.
   *
   * The tabs are counted in [tabWidth] and not in [shiftWidth]: a tab character is as wide as
   * `'tabstop'` says whatever `>>` moves by, so `sw=2 ts=8 noexpandtab` indents one level as two
   * spaces rather than as a tab that would have moved eight. That is what Vim does.
   *
   * The remainder is spaces even in a tab-indented file, because a tab cannot express a column that
   * is not a multiple of the tab width - the same arithmetic IntelliJ's `IndentConfig` does, for the
   * same reason.
   */
  override fun createIndentBySize(size: Int): String {
    if (size <= 0) return ""
    if (!usesTabs) return " ".repeat(size)
    return "\t".repeat(size / tabWidth) + " ".repeat(size % tabWidth)
  }

  /**
   * What to indent by when nothing will say.
   *
   * Only reachable through an option set to zero that has no Vim fallback, which `'tabstop'` does
   * not - Vim refuses `ts=0`. Four is VS Code's own default.
   */
  private companion object {
    const val DEFAULT_WIDTH = 4
  }
}
