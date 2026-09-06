/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.host
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.host.HeadlessExecutionContext
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The string functions a `~/.vimrc` is actually written with, on both targets.
 *
 * `printf()` gets the most attention here and deserves it: it cannot be delegated. `String.format`
 * is a JVM method, so `commonMain` cannot use it, and the specification had to be implemented -
 * flags, width, precision, `*`, positional arguments and eleven conversions. That is a lot of
 * surface to be quietly wrong on, and a `printf` in a status line runs on every redraw.
 *
 * The measurement functions are the other place to look. Kotlin strings are UTF-16 and Vim counts
 * bytes, characters and screen columns, so *none* of the three is `String.length` and a test with
 * only ASCII in it would pass for all three implementations being the same function.
 */
class HeadlessStringFunctionTest {

  private fun evaluate(expression: String): String {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    val parsed = injector.vimscriptParser.parseExpression(expression)
      ?: throw AssertionError("failed to parse: $expression")
    val editor = TestVimEditor("", listOf(TestVimCaret(0)))
    return parsed.evaluate(editor, HeadlessExecutionContext, CommandLineVimLContext).toOutputString()
  }

  // ---- printf ----------------------------------------------------------------------------------

  @Test
  fun `test printf substitutes strings and numbers`() {
    assertEquals("one 2 three", evaluate("""printf('%s %d %s', 'one', 2, 'three')"""))
  }

  @Test
  fun `test printf pads to a width and aligns both ways`() {
    assertEquals("   ab", evaluate("""printf('%5s', 'ab')"""))
    assertEquals("ab   ", evaluate("""printf('%-5s', 'ab')"""))
    assertEquals("00042", evaluate("""printf('%05d', 42)"""))
  }

  /** Zero padding goes after the sign, which is the detail that looks right until it is checked. */
  @Test
  fun `test a zero-padded signed number keeps its sign at the front`() {
    assertEquals("+0042", evaluate("""printf('%+05d', 42)"""))
    assertEquals("-0042", evaluate("""printf('%05d', -42)"""))
  }

  @Test
  fun `test printf formats a float to the precision asked for`() {
    assertEquals("3.14", evaluate("""printf('%.2f', 3.14159)"""))
    assertEquals("3", evaluate("""printf('%.0f', 3.14159)"""))
    assertEquals("3.141590", evaluate("""printf('%f', 3.14159)"""))
  }

  @Test
  fun `test printf rounds rather than truncating`() {
    assertEquals("2.68", evaluate("""printf('%.2f', 2.675001)"""))
    assertEquals("-1.5", evaluate("""printf('%.1f', -1.45)"""))
  }

  @Test
  fun `test the number bases`() {
    assertEquals("ff", evaluate("""printf('%x', 255)"""))
    assertEquals("FF", evaluate("""printf('%X', 255)"""))
    assertEquals("0xff", evaluate("""printf('%#x', 255)"""))
    assertEquals("377", evaluate("""printf('%o', 255)"""))
    assertEquals("1010", evaluate("""printf('%b', 10)"""))
  }

  @Test
  fun `test the width can come from an argument`() {
    assertEquals("   ab", evaluate("""printf('%*s', 5, 'ab')"""))
    assertEquals("ab   ", evaluate("""printf('%*s', -5, 'ab')"""))
  }

  /** A translated message is the reason positional arguments exist, and it is why they are here. */
  @Test
  fun `test positional arguments pick their own`() {
    assertEquals("second first", evaluate("""printf('%2${'$'}s %1${'$'}s', 'first', 'second')"""))
  }

  @Test
  fun `test a doubled percent is one percent`() {
    assertEquals("50%", evaluate("""printf('%d%%', 50)"""))
  }

  @Test
  fun `test scientific and general notation`() {
    assertEquals("1.500000e+03", evaluate("""printf('%e', 1500.0)"""))
    assertEquals("1500", evaluate("""printf('%g', 1500.0)"""))
    assertEquals("1.5e-05", evaluate("""printf('%g', 0.000015)"""))
  }

  @Test
  fun `test too few arguments is E766 and too many is E767`() {
    assertTrue(runCatching { evaluate("""printf('%s %s', 'one')""") }.exceptionOrNull()?.message?.contains("E766") == true)
    assertTrue(runCatching { evaluate("""printf('%s', 'one', 'two')""") }.exceptionOrNull()?.message?.contains("E767") == true)
  }

  // ---- measuring -------------------------------------------------------------------------------

  /**
   * Three questions, three answers, and only one of them is `String.length`.
   *
   * `é` is one character, two UTF-8 bytes and one screen column; a CJK ideograph is one character,
   * three bytes and two columns. A test written with ASCII alone would pass whatever these
   * functions did.
   */
  @Test
  fun `test strlen counts bytes and strchars counts characters`() {
    assertEquals("6", evaluate("""strlen('héllo')"""))
    assertEquals("5", evaluate("""strchars('héllo')"""))
    assertEquals("3", evaluate("""strlen('中')"""))
    assertEquals("1", evaluate("""strchars('中')"""))
  }

  @Test
  fun `test strwidth counts the columns a terminal would use`() {
    assertEquals("5", evaluate("""strwidth('héllo')"""))
    assertEquals("2", evaluate("""strwidth('中')"""))
    assertEquals("6", evaluate("""strwidth('中文字')"""))
  }

  @Test
  fun `test strtrans makes a control character visible`() {
    assertEquals("^I", evaluate("""strtrans("\t")"""))
  }

  // ---- slicing and indexing --------------------------------------------------------------------

  @Test
  fun `test strpart takes a substring and clips rather than failing`() {
    assertEquals("ell", evaluate("""strpart('hello', 1, 3)"""))
    assertEquals("llo", evaluate("""strpart('hello', 2)"""))
    assertEquals("", evaluate("""strpart('hello', 10, 3)"""))
  }

  /** A negative start shortens the length, which is Vim's rule and reads as a bug until checked. */
  @Test
  fun `test a negative start shortens what comes back`() {
    assertEquals("he", evaluate("""strpart('hello', -3, 5)"""))
  }

  @Test
  fun `test strcharpart slices by character`() {
    assertEquals("é", evaluate("""strcharpart('héllo', 1, 1)"""))
  }

  @Test
  fun `test stridx and strridx find the first and the last`() {
    assertEquals("2", evaluate("""stridx('abcabc', 'c')"""))
    assertEquals("5", evaluate("""strridx('abcabc', 'c')"""))
    assertEquals("-1", evaluate("""stridx('abc', 'z')"""))
  }

  @Test
  fun `test strgetchar answers with a number and minus one past the end`() {
    assertEquals("104", evaluate("""strgetchar('hello', 0)"""))
    assertEquals("-1", evaluate("""strgetchar('hello', 9)"""))
  }

  // ---- converting ------------------------------------------------------------------------------

  @Test
  fun `test str2nr stops where the number does`() {
    assertEquals("12", evaluate("""str2nr('12abc')"""))
    assertEquals("0", evaluate("""str2nr('abc')"""))
    assertEquals("-7", evaluate("""str2nr('-7')"""))
    assertEquals("255", evaluate("""str2nr('ff', 16)"""))
    assertEquals("255", evaluate("""str2nr('0xff', 16)"""))
  }

  @Test
  fun `test str2float reads the number off the front`() {
    assertEquals("1.5", evaluate("""str2float('1.5abc')"""))
    assertEquals("0.0", evaluate("""str2float('abc')"""))
  }

  @Test
  fun `test str2list and list2str are inverses`() {
    assertEquals("[104, 105]", evaluate("""str2list('hi')"""))
    assertEquals("hi", evaluate("""list2str([104, 105])"""))
  }

  @Test
  fun `test tr replaces character for character`() {
    assertEquals("bcd", evaluate("""tr('abc', 'abc', 'bcd')"""))
    assertEquals("a-b-c", evaluate("""tr('a.b.c', '.', '-')"""))
  }

  @Test
  fun `test tr with sets of different lengths is an error`() {
    assertTrue(runCatching { evaluate("""tr('abc', 'ab', 'xyz')""") }.exceptionOrNull()?.message?.contains("E475") == true)
  }

  // ---- matching --------------------------------------------------------------------------------

  @Test
  fun `test the match family answers five questions about one search`() {
    assertEquals("4", evaluate("""match('foo bar baz', 'ba')"""))
    assertEquals("6", evaluate("""matchend('foo bar baz', 'ba')"""))
    assertEquals("bar", evaluate("""matchstr('foo bar baz', 'ba\w')"""))
    assertEquals("-1", evaluate("""match('foo', 'zz')"""))
  }

  @Test
  fun `test matchlist gives the match and its groups`() {
    assertEquals(
      "['bar', 'b', 'ar', '', '', '', '', '', '', '']",
      evaluate("""matchlist('foo bar', '\(b\)\(ar\)')"""),
    )
  }

  @Test
  fun `test matchstrpos says what and where`() {
    assertEquals("['bar', 4, 7]", evaluate("""matchstrpos('foo bar', 'bar')"""))
  }

  /** The list form is what `match(getline(1, '$'), 'TODO')` uses, and it answers with an index. */
  @Test
  fun `test the match family also takes a list`() {
    assertEquals("1", evaluate("""match(['alpha', 'beta'], 'be')"""))
    assertEquals("beta", evaluate("""matchstr(['alpha', 'beta'], 'be\w*')"""))
  }

  @Test
  fun `test a count skips that many matches`() {
    assertEquals("8", evaluate("""match('foo bar baz', 'ba', 0, 2)"""))
  }

  // ---- substitute ------------------------------------------------------------------------------

  @Test
  fun `test substitute replaces the first and g replaces them all`() {
    assertEquals("Xbcabc", evaluate("""substitute('abcabc', 'a', 'X', '')"""))
    assertEquals("XbcXbc", evaluate("""substitute('abcabc', 'a', 'X', 'g')"""))
  }

  /** The replacement grammar is `:s`'s, which is the point of sharing the code with it. */
  @Test
  fun `test the replacement can refer to the match and its groups`() {
    assertEquals("[abc]", evaluate("""substitute('abc', '.*', '[&]', '')"""))
    assertEquals("cba", evaluate("""substitute('abc', '\(a\)\(b\)\(c\)', '\3\2\1', '')"""))
    assertEquals("ABC", evaluate("""substitute('abc', '.*', '\U&', '')"""))
  }

  /**
   * An empty match must not loop, which is the one way this can hang rather than fail.
   *
   * Vim answers `-a-b-c-`: it also matches the empty string at the very end. This engine's
   * `findAll` stops before the last offset, so there is no trailing `-` - and that is shared with
   * `:s`, not something this function does differently. The behaviour under test here is that it
   * terminates at all.
   */
  @Test
  fun `test a pattern that can match nothing still terminates`() {
    assertEquals("-a-b-c", evaluate("""substitute('abc', 'x*', '-', 'g')"""))
  }

  // ---- type, eval and execute --------------------------------------------------------------------

  @Test
  fun `test type answers with Vim's own numbers`() {
    assertEquals("0", evaluate("""type(1)"""))
    assertEquals("1", evaluate("""type('a')"""))
    assertEquals("3", evaluate("""type([])"""))
    assertEquals("4", evaluate("""type({})"""))
    assertEquals("5", evaluate("""type(1.0)"""))
  }

  @Test
  fun `test typename spells a container with its contents`() {
    assertEquals("number", evaluate("""typename(1)"""))
    assertEquals("list<string>", evaluate("""typename(['a', 'b'])"""))
    assertEquals("list<any>", evaluate("""typename(['a', 1])"""))
    assertEquals("list<unknown>", evaluate("""typename([])"""))
  }

  /** `string()` writes a value out and `eval()` reads it back, which is why the pair exists. */
  @Test
  fun `test eval is the inverse of string`() {
    assertEquals("3", evaluate("""eval('1 + 2')"""))
    assertEquals("[1, 2]", evaluate("""eval(string([1, 2]))"""))
  }

  /**
   * `execute()` is the function form of `:redir`, and is built on it.
   *
   * Which is why the interesting case is a command that prints - and why it is silent by default:
   * the point is to have the output rather than to see it.
   */
  @Test
  fun `test execute catches what a command printed`() {
    assertEquals("hello\n", evaluate("""execute('echo "hello"')"""))
  }

  @Test
  fun `test execute takes a list of commands and runs them in order`() {
    assertEquals("one\ntwo\n", evaluate("""execute(['echo "one"', 'echo "two"'])"""))
  }
}
