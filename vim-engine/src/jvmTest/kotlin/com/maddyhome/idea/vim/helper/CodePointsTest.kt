/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.helper

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Pins the hand-written codepoint helpers to `java.lang.Character`.
 *
 * These drive grapheme iteration, so getting surrogate handling wrong moves the cursor to the
 * wrong place inside an emoji rather than throwing. Unpaired surrogates are included on purpose:
 * the JDK returns them as themselves rather than throwing or substituting, and a reimplementation
 * that "cleaned them up" would differ only on malformed text, which is exactly where it matters.
 */
class CodePointsTest {

  private val samples = listOf(
    "", "a", "abc", "\u00e9", "\u4e2d\u6587",
    "\uD83D\uDE00",                 // emoji, one supplementary codepoint
    "a\uD83D\uDE00b",              // surrounded by BMP characters
    "\uD83D\uDE00\uD83D\uDE01",   // two adjacent supplementary codepoints
    "\uD83D",                      // lone high surrogate
    "\uDE00",                      // lone low surrogate
    "a\uD83Db",                    // unpaired high surrogate in the middle
    "a\uDE00b",                    // unpaired low surrogate in the middle
    "\uD83D\uD83D\uDE00",         // high surrogate followed by a valid pair
  )

  @Test
  fun `test charCount matches the JDK`() {
    for (cp in listOf(0, 1, 0x41, 0xFFFF, 0x10000, 0x1F600, 0x10FFFF)) {
      assertEquals(Character.charCount(cp), charCount(cp), "codepoint " + cp)
    }
  }

  @Test
  fun `test isSupplementaryCodePoint and MAX_CODE_POINT match the JDK`() {
    assertEquals(Character.MAX_CODE_POINT, MAX_CODE_POINT)
    for (cp in listOf(0, 0xFFFF, 0x10000, 0x1F600, 0x10FFFF)) {
      assertEquals(Character.isSupplementaryCodePoint(cp), isSupplementaryCodePoint(cp), "cp " + cp)
    }
  }

  @Test
  fun `test codePointAt matches the JDK at every index`() {
    for (text in samples) {
      for (i in text.indices) {
        assertEquals(
          Character.codePointAt(text, i), codePointAt(text, i),
          "index " + i + " of " + text.map { it.code.toString(16) },
        )
      }
    }
  }

  @Test
  fun `test codePointBefore matches the JDK at every index`() {
    for (text in samples) {
      for (i in 1..text.length) {
        assertEquals(
          Character.codePointBefore(text, i), codePointBefore(text, i),
          "index " + i + " of " + text.map { it.code.toString(16) },
        )
      }
    }
  }

  @Test
  fun `test codePointCount matches the JDK`() {
    for (text in samples) {
      assertEquals(
        Character.codePointCount(text, 0, text.length), codePointCount(text, 0, text.length),
        "whole of " + text.map { it.code.toString(16) },
      )
    }
  }

  @Test
  fun `test toChars matches the JDK`() {
    for (cp in listOf(0, 0x41, 0xFFFF, 0x10000, 0x1F600, 0x10FFFF)) {
      assertContentEquals(Character.toChars(cp), toChars(cp), "codepoint " + cp)
    }
  }

  @Test
  fun `test isLetterCodePoint matches the JDK across the basic plane and beyond`() {
    for (cp in 0..0xFFFF) {
      assertEquals(Character.isLetter(cp), isLetterCodePoint(cp), "codepoint U+%04X".format(cp))
    }
    for (cp in listOf(0x10000, 0x10400, 0x1D400, 0x1F600, 0x20000)) {
      assertEquals(Character.isLetter(cp), isLetterCodePoint(cp), "codepoint U+%04X".format(cp))
    }
  }

  @Test
  fun `test isRightToLeft matches the directionality check it replaced`() {
    for (cp in 0..0xFFFF) {
      val d = Character.getDirectionality(cp)
      val expected = d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
        d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
        d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING ||
        d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE
      assertEquals(expected, isRightToLeft(cp), "codepoint U+%04X".format(cp))
    }
  }
}
