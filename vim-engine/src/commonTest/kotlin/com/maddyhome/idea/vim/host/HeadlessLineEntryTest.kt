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
 * `:append`, `:insert` and `:change` - the three commands whose argument is the next few lines.
 *
 * Every test here runs a *script* rather than a command line, because that is the only shape these
 * three have: the text arrives after the command, and a command line is one line by definition. The
 * grammar cannot express that at all, so the block is folded into the argument before parsing - and
 * the fold is the part that can go wrong in ways nothing else would notice.
 *
 * Which is why the last group is about text that is not a block at all. A fold that is too eager
 * turns an ordinary `echo a` into an append the moment a lone `.` appears further down the file,
 * and the failure is silent: lines vanish into an argument instead of running.
 */
class HeadlessLineEntryTest {

  private class Session(text: String) {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor(text, listOf(caret)).also { caret.editorRef = it }
    val panel: HeadlessOutputPanelService get() = injector.outputPanel as HeadlessOutputPanelService

    fun run(script: String): ExecutionResult =
      injector.vimscriptExecutor.execute(
        script,
        editor,
        HeadlessExecutionContext,
        skipHistory = true,
        indicateErrors = true,
        CommandLineVimLContext,
      )
  }

  private fun session(text: String = "one\ntwo\nthree"): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session(text)
  }

  // ---- append ------------------------------------------------------------------------------------

  @Test
  fun `test append puts the block after the line the range names`() {
    val s = session()
    s.run("2append\nnew one\nnew two\n.\n")

    assertEquals("one\ntwo\nnew one\nnew two\nthree", s.editor.text)
  }

  @Test
  fun `test append with no range goes after the line the caret is on`() {
    val s = session()
    s.run("append\nadded\n.\n")

    assertEquals("one\nadded\ntwo\nthree", s.editor.text)
  }

  /** Vim's address zero, which means "before the first line" rather than a line that is not there. */
  @Test
  fun `test append to line zero puts the block at the very top`() {
    val s = session()
    s.run("0append\nfirst\n.\n")

    assertEquals("first\none\ntwo\nthree", s.editor.text)
  }

  /**
   * The buffer gains a final newline, which is the put machinery's doing and is right.
   *
   * The test file has no newline after its last line; Vim's buffers always end with one, and
   * appending past the end is the moment that difference has to be resolved. `:put`, `:read` and
   * `:copy` all resolve it the same way and in the same place.
   */
  @Test
  fun `test append at the last line goes at the end of the file`() {
    val s = session()
    s.run("\$append\nlast\n.\n")

    assertEquals("one\ntwo\nthree\nlast\n", s.editor.text)
  }

  @Test
  fun `test the abbreviation is the whole command`() {
    val s = session()
    s.run("2a\nshort\n.\n")

    assertEquals("one\ntwo\nshort\nthree", s.editor.text)
  }

  @Test
  fun `test the bang is accepted and changes nothing about the text`() {
    val s = session()
    s.run("1append!\n    kept as written\n.\n")

    assertEquals("one\n    kept as written\ntwo\nthree", s.editor.text)
  }

  /** A block with nothing in it is not a block holding one empty line. */
  @Test
  fun `test an empty block adds nothing`() {
    val s = session()
    s.run("1append\n.\n")

    assertEquals("one\ntwo\nthree", s.editor.text)
  }

  @Test
  fun `test a block that runs to the end of the file needs no terminator`() {
    val s = session()
    s.run("1append\nno dot after me\n")

    assertEquals("one\nno dot after me\ntwo\nthree", s.editor.text)
  }

  // ---- insert ------------------------------------------------------------------------------------

  @Test
  fun `test insert puts the block before the line the range names`() {
    val s = session()
    s.run("2insert\nbefore two\n.\n")

    assertEquals("one\nbefore two\ntwo\nthree", s.editor.text)
  }

  @Test
  fun `test insert with no range goes before the line the caret is on`() {
    val s = session()
    s.run("insert\nbefore one\n.\n")

    assertEquals("before one\none\ntwo\nthree", s.editor.text)
  }

  // ---- change ------------------------------------------------------------------------------------

  @Test
  fun `test change replaces the lines the range names`() {
    val s = session()
    s.run("2change\ninstead of two\n.\n")

    assertEquals("one\ninstead of two\nthree", s.editor.text)
  }

  @Test
  fun `test change over several lines replaces all of them`() {
    val s = session()
    s.run("1,2change\njust one line now\n.\n")

    assertEquals("just one line now\nthree", s.editor.text)
  }

  /** The one case where a block with nothing in it still has work to do. */
  @Test
  fun `test change with an empty block empties the lines`() {
    val s = session()
    s.run("%change\n.\n")

    assertEquals("", s.editor.text)
  }

  @Test
  fun `test change at the end of the file puts the text where the lines were`() {
    val s = session()
    s.run("3change\nnew last\n.\n")

    assertEquals("one\ntwo\nnew last\n", s.editor.text)
  }

  // ---- what must not be folded ---------------------------------------------------------------------

  /**
   * The fold is anchored on a line that is *only* a range and a command word.
   *
   * `echo a` ends in `a`, which is `:append`, and a file with a lone `.` further down would give a
   * greedy fold every excuse it needed. Nothing about the failure would be visible: the `echo` and
   * the lines under it would simply stop running.
   */
  @Test
  fun `test a line that merely ends in a command word is not a block`() {
    val s = session()
    s.run("echo 'a'\nlet g:x = 1\n.\n")

    assertEquals("one\ntwo\nthree", s.editor.text, "nothing should have been inserted")
    assertEquals("a\n", s.panel.lines.joinToString(""), "the echo should have run")
  }

  /**
   * The one that came out of a real regression, in IdeaVim's own `:map` tests.
   *
   * `:imap a b |c " Something else` puts a bare `c` after the bar, and `:c` is `:change`. Before
   * this check that arrived as a `:change` whose argument was ` " Something else` - not a block,
   * because nothing folded it - and the command dutifully deleted a line of the user's file. Vim
   * answers `E488` there, because these three take no argument at all.
   */
  @Test
  fun `test a bare command with something after it is E488 rather than a deleted line`() {
    val s = session()
    s.run("change \" a trailing comment\n")

    assertEquals("one\ntwo\nthree", s.editor.text, "nothing should have been deleted")
    assertEquals(
      "E488: Trailing characters: \" a trailing comment",
      (injector.messages as HeadlessMessages).lastError,
    )
  }

  @Test
  fun `test a command with arguments is not a block even when it starts with the right letter`() {
    val s = session()
    s.run("copy 2\n")

    assertEquals("one\ntwo\none\nthree", s.editor.text, ":copy still copies")
  }

  /**
   * A block inside a bigger script, with ordinary commands on both sides.
   *
   * The fold rewrites text in place, so what it has to leave alone is everything that is not a
   * block - including the lines that come after one, which is where an off-by-one in the fold would
   * show up as a script that stops halfway.
   */
  @Test
  fun `test the script keeps running on both sides of a block`() {
    val s = session()
    s.run("1append\nadded\n.\necho 'after'\n")

    assertEquals("one\nadded\ntwo\nthree", s.editor.text)
    assertEquals("after\n", s.panel.lines.joinToString(""))
  }

  /**
   * Two blocks, and the second one's range counts the lines the first one added.
   *
   * That is not an artefact of the fold - it is what Vim does, and what any command that takes a
   * line number does. `3append` after a line has been inserted above line 3 appends after what is
   * now line 3, which is the old line 2.
   */
  @Test
  fun `test two blocks in one script both land`() {
    val s = session()
    s.run("1append\nfrom the first\n.\n3append\nfrom the second\n.\n")

    assertEquals("one\nfrom the first\ntwo\nfrom the second\nthree", s.editor.text)
  }
}
