/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `gq` and `gw`, which used to throw.
 *
 * `reformatCode` was a `TODO("reformatCode is an asynchronous command")`, and asynchronous was true
 * and was never a reason to leave it unbuilt - the same note `=` carried until `autoIndentRange`
 * was written. The host already holds the user's keys until a command lands.
 *
 * The interesting half is that this is deliberately **not** the command `=` uses. Vim's `=` changes
 * leading whitespace and nothing else, which is why `VsCodeCommands.REINDENT_SELECTED_LINES` exists
 * and why using a formatter for it would have been wrong. `gq` is *format*, and IdeaVim hands it
 * straight to the IDE's reformat - so it gets `editor.action.formatSelection`, whatever that does
 * for the language.
 *
 * What a test can check is the request, not the formatting: the stub host has no language rules and
 * reformats nothing. Whether VS Code's formatter is any good is VS Code's business; whether
 * Vimperor asks it the right question, over the right range, is this fork's.
 */
class ReformatTest {

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
  }

  @Test
  fun `test gq over a motion asks VS Code to format`() {
    val session = Session("one\ntwo\nthree")

    session.type("gqj")

    assertEquals(emptyList(), session.sink.errors)
    assertTrue(VsCodeCommands.FORMAT_SELECTION in executed(), "got ${executed()}")
  }

  @Test
  fun `test every form of it reaches the same command`() {
    // `gqq` and `gqgq` are the line forms; `gw` is the one that puts the caret back.
    for (keys in listOf("gqq", "gqgq", "gqap", "gwj", "gww")) {
      val session = Session("one\ntwo\nthree")

      session.type(keys)

      assertEquals(emptyList(), session.sink.errors, "`$keys` reported a failure")
      assertTrue(VsCodeCommands.FORMAT_SELECTION in executed(), "`$keys` sent nothing")
    }
  }

  @Test
  fun `test gq over a visual selection formats too`() {
    val session = Session("one\ntwo\nthree")

    session.type("Vjgq")

    assertEquals(emptyList(), session.sink.errors)
    assertTrue(VsCodeCommands.FORMAT_SELECTION in executed())
  }

  /**
   * `=` must not have become a formatter along the way. The two keys mean different things in Vim
   * and now send different commands, which is the whole reason `reformatCode` and `autoIndentRange`
   * are separate.
   */
  @Test
  fun `test = still only reindents`() {
    val session = Session("one\ntwo\nthree")

    session.type("=j")

    assertTrue(VsCodeCommands.REINDENT_SELECTED_LINES in executed())
    assertTrue(VsCodeCommands.FORMAT_SELECTION !in executed(), "`=` is not a formatter")
  }
}

private fun commandsExecuted(): dynamic = js("require('vscode')").commands.executed

private fun executed(): List<String> {
  val raw = commandsExecuted()
  return (0 until (raw.length as Int)).map { raw[it] as String }
}
