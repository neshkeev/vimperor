/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.keyboard

import com.maddyhome.idea.vim.api.Options
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString

/**
 * Built-in physical-key tables for `'keyboardlayout'`.
 *
 * `'langmap'` is Vim's answer to "I type Vim commands on a non-English keyboard", and it works: it
 * translates a typed character back to the command character on the same physical key, everywhere
 * the engine expects a command - Normal, Visual and Operator-pending, and the register and mark
 * names those read. [com.maddyhome.idea.vim.options.helpers.LangMapOptionHelper] has all of it.
 *
 * What Vim does not have is the table. A Russian user has to write out sixty-odd pairs, in an
 * option whose value needs a backslash before every literal `;` and `,` - and then another
 * backslash to get that one past `:set` - and a `\"` so the rest of the line is not read as a
 * comment. That line is 150 characters of escaping, and a config runs with `indicateErrors = false`
 * so one slip is silent. This is the table, written once and checked by a test.
 *
 * ## Only characters that cannot be typed on a US keyboard
 *
 * This is the rule the whole file turns on, and it is a decision rather than an omission.
 *
 * A layout maps the *whole* keyboard, so the physical key marked `4` emits `$` on a US layout and
 * `;` on a Russian one - and the key marked `/` emits `/` there and `.` here. Translating those
 * too would make `$` and `/` reachable from the Cyrillic layout, and would take `.`, `,`, `;`,
 * `:`, `/` and `?` away from anyone typing on the Latin one, because a character arrives here with
 * no record of which layout produced it. `:` is the command line and `.` is the repeat; a user who
 * works in both layouts - which is the whole point - cannot afford to lose either.
 *
 * So a pair is kept only when the character being typed is outside ASCII, where it can only have
 * come from the Cyrillic layout and translating it is unambiguous. Cyrillic letters, `ё`, `№` and
 * Ukrainian's `ґ` and `₴` qualify; the punctuation keys do not.
 *
 * The cost is that `$`, `^`, `@`, `&`, `/`, `?` and `|` still need the Latin layout. `0`, `_`,
 * `%`, `(`, `)` and every digit are unaffected - they are the same key on both. A user who never
 * issues commands from the Latin layout can add the rest with `'langmap'`, which is consulted
 * first; the README carries that line, escaped.
 *
 * The rule pays for itself twice, because the ASCII half is also the half that *varies*: Windows
 * and macOS disagree about the Russian digit row, and agree about every letter.
 */
object KeyboardLayouts {

  /**
   * The US keyboard, in physical key order: the 47 keys that carry a character, unshifted and
   * shifted. Every layout below is written against these two strings, position for position, so a
   * table can be read - and corrected - by eye.
   */
  private const val QWERTY_UNSHIFTED = "`1234567890-=qwertyuiop[]\\asdfghjkl;'zxcvbnm,./"
  private const val QWERTY_SHIFTED = "~!@#\$%^&*()_+QWERTYUIOP{}|ASDFGHJKL:\"ZXCVBNM<>?"

  private class Layout(val unshifted: String, val shifted: String) {
    init {
      require(unshifted.length == QWERTY_UNSHIFTED.length) { "unshifted row is the wrong length" }
      require(shifted.length == QWERTY_SHIFTED.length) { "shifted row is the wrong length" }
    }
  }

  /**
   * The layouts `'keyboardlayout'` accepts.
   *
   * All three are ЙЦУКЕН and differ in a handful of keys. Ukrainian has `і`, `ї`, `є` and `ґ` where
   * Russian has `ы`, `ъ`, `э` and the backslash, and puts an apostrophe where `ё` is - so `` ` ``
   * is not reachable there, though `~` is, from `₴`. Belarusian has `ў` in place of `щ` and `і` in
   * place of `и`, and an apostrophe where `ъ` is - so `]` and `}` are not reachable there.
   */
  private val layouts = mapOf(
    "russian" to Layout(
      "ё1234567890-=йцукенгшщзхъ\\фывапролджэячсмитьбю.",
      "Ё!\"№;%:?*()_+ЙЦУКЕНГШЩЗХЪ/ФЫВАПРОЛДЖЭЯЧСМИТЬБЮ,",
    ),
    "ukrainian" to Layout(
      "'1234567890-=йцукенгшщзхїґфівапролджєячсмитьбю.",
      "₴!\"№;%:?*()_+ЙЦУКЕНГШЩЗХЇҐФІВАПРОЛДЖЄЯЧСМИТЬБЮ,",
    ),
    "belarusian" to Layout(
      "ё1234567890-=йцукенгшўзх'\\фывапролджэячсмітьбю.",
      "Ё!\"№;%:?*()_+ЙЦУКЕНГШЎЗХ'/ФЫВАПРОЛДЖЭЯЧСМІТЬБЮ,",
    ),
  )

  /** The names `:set keyboardlayout=` accepts, which is also what bounds the option. */
  val names: Collection<String> = layouts.keys

  /**
   * The command character on the same physical key as [from], or `null` if this is not a key any
   * enabled layout translates.
   *
   * `null` rather than [from] so that the caller can tell "no layout has an opinion" from "a layout
   * says this character maps to itself", and so `'langmap'` stays the thing that decides.
   */
  fun mapChar(from: Char): Char? =
    injector.optionGroup.getParsedEffectiveOptionValue(Options.keyboardlayout, null, ::parse)[from]

  private fun parse(value: VimString): Map<Char, Char> = buildMap {
    // In the order they are listed, so an earlier layout wins a key two of them disagree about.
    // `russian,ukrainian` is a reasonable thing to write - one keyboard, two layouts on it - and
    // the letters they share are the letters they agree about anyway.
    value.value.split(',').filter { it.isNotEmpty() }.forEach { name ->
      val layout = layouts[name] ?: return@forEach
      addPairs(layout.unshifted, QWERTY_UNSHIFTED)
      addPairs(layout.shifted, QWERTY_SHIFTED)
    }
  }

  private fun MutableMap<Char, Char>.addPairs(typed: String, command: String) {
    for (i in typed.indices) {
      // The rule this file exists to state: only what cannot have come from a US keyboard. See the
      // class comment - translating the ASCII punctuation would break the Latin layout.
      if (typed[i].code <= 0x7F) continue
      if (!containsKey(typed[i])) put(typed[i], command[i])
    }
  }
}
