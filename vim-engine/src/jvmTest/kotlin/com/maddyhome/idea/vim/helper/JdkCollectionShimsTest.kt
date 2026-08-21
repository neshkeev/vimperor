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
import kotlin.test.assertFailsWith

/**
 * Differential tests for the multiplatform stand-ins for `java.util.StringTokenizer` and
 * `java.util.Collections.indexOfSubList`.
 *
 * These assert against the JDK implementations they replaced rather than against hand-written
 * expectations, because the point of the shims is to be indistinguishable from them. The edge cases
 * that matter - runs of delimiters collapsing, leading and trailing delimiters vanishing, an empty
 * target sublist matching at 0 - are exactly the ones that are easy to get subtly wrong.
 *
 * Once the engine drops its JVM dependency these lose their reference implementation, so they are
 * deliberately in `jvmTest`: they pin the behaviour now, while the JDK is still here to be asked.
 */
class JdkCollectionShimsTest {

  private fun jdkTokens(string: String, delimiters: String?): List<String> {
    val tokenizer =
      if (delimiters == null) java.util.StringTokenizer(string) else java.util.StringTokenizer(string, delimiters)
    val result = mutableListOf<String>()
    while (tokenizer.hasMoreTokens()) result.add(tokenizer.nextToken())
    return result
  }

  private fun shimTokens(string: String, delimiters: String? = null): List<String> {
    val tokenizer = if (delimiters == null) StringTokenizer(string) else StringTokenizer(string, delimiters)
    val result = mutableListOf<String>()
    while (tokenizer.hasMoreTokens()) result.add(tokenizer.nextToken())
    return result
  }

  private fun assertMatchesJdk(string: String, delimiters: String? = null) {
    assertEquals(jdkTokens(string, delimiters), shimTokens(string, delimiters), "input=" + escape(string))
  }

  private fun escape(string: String) = string.map { if (it.code < 0x20) "<" + it.code + ">" else it.toString() }
    .joinToString("")

  @Test
  fun `test tokenizer matches the JDK for the default whitespace delimiters`() {
    // The default delimiters are space, tab, newline, carriage return and form feed.
    listOf(
      "",
      "   ",
      "one",
      "one two three",
      "  leading",
      "trailing  ",
      "collapsed     run",
      "mixed \t\n\rwhitespace",
      "form\u000Cfeed\u000Cleads",
      "a\tb\nc\rde",
    ).forEach { assertMatchesJdk(it) }
  }

  @Test
  fun `test tokenizer matches the JDK when delimiter runs would produce empty tokens`() {
    // This is why the shim is not String.split: split keeps the empty tokens, and callers such as
    // blockwise put rely on them being dropped.
    listOf("a\n\nb", "\n\na\n\n", "\n", "a\n", "\nb", "a\n\n\n\nb").forEach { assertMatchesJdk(it, "\n") }
    assertEquals(listOf("a", "b"), shimTokens("a\n\nb", "\n"))
  }

  @Test
  fun `test tokenizer matches the JDK for a multi-character delimiter set`() {
    // The delimiters argument is a set of characters, not a separator string.
    listOf("a,b;c", ",,a;;b,,", "a-b_c", "no delimiters here", ",;,;").forEach { assertMatchesJdk(it, ",;") }
    assertEquals(listOf("a", "b", "c"), shimTokens("a,b;c", ",;"))
  }

  @Test
  fun `test tokenizer matches the JDK for a NUL delimiter`() {
    // Address parses search ranges such as `/one//two/`, which arrive separated by NUL.
    val nul = "\u0000"
    listOf("/one/" + nul + "/two/", nul, nul + "a" + nul + nul + "b" + nul, "")
      .forEach { assertMatchesJdk(it, nul) }
  }

  @Test
  fun `test tokenizer nextToken past the end throws like the JDK`() {
    assertFailsWith<NoSuchElementException> { StringTokenizer("").nextToken() }
    assertFailsWith<NoSuchElementException> { StringTokenizer("   ").nextToken() }
    val tokenizer = StringTokenizer("only")
    assertEquals("only", tokenizer.nextToken())
    assertFailsWith<NoSuchElementException> { tokenizer.nextToken() }
  }

  @Test
  fun `test tokenizer allows nextToken without a preceding hasMoreTokens call`() {
    // SetCommand's EscapedWhitespaceStringTokenizer overrides nextToken and interleaves the two, so
    // neither call may depend on the other having run first.
    val tokenizer = StringTokenizer("  a  b  ")
    assertEquals("a", tokenizer.nextToken())
    assertEquals(true, tokenizer.hasMoreTokens())
    assertEquals(true, tokenizer.hasMoreTokens())
    assertEquals("b", tokenizer.nextToken())
    assertEquals(false, tokenizer.hasMoreTokens())
  }

  @Test
  fun `test indexOfSubList matches the JDK`() {
    val empty = listOf<String>()
    val cases = listOf(
      empty to empty,
      listOf("a") to empty,
      empty to listOf("a"),
      listOf("a", "b", "c") to listOf("b"),
      listOf("a", "b", "c") to listOf("a", "b"),
      listOf("a", "b", "c") to listOf("b", "c"),
      listOf("a", "b", "c") to listOf("a", "b", "c"),
      listOf("a", "b", "c") to listOf("a", "c"),
      listOf("a", "b", "c") to listOf("a", "b", "c", "d"),
      listOf("a", "b", "a", "b") to listOf("a", "b"),
      listOf("a", "a", "a") to listOf("a", "a"),
    )
    for ((source, target) in cases) {
      assertEquals(
        java.util.Collections.indexOfSubList(source, target),
        source.indexOfSubList(target),
        "source=" + source + " target=" + target,
      )
    }
  }
}
