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
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `:uniq` - Unix `uniq`, over a range of the buffer.
 *
 * The thing worth checking on every one of these is that it is *adjacent* duplicates and nothing
 * else. `:sort u` removes every repeat and reorders the file to do it; this removes a repeat only
 * where it sits directly under the line it repeats, which is what makes it usable on a log or on an
 * already-sorted list. A test that only ever used a file with its duplicates already next to each
 * other would pass for both commands.
 */
class HeadlessUniqTest {

  private class Session(text: String) {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor(text, listOf(caret)).also { caret.editorRef = it }

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

  private fun session(text: String): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session(text)
  }

  @Test
  fun `test the first line of every run survives`() {
    val s = session("a\na\nb\nb\nb\nc")
    s.run("uniq")

    assertEquals("a\nb\nc", s.editor.text)
  }

  /** The whole difference from `:sort u`, and the reason both commands exist. */
  @Test
  fun `test a repeat that is not adjacent is left alone`() {
    val s = session("a\nb\na")
    s.run("uniq")

    assertEquals("a\nb\na", s.editor.text, "nothing here is next to its own duplicate")
  }

  @Test
  fun `test nothing changes when there is nothing to remove`() {
    val s = session("a\nb\nc")
    s.run("uniq")

    assertEquals("a\nb\nc", s.editor.text)
  }

  @Test
  fun `test u keeps only the lines that never repeat`() {
    val s = session("a\na\nb\nc\nc")
    s.run("uniq u")

    assertEquals("b", s.editor.text)
  }

  /**
   * Vim's wording: "only keep lines that are immediately followed by a duplicate".
   *
   * Taken literally, which is what makes a run of three interesting: the first two are each
   * followed by a duplicate and the third is not, so two survive.
   */
  @Test
  fun `test the bang keeps the lines that are followed by a duplicate`() {
    val s = session("a\na\na\nb\nc\nc")
    s.run("uniq!")

    assertEquals("a\na\nc", s.editor.text)
  }

  /** "If both [!] and [u] are given, [u] is ignored and [!] takes effect." */
  @Test
  fun `test the bang wins over u`() {
    val s = session("a\na\nb")
    s.run("uniq! u")

    assertEquals("a", s.editor.text)
  }

  @Test
  fun `test i ignores case when comparing`() {
    val s = session("Alpha\nALPHA\nbeta")
    s.run("uniq i")

    assertEquals("Alpha\nbeta", s.editor.text)
  }

  @Test
  fun `test without i the case matters`() {
    val s = session("Alpha\nALPHA\nbeta")
    s.run("uniq")

    assertEquals("Alpha\nALPHA\nbeta", s.editor.text)
  }

  @Test
  fun `test a range narrows what is looked at`() {
    val s = session("a\na\nb\nb\nc\nc")
    s.run("3,4uniq")

    assertEquals("a\na\nb\nc\nc", s.editor.text)
  }

  /**
   * The pattern changes what is *compared*, not which lines are looked at.
   *
   * Without `r` the comparison is on what comes after the match, which is Vim's own example: keep
   * only unique lines ignoring the first five characters.
   */
  @Test
  fun `test a pattern with no r compares what comes after the match`() {
    val s = session("00001same\n00002same\n00003other")
    s.run("uniq /.\\{5}/")

    assertEquals("00001same\n00003other", s.editor.text)
  }

  /** With `r`, on the text the pattern matched instead. */
  @Test
  fun `test r compares the text the pattern matched`() {
    val s = session("aa: one\naa: two\nbb: three")
    s.run("uniq r /^..:/")

    assertEquals("aa: one\nbb: three", s.editor.text)
  }

  @Test
  fun `test the file keeps its final newline when it had one`() {
    val s = session("a\na\nb\n")
    s.run("uniq")

    assertEquals("a\nb\n", s.editor.text)
  }

  @Test
  fun `test a file with no final newline does not gain one`() {
    val s = session("a\na\nb")
    s.run("uniq")

    assertEquals("a\nb", s.editor.text)
  }
}
