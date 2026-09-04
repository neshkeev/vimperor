/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `=`, which reached the host as a `TODO` and killed the keystroke.
 *
 * "Vimperor: typing '=' failed - NotImplementedError: An operation is not implemented: VS Code
 * host: autoIndentRange is an asynchronous command" - reported from a real window, over a visual
 * selection. Every form of `=` lands on the same seam: the visual action calls `autoIndentRange`
 * directly, and `==`, `=j` and `=ap` all reach it through `autoIndentMotion`.
 *
 * Asynchronous is true and was never a reason to leave it unimplemented. The host already holds
 * the user's keys until a command lands - undo and redo depend on it - and the only piece missing
 * was somewhere to put the caret move, which has to happen after the re-read rather than before it.
 *
 * What a test can check here is the request and the caret, not the indenting: the stub host has no
 * language rules and does not re-indent anything. That is the right split. Whether
 * `editor.action.reindentselectedlines` indents Kotlin correctly is VS Code's business and is not
 * this fork's to reimplement; whether Vimperor asks it the right question, on the right lines, and
 * leaves the caret where Vim leaves it, is.
 */
class AutoIndentTest {

  private class RecordingSink : MessageSink {
    val errors: MutableList<String> = mutableListOf()
    override fun message(text: String?) {}
    override fun error(text: String?) { text?.let { errors += it } }
    override fun status(text: String?) {}
  }

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val sink = RecordingSink()
    val host = VimHost(sink = sink).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      commandsExecuted().length = 0
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    val caretOffset: Int get() = host.editorFor(fake).primaryCaret().offset
    val content: String get() = fake.document.content
  }

  // ---- the reported crash ------------------------------------------------------------------

  @Test
  fun `test = over a visual selection does not fail`() {
    val session = Session("one\ntwo\nthree")

    session.type("Vj=")

    assertEquals(emptyList(), session.sink.errors, "`=` should not have reported a failure")
  }

  @Test
  fun `test = over a visual selection asks VS Code to reindent`() {
    val session = Session("one\ntwo\nthree")

    session.type("Vj=")

    assertTrue(
      VsCodeCommands.REINDENT_SELECTED_LINES in executed(),
      "the reindent command should have been sent, got ${executed()}",
    )
  }

  /**
   * `==`, `=j` and `=ap` reach the same seam through `autoIndentMotion`, so the operator forms are
   * fixed by the same change - and would have thrown in exactly the same way before it.
   */
  @Test
  fun `test the operator forms reindent too`() {
    for (keys in listOf("==", "=j", "=ap")) {
      val session = Session("one\ntwo\nthree")

      session.type(keys)

      assertEquals(emptyList(), session.sink.errors, "`$keys` should not have reported a failure")
      assertTrue(VsCodeCommands.REINDENT_SELECTED_LINES in executed(), "`$keys` should have sent the command")
    }
  }

  // ---- where Vim leaves the caret ------------------------------------------------------------

  /**
   * Vim puts the caret on the first non-blank of the first line of the range, not at column zero
   * and not where the selection ended. The stub does not re-indent, so the leading blanks here are
   * the ones the text started with - which is what makes the difference visible at all.
   */
  @Test
  fun `test the caret lands on the first non-blank of the first line`() {
    val session = Session("zero\n    one\n    two\nthree")

    session.type("jVj=")

    assertEquals("zero\n    one\n    two\nthree", session.content, "the stub reindents nothing")
    assertEquals(9, session.caretOffset, "on the `o` of `one`, past its four spaces")
  }

  @Test
  fun `test a line with no indent leaves the caret at its start`() {
    val session = Session("zero\none\ntwo")

    session.type("jV=")

    assertEquals(5, session.caretOffset)
  }

  // ---- what VS Code is handed --------------------------------------------------------------

  /**
   * The command reads the focused editor's selection and takes no argument, so the range has to be
   * handed over as a selection first. Checked directly, because the next keystroke's caret flush
   * overwrites it - correctly, and too soon for an assertion after the fact.
   */
  @Test
  fun `test the range is handed over as a VS Code selection`() {
    val session = Session("one\ntwo\nthree")
    val editor = session.host.editorFor(session.fake) as VsCodeEditor

    editor.selectForHostCommand(listOf(com.maddyhome.idea.vim.common.TextRange(4, 11)))

    assertEquals(1, session.fake.selections.size)
    val selection = session.fake.selections[0]
    assertEquals(1, selection.anchor.line, "starts on the second line")
    assertEquals(2, selection.active.line, "ends on the third")
  }

  @Test
  fun `test an empty range asks for nothing`() {
    val session = Session("one\ntwo")
    val editor = session.host.editorFor(session.fake) as VsCodeEditor

    editor.selectForHostCommand(emptyList())

    assertEquals(emptyList(), executed(), "no command should have been sent")
  }
}

/** The stub records every command it was asked for; this is that list, and the way to clear it. */
private fun commandsExecuted(): dynamic = js("require('vscode')").commands.executed

private fun executed(): List<String> {
  val raw = commandsExecuted()
  return (0 until (raw.length as Int)).map { raw[it] as String }
}
