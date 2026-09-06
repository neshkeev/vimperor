/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.host
import com.github.neshkeev.vimperor.match.Matches
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.host.HeadlessExecutionContext
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.host.HeadlessMatchHighlighter
import com.maddyhome.idea.vim.host.HeadlessMessages
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `:match`, `:2match` and `:3match` - a highlight that *stands*.
 *
 * The difference from a search is the whole point and is what these check: the pattern is kept and
 * the ranges are recomputed, so what is lit follows the text as it is edited. Storing the ranges
 * would pass a test that only ever set a pattern and looked once, and would be wrong the moment
 * anybody typed a character above one - so the tests type.
 *
 * Three channels because Vim has three, and they are separate so that a plugin can light something
 * up without taking the one the user is using.
 */
class HeadlessMatchTest {

  private class Session(text: String = "one two one three") {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor(text, listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val painted: HeadlessMatchHighlighter get() = injector.matchHighlighter as HeadlessMatchHighlighter

    /** The text of what channel [channel] is lighting up, which is what a reader would see. */
    fun litOn(channel: Int): List<String> =
      painted.shown[channel]?.second?.map { editor.text.substring(it.startOffset, it.endOffset) }.orEmpty()

    fun run(line: String): ExecutionResult =
      injector.vimscriptExecutor.execute(
        line,
        editor,
        HeadlessExecutionContext,
        skipHistory = true,
        indicateErrors = true,
        CommandLineVimLContext,
      )
  }

  private fun session(text: String = "one two one three"): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session(text)
  }

  @Test
  fun `test match lights up every occurrence of the pattern`() {
    val s = session()
    s.run("match Search /one/")

    assertEquals(listOf("one", "one"), s.litOn(1))
    assertEquals("Search", s.painted.shown[1]?.first?.name)
  }

  @Test
  fun `test the group is passed through as the name Vim uses`() {
    val s = session()
    s.run("match ErrorMsg /two/")

    assertEquals("ErrorMsg", s.painted.shown[1]?.first?.name)
    assertEquals(listOf("two"), s.litOn(1))
  }

  /** The delimiter is whatever the pattern starts with, which is how a pattern holds a slash. */
  @Test
  fun `test any punctuation can delimit the pattern`() {
    val s = session("a/b and a/b")
    s.run("match Search #a/b#")

    assertEquals(listOf("a/b", "a/b"), s.litOn(1))
  }

  @Test
  fun `test the pattern is a Vim regular expression`() {
    val s = session("one oneself one")
    s.run("""match Search /\<one\>/""")

    assertEquals(listOf("one", "one"), s.litOn(1), "the word boundary should have skipped `oneself`")
  }

  // Standing, which is the whole difference from a search.

  /**
   * The ranges have to follow the text.
   *
   * An implementation that stored the ranges instead of the pattern passes every test above and
   * fails this one, which is why the test types rather than only setting a pattern.
   */
  @Test
  fun `test what is lit follows the buffer as it is edited`() {
    val s = session("one two")
    s.run("match Search /one/")
    assertEquals(listOf(0), s.painted.shown[1]?.second?.map { it.startOffset })

    // Two characters put in front of it, which moves the match without changing it.
    s.run("s/^/xx/")

    assertEquals("xxone two", s.editor.text)
    assertEquals(listOf("one"), s.litOn(1))
    assertEquals(listOf(2), s.painted.shown[1]?.second?.map { it.startOffset }, "the match moved with the text")
  }

  @Test
  fun `test text that stops matching stops being lit`() {
    val s = session("one two")
    s.run("match Search /one/")
    s.run("%s/one/xxx/")

    assertEquals(emptyList(), s.litOn(1), "nothing matches any more: ${s.painted.shown}")
  }

  @Test
  fun `test text that starts matching is lit without the command being run again`() {
    val s = session("two")
    s.run("match Search /one/")
    s.run("%s/two/one/")

    assertEquals(listOf("one"), s.litOn(1))
  }

  // Turning it off.

  @Test
  fun `test match none puts it out`() {
    val s = session()
    s.run("match Search /one/")
    s.run("match none")

    assertNull(s.painted.shown[1])
    assertTrue(1 in s.painted.cleared)
  }

  /** Vim accepts a bare `:match` for the same thing, which is what people actually type. */
  @Test
  fun `test match with nothing after it also puts it out`() {
    val s = session()
    s.run("match Search /one/")
    s.run("match")

    assertNull(s.painted.shown[1])
  }

  // The three channels.

  @Test
  fun `test the three channels are separate`() {
    val s = session("one two three")
    s.run("match Search /one/")
    s.run("2match ErrorMsg /two/")
    s.run("3match Todo /three/")

    assertEquals(listOf("one"), s.litOn(1))
    assertEquals(listOf("two"), s.litOn(2))
    assertEquals(listOf("three"), s.litOn(3))
  }

  @Test
  fun `test clearing one channel leaves the others alone`() {
    val s = session("one two")
    s.run("match Search /one/")
    s.run("2match ErrorMsg /two/")
    s.run("2match none")

    assertEquals(listOf("one"), s.litOn(1))
    assertNull(s.painted.shown[2])
  }

  @Test
  fun `test setting a channel again replaces its pattern`() {
    val s = session("one two")
    s.run("match Search /one/")
    s.run("match Search /two/")

    assertEquals(listOf("two"), s.litOn(1))
    assertEquals("two", Matches.current(s.editor, 1)?.pattern)
  }

  // Malformed.

  @Test
  fun `test a group with no pattern is an error`() {
    val s = session()
    s.messages.clearError()
    s.run("match Search")

    assertTrue(s.messages.lastError?.contains("E475") == true, "got ${s.messages.lastError}")
  }

  @Test
  fun `test a pattern with no closing delimiter is an error`() {
    val s = session()
    s.messages.clearError()
    s.run("match Search /one")

    assertTrue(s.messages.lastError?.contains("E475") == true, "got ${s.messages.lastError}")
  }

  /**
   * A pattern that stops compiling paints nothing rather than throwing, because this runs after
   * every keystroke and an exception there would be one per key for the rest of the session.
   */
  /**
   * `:2match` arrives as a range, because this parser reads a leading digit before it looks the
   * command name up - and a range that is not one of the three channels is Vim's own `E481`.
   */
  @Test
  fun `test a range that is not a channel is refused`() {
    val s = session()
    s.messages.clearError()
    s.run("4match Search /one/")
    assertTrue(s.messages.lastError?.contains("E481") == true, "got ${s.messages.lastError}")

    s.messages.clearError()
    s.run("1,3match Search /one/")
    assertTrue(s.messages.lastError?.contains("E481") == true, "got ${s.messages.lastError}")
  }

  @Test
  fun `test a pattern that cannot be compiled paints nothing and does not throw`() {
    val s = session()
    Matches.set(s.editor, 1, "Search", """\%(""")
    Matches.repaint(s.editor)

    assertEquals(emptyList(), s.litOn(1))
  }
}
