/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `:folddoopen` and `:folddoclosed` - `:global`, with a fold instead of a pattern.
 *
 * The pair is only interesting in a buffer that actually has a closed fold in it, and a headless
 * buffer has none by itself, so the tests state one. Without that the whole file would be open, and
 * every test here would be checking the same half of the pair.
 *
 * The second thing worth checking is that the sweep survives its own command. Both of these run
 * over range markers rather than line numbers, because a `:d` or a `:normal o` moves every line
 * below it - a sweep collected as numbers works on a three-line file and quietly hits the wrong
 * lines on a longer one.
 */
class HeadlessFoldDoTest {

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

  private fun session(text: String = "one\ntwo\nthree\nfour\nfive"): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session(text)
  }

  @Test
  fun `test folddoopen runs on the lines that are not hidden`() {
    val s = session()
    s.editor.collapseLines(1, 2)
    s.run("folddoopen s/^/> /")

    assertEquals("> one\ntwo\nthree\n> four\n> five", s.editor.text)
  }

  @Test
  fun `test folddoclosed runs on exactly the lines folddoopen did not`() {
    val s = session()
    s.editor.collapseLines(1, 2)
    s.run("folddoclosed s/^/> /")

    assertEquals("one\n> two\n> three\nfour\nfive", s.editor.text)
  }

  /** Vim's default range for both is the whole file, not the usual "the current line". */
  @Test
  fun `test the default range is the whole file`() {
    val s = session()
    s.run("folddoopen s/^/> /")

    assertEquals("> one\n> two\n> three\n> four\n> five", s.editor.text)
  }

  @Test
  fun `test a range narrows the sweep`() {
    val s = session()
    s.run("2,3folddoopen s/^/> /")

    assertEquals("one\n> two\n> three\nfour\nfive", s.editor.text)
  }

  /**
   * A buffer with nothing folded, which is what both hosts hand over most of the time.
   *
   * Every line is then "not in a closed fold", so one of the pair runs everywhere and the other
   * runs nowhere - which is what Vim does in a buffer with no folds, rather than an error.
   */
  @Test
  fun `test nothing folded means folddoclosed does nothing at all`() {
    val s = session()
    s.run("folddoclosed s/^/> /")

    assertEquals("one\ntwo\nthree\nfour\nfive", s.editor.text)
  }

  /**
   * The reason this runs over markers rather than over line numbers.
   *
   * Deleting a line moves every line below it, so the second thing the sweep visits is not where it
   * was when the list was made. With numbers this deletes lines 1, 2 and 3 of a shrinking file and
   * leaves two of the originals behind.
   */
  @Test
  fun `test the sweep survives a command that deletes the lines under it`() {
    val s = session()
    s.run("folddoopen d")

    assertEquals("", s.editor.text, "every line should have gone")
  }

  /**
   * The other direction: a command that *adds* lines must not make the sweep visit them.
   *
   * `:t.` copies the line below itself, so a sweep that recomputed as it went would find its own
   * copy and keep going. Two lines in, two copies out - and a final newline, because copying past
   * the end of a buffer that had none is where the put machinery gives it one.
   */
  @Test
  fun `test a command that adds lines does not make the sweep visit them`() {
    val s = session("one\ntwo")
    s.run("folddoopen t.")

    assertEquals("one\none\ntwo\ntwo\n", s.editor.text)
  }
}
