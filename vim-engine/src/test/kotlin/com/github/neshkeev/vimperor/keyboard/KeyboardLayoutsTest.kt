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
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The built-in `'langmap'` tables.
 *
 * A table is data, and the only thing that can be wrong with data is that it is wrong - so these
 * check the two properties that make it right rather than spot-checking letters. The lengths have
 * to line up with the US rows, key for key, or every pair after the mistake is shifted by one and
 * silently maps the wrong thing. And nothing in ASCII may be a source, which is the rule the whole
 * file turns on: break it and the Latin layout loses `.` and `:`.
 */
class KeyboardLayoutsTest {

  private fun withLayout(value: String): Unit {
    injector = HeadlessInjector()
    injector.optionGroup.setOptionValue(Options.keyboardlayout, OptionAccessScope.GLOBAL(null), VimString(value))
  }

  private fun map(char: Char) = KeyboardLayouts.mapChar(char)

  @Test
  fun `test the layouts are the ones offered`() {
    assertEquals(listOf("belarusian", "russian", "ukrainian"), KeyboardLayouts.names.sorted())
  }

  @Test
  fun `test nothing is translated until a layout is set`() {
    withLayout("")
    assertNull(map('ф'))
    assertNull(map('в'))
  }

  @Test
  fun `test an unknown layout is an error rather than a silent no-op`() {
    // The whole point of the option: a config runs with errors suppressed, so a typo that left the
    // option empty would be indistinguishable from the feature not working.
    injector = HeadlessInjector()
    assertFailsWith<Exception> {
      injector.optionGroup.setOptionValue(Options.keyboardlayout, OptionAccessScope.GLOBAL(null), VimString("russain"))
    }
  }

  @Test
  fun `test russian home row`() {
    withLayout("russian")
    assertEquals("asdfghjkl;'", "фывапролджэ".map { map(it) }.joinToString(""))
    assertEquals("ASDFGHJKL:\"", "ФЫВАПРОЛДЖЭ".map { map(it) }.joinToString(""))
  }

  @Test
  fun `test russian top and bottom rows`() {
    withLayout("russian")
    assertEquals("qwertyuiop[]", "йцукенгшщзхъ".map { map(it) }.joinToString(""))
    assertEquals("QWERTYUIOP{}", "ЙЦУКЕНГШЩЗХЪ".map { map(it) }.joinToString(""))
    assertEquals("zxcvbnm,.", "ячсмитьбю".map { map(it) }.joinToString(""))
    assertEquals("ZXCVBNM<>", "ЯЧСМИТЬБЮ".map { map(it) }.joinToString(""))
  }

  @Test
  fun `test russian reaches backtick tilde and hash`() {
    // The three non-letters that survive the ASCII rule, and each is worth a key: `` ` `` and `~`
    // are marks, and `#` is search-backwards-for-the-word-here.
    withLayout("russian")
    assertEquals('`', map('ё'))
    assertEquals('~', map('Ё'))
    assertEquals('#', map('№'))
  }

  @Test
  fun `test no ASCII character is ever a source`() {
    // The rule the file turns on. A source in ASCII would be a character the Latin layout also
    // produces, and translating it would take that key away from anyone typing in Latin - `.` for
    // repeat and `:` for the command line among them.
    for (name in KeyboardLayouts.names) {
      withLayout(name)
      for (code in 0x20..0x7F) {
        assertNull(map(code.toChar()), "$name translates the ASCII character ${code.toChar()}")
      }
    }
  }

  @Test
  fun `test the ambiguous keys are left alone`() {
    // Stated as a test rather than only as a comment, because the temptation to add them is the
    // whole reason the comment is there. These are the seven that still need the Latin layout.
    withLayout("russian")
    for (typed in listOf(';', ':', '.', ',', '/', '?', '"')) {
      assertNull(map(typed), "$typed must keep its own meaning for a user typing in Latin")
    }
  }

  @Test
  fun `test ukrainian differs from russian in four letters`() {
    withLayout("ukrainian")
    assertEquals('s', map('і'))
    assertEquals(']', map('ї'))
    assertEquals('\'', map('є'))
    assertEquals('\\', map('ґ'))
    assertEquals('|', map('Ґ'))
    // Ukrainian puts an apostrophe where `ё` is, so the backtick is not reachable - but `₴` is
    // there under Shift, and that does reach `~`.
    assertNull(map('ё'))
    assertEquals('~', map('₴'))
  }

  @Test
  fun `test belarusian differs from russian in two letters`() {
    withLayout("belarusian")
    assertEquals('o', map('ў'))
    assertEquals('O', map('Ў'))
    assertEquals('b', map('і'))
    // Belarusian has neither, and the keys they sit on carry something else.
    assertNull(map('щ'))
    assertNull(map('ъ'))
  }

  @Test
  fun `test two layouts can be listed and the first one wins`() {
    withLayout("russian,ukrainian")
    assertEquals('s', map('ы'), "russian is listed first")
    assertEquals('s', map('і'), "and ukrainian still contributes what russian has no key for")
    assertEquals('d', map('в'), "the letters they agree about are unaffected")
  }

  @Test
  fun `test every layout agrees with russian about the letters it shares`() {
    withLayout("russian")
    val russian = ('а'..'я').associateWith { map(it) }
    for (name in KeyboardLayouts.names - "russian") {
      withLayout(name)
      for ((letter, expected) in russian) {
        val actual = map(letter)
        if (expected != null && actual != null) {
          assertEquals(expected, actual, "$name moved $letter, which is shared with russian")
        }
      }
    }
  }

  @Test
  fun `test a layout translates most of the alphabet`() {
    // A guard against a table that parses but is mostly empty, which is what a length mistake in
    // one row looks like from the outside.
    withLayout("russian")
    assertTrue(('а'..'я').count { map(it) != null } >= 30)
  }
}
