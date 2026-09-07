/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vim's word motions, on both targets.
 *
 * `w`, `b` and `e` are functions of the buffer text and an offset, so they run against the
 * immutable test editor - no mutation needed. They are worth reaching early because they are where
 * this port's character-classification work actually shows: `isVimWhitespace` is Java's definition
 * rather than Kotlin's broader one, and a non-breaking space is punctuation to Vim rather than
 * whitespace. Getting that wrong moves where `w` stops, which no compile check would catch.
 */
class HeadlessWordMotionTest {

  private fun nextWord(text: String, from: Int, count: Int = 1, bigWord: Boolean = false): Int {
    // The host is installed first, deliberately: a receiver is evaluated before its arguments, so
    // building the editor inside the call would read `injector.searchHelper` before this ran.
    injector = HeadlessInjector()
    val editor = TestVimEditor(text, listOf(TestVimCaret(0)))
    return injector.searchHelper.findNextWord(editor, from, count, bigWord)
  }

  @Test
  fun `test w stops at the start of each word`() {
    val text = "one two three"
    assertEquals(4, nextWord(text, 0))
    assertEquals(8, nextWord(text, 4))
  }

  @Test
  fun `test w treats punctuation as its own word`() {
    // Vim stops on the punctuation run, then on the word after it.
    val text = "foo.bar"
    assertEquals(3, nextWord(text, 0))
    assertEquals(4, nextWord(text, 3))
  }

  @Test
  fun `test W skips punctuation`() {
    // A big word runs to whitespace, so the dot is part of it.
    val text = "foo.bar baz"
    assertEquals(8, nextWord(text, 0, bigWord = true))
  }

  @Test
  fun `test a count moves that many words`() {
    val text = "one two three four"
    assertEquals(8, nextWord(text, 0, count = 2))
    assertEquals(14, nextWord(text, 0, count = 3))
  }

  @Test
  fun `test a non-breaking space is not whitespace`() {
    // U+00A0. Kotlin's `Char.isWhitespace()` accepts it and Java's does not, and Vim follows Java:
    // the space is punctuation, so `w` stops on it rather than skipping over it.
    val text = "one two"
    assertEquals(3, nextWord(text, 0))
  }
}
