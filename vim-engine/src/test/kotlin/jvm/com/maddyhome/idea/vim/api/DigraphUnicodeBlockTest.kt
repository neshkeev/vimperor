/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [DigraphUnicodeBlock] to `java.lang.Character.UnicodeBlock`.
 *
 * The ranges were generated from the JDK, and this asserts codepoint by codepoint that they still
 * match it. They decide the section headers `:digraphs` prints, so a boundary that drifts shows up
 * as a header appearing in the wrong place rather than as a crash.
 */
class DigraphUnicodeBlockTest {

  @Test
  fun `test every block range matches the JDK exactly`() {
    for (block in DigraphUnicodeBlock.entries) {
      val jdk = Character.UnicodeBlock.forName(block.name)
      // Walk a margin either side so a range that is too wide or too narrow both fail.
      for (cp in (block.start - 4).coerceAtLeast(0)..(block.end + 4).coerceAtMost(0x10FFFF)) {
        val expected = Character.UnicodeBlock.of(cp) == jdk
        assertEquals(expected, cp in block.start..block.end, block.name + " at U+%04X".format(cp))
      }
    }
  }

  @Test
  fun `test of returns the JDK block wherever this table has one`() {
    val covered = DigraphUnicodeBlock.entries.associateBy { Character.UnicodeBlock.forName(it.name) }
    for (cp in 0..0xFFFF) {
      val jdkBlock = Character.UnicodeBlock.of(cp)
      val expected = covered[jdkBlock]
      assertEquals(expected, DigraphUnicodeBlock.of(cp), "codepoint U+%04X".format(cp))
    }
  }

  @Test
  fun `test a codepoint outside the table is null rather than some other block`() {
    // Deseret is a real block, deliberately not in the table: the digraph listing has no header for
    // it, and the old code reached the same outcome by looking it up and missing.
    assertNull(DigraphUnicodeBlock.of(0x10400))
    assertTrue(DigraphUnicodeBlock.entries.size >= 27, "the table lost entries")
  }
}
