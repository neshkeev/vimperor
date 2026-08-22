/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.helper

import com.maddyhome.idea.vim.common.concurrentCollectionOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contracts that must hold on **every** target, run on each of them.
 *
 * The differential tests in `jvmTest` compare against `java.lang.*` and can only run where that
 * exists; these check the same behaviour in a platform-independent way, so a JS `actual` that
 * quietly does something else fails here rather than in a user's editor.
 */
class PlatformContractsTest {

  // --- enum sets ------------------------------------------------------------------------------

  private enum class Mode { NORMAL, INSERT, VISUAL, SELECT, OP_PENDING }

  @Test
  fun `test enum sets iterate in ordinal order, not insertion order`() {
    // The JVM actual is a real EnumSet, which iterates by ordinal. Mapping listings are rendered to
    // the user in iteration order, so a JS actual backed by a plain LinkedHashSet would reorder
    // them. Inserted deliberately backwards.
    val set = enumSetOf(Mode.SELECT, Mode.NORMAL, Mode.VISUAL)
    assertContentEquals(listOf(Mode.NORMAL, Mode.VISUAL, Mode.SELECT), set.toList())
  }

  @Test
  fun `test enum set membership and mutation`() {
    val set = noneOfEnum<Mode>()
    assertTrue(set.isEmpty())
    set.add(Mode.INSERT)
    set.add(Mode.NORMAL)
    assertTrue(Mode.INSERT in set)
    assertFalse(Mode.VISUAL in set)
    assertEquals(2, set.size)
    assertContentEquals(listOf(Mode.NORMAL, Mode.INSERT), set.toList())
    set.remove(Mode.NORMAL)
    assertContentEquals(listOf(Mode.INSERT), set.toList())
  }

  @Test
  fun `test allOfEnum contains every constant in ordinal order`() {
    assertContentEquals(Mode.entries.toList(), allOfEnum<Mode>().toList())
  }

  // --- listener collections -------------------------------------------------------------------

  @Test
  fun `test a listener collection survives removal during iteration`() {
    // Reentrancy, not concurrency: a listener removing itself while being notified. This is why the
    // JS actual is copy-on-write rather than an ArrayList, which would throw here.
    val collection = concurrentCollectionOf<String>()
    collection.add("a")
    collection.add("b")
    collection.add("c")
    val seen = mutableListOf<String>()
    for (item in collection) {
      seen.add(item)
      if (item == "a") collection.remove("b")
    }
    assertEquals(listOf("a", "b", "c"), seen, "a removal must not truncate the walk in progress")
    assertEquals(listOf("a", "c"), collection.toList())
  }

  @Test
  fun `test a listener collection survives addition during iteration`() {
    // Deliberately does not assert whether the new element is seen. The JVM's
    // ConcurrentLinkedDeque is weakly consistent and may show it; the JS copy-on-write actual walks
    // a snapshot and never will. Both are safe, they simply differ, and the expect documents that
    // rather than either side pretending to a guarantee it does not offer.
    val collection = concurrentCollectionOf<String>()
    collection.add("a")
    var iterations = 0
    for (item in collection) {
      iterations++
      if (item == "a") collection.add("b")
      if (iterations > 10) break
    }
    assertTrue(iterations in 1..2, "expected one or two elements, saw " + iterations)
    assertEquals(listOf("a", "b"), collection.toList())
  }

  // --- codepoints -----------------------------------------------------------------------------

  @Test
  fun `test codepoint helpers handle surrogate pairs`() {
    val emoji = "\uD83D\uDE00"
    assertEquals(1, codePointCount(emoji, 0, emoji.length))
    assertEquals(0x1F600, codePointAt(emoji, 0))
    assertEquals(0x1F600, codePointBefore(emoji, emoji.length))
    assertEquals(2, charCount(0x1F600))
    assertEquals(1, charCount('a'.code))
    assertTrue(isSupplementaryCodePoint(0x1F600))
    assertFalse(isSupplementaryCodePoint('a'.code))
    assertEquals(emoji, toChars(0x1F600).concatToString())
  }

  @Test
  fun `test codepoint helpers return an unpaired surrogate as itself`() {
    // Built rather than written as a literal: Kotlin/JS does not carry a lone surrogate through a
    // source literal - it arrives as '?' - so a literal here would test the compiler, not the code.
    val lone = charArrayOf(0xD83D.toChar()).concatToString()
    assertEquals(0xD83D, codePointAt(lone, 0))
    assertEquals(0xD83D, codePointBefore(lone, 1))
  }

  @Test
  fun `test isVimWhitespace excludes the non-breaking spaces`() {
    assertTrue(isVimWhitespace(' '))
    assertTrue(isVimWhitespace('\t'))
    assertTrue(isVimWhitespace('\n'))
    assertFalse(isVimWhitespace('\u00A0'), "non-breaking space is punctuation to Vim")
    assertFalse(isVimWhitespace('a'))
  }

  // --- character classification ---------------------------------------------------------------

  @Test
  fun `test charCategoryOf classifies representative characters`() {
    assertEquals(CharCategory.UPPERCASE_LETTER, charCategoryOf('A'.code))
    assertEquals(CharCategory.LOWERCASE_LETTER, charCategoryOf('a'.code))
    assertEquals(CharCategory.DECIMAL_DIGIT_NUMBER, charCategoryOf('7'.code))
    assertEquals(CharCategory.SPACE_SEPARATOR, charCategoryOf(' '.code))
    assertEquals(CharCategory.CONTROL, charCategoryOf(0x0A))
    assertEquals(CharCategory.OTHER_LETTER, charCategoryOf(0x4E2D))
    assertEquals(CharCategory.FORMAT, charCategoryOf(0x200B.let { 0x00AD }))
  }

  @Test
  fun `test isLetterCodePoint agrees with the categories`() {
    assertTrue(isLetterCodePoint('a'.code))
    assertTrue(isLetterCodePoint(0x4E2D))
    assertFalse(isLetterCodePoint('7'.code))
    assertFalse(isLetterCodePoint(' '.code))
  }

  @Test
  fun `test isRightToLeft`() {
    assertTrue(isRightToLeft(0x05D0), "Hebrew alef")
    assertTrue(isRightToLeft(0x0627), "Arabic alef")
    assertFalse(isRightToLeft('a'.code))
    assertFalse(isRightToLeft(0x4E2D), "CJK is neutral")
  }

  // --- string tokenizer -----------------------------------------------------------------------

  @Test
  fun `test the tokenizer drops empty tokens`() {
    val tokenizer = StringTokenizer("a\n\nb", "\n")
    val tokens = mutableListOf<String>()
    while (tokenizer.hasMoreTokens()) tokens.add(tokenizer.nextToken())
    assertEquals(listOf("a", "b"), tokens)
  }

  @Test
  fun `test the tokenizer throws past the end`() {
    assertFailsWith<NoSuchElementException> { StringTokenizer("").nextToken() }
  }

  // --- lists and maps -------------------------------------------------------------------------

  @Test
  fun `test indexOfSubList`() {
    assertEquals(1, listOf("a", "b", "c").indexOfSubList(listOf("b", "c")))
    assertEquals(-1, listOf("a", "b", "c").indexOfSubList(listOf("a", "c")))
    assertEquals(0, listOf("a").indexOfSubList(emptyList()))
  }

  @Test
  fun `test putLast and putFirst move keys in iteration order`() {
    val map = linkedMapOf("a" to 1, "b" to 2, "c" to 3)
    map.putLast("a", 9)
    assertEquals(listOf("b", "c", "a"), map.keys.toList())
    map.putFirst("c", 8)
    assertEquals(listOf("c", "b", "a"), map.keys.toList())
    assertEquals(9, map["a"])
    assertEquals(8, map["c"])
  }

  @Test
  fun `test hex and octal formatting pad the way the format strings did`() {
    assertEquals("41", hexString(0x41, 2))
    assertEquals("0041", hexString(0x41, 4))
    assertEquals("101", octString(0x41, 3))
    assertEquals("007", octString(7, 3))
  }

  // --- locks ----------------------------------------------------------------------------------

  @Test
  fun `test withLock runs the block and returns its value`() {
    val lock = Any()
    assertEquals(42, withLock(lock) { 42 })
  }
}
