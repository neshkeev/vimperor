/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.helper

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the multiplatform character predicates to the `java.lang.Character` behaviour they replace.
 *
 * All three are places where the obvious Kotlin equivalent is subtly *not* equivalent, and where
 * being wrong would change where word motions stop rather than crash anything.
 */
class CharacterHelperTest {

  @Test
  fun `test isVimWhitespace matches Character-isWhitespace for every char`() {
    for (code in 0..0xFFFF) {
      val ch = code.toChar()
      assertEquals(Character.isWhitespace(ch), isVimWhitespace(ch), "char U+%04X".format(code))
    }
  }

  @Test
  fun `test Kotlin isWhitespace would have been wrong, and by exactly three characters`() {
    // The reason isVimWhitespace exists. Kotlin's Char.isWhitespace() is isWhitespace() ||
    // isSpaceChar(), so it accepts the non-breaking spaces that Java's rejects. If a future Kotlin
    // release narrows that, this test says so rather than the behaviour drifting quietly.
    val divergent = (0..0xFFFF).map { it.toChar() }.filter { it.isWhitespace() != isVimWhitespace(it) }
    assertEquals(listOf('\u00A0', '\u2007', '\u202F'), divergent)
    for (ch in divergent) {
      assertTrue(ch.isWhitespace(), "Kotlin should treat " + ch.code + " as whitespace")
      assertFalse(isVimWhitespace(ch), "Vim should not treat " + ch.code + " as whitespace")
    }
  }

  @Test
  fun `test charCategoryOf matches Character-getType, including outside the basic plane`() {
    val codepoints = (0..0xFFFF).toList() +
      listOf(0x10000, 0x1F600, 0x20000, 0xE0001, 0xF0000, 0x10FFFF)
    for (cp in codepoints) {
      assertEquals(
        Character.getType(cp),
        charCategoryOf(cp).value,
        "codepoint U+%04X".format(cp),
      )
    }
  }

  @Test
  fun `test the Unicode block ranges match Character-UnicodeBlock exactly`() {
    // charType() classifies by explicit range now instead of asking UnicodeBlock. The ranges are
    // restated here on purpose: the test's job is to fail if the JDK's boundaries ever move away
    // from the constants baked into CharacterHelper.
    val hiragana = '\u3040'..'\u309F'
    val katakana = '\u30A0'..'\u30FF'
    val cjk = '\u4E00'..'\u9FFF'
    for (code in 0..0xFFFF) {
      val ch = code.toChar()
      val block = Character.UnicodeBlock.of(ch)
      val label = "char U+%04X".format(code)
      assertEquals(block == Character.UnicodeBlock.HIRAGANA, ch in hiragana, label)
      assertEquals(block == Character.UnicodeBlock.KATAKANA, ch in katakana, label)
      assertEquals(block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS, ch in cjk, label)
    }
  }
}
