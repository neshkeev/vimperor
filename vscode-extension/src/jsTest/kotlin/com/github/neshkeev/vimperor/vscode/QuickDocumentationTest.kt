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
 * `K`, which this host had no answer for at all - the key was simply not declared, so it did
 * nothing rather than failing.
 *
 * Vim runs the program named by `'keywordprg'`, `man` by default. IdeaVim substitutes IntelliJ's
 * Quick Documentation, and this substitutes VS Code's hover: in an editor that already knows what
 * the symbol under the caret is, that is what pressing `K` is for. All three are the same key
 * answering "what is this thing" with whatever the host has.
 */
class QuickDocumentationTest {

  private class Session(text: String = "one two\n") {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      commandsExecuted().length = 0
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    val caret: Int get() = host.editorFor(fake).primaryCaret().offset
    val content: String get() = fake.document.content
  }

  @Test
  fun `test K asks VS Code for the hover`() {
    val session = Session()

    session.type("K")

    assertEquals(listOf(VsCodeCommands.SHOW_HOVER), executed())
  }

  /** `K` is a read-only command: it must not move the caret or touch the text. */
  @Test
  fun `test K leaves the buffer and the caret alone`() {
    val session = Session("one two\n")

    session.type("wK")

    assertEquals(4, session.caret, "still on `two`")
    assertEquals("one two\n", session.content)
  }

  /**
   * It keeps IdeaVim's action id, which is what a borrowed config would name in a `sethandler` line
   * or an `<Action>` mapping.
   */
  @Test
  fun `test it is registered under the id IdeaVim gives it`() {
    Session()

    val ids = VsCodeCommandProvider.getCommands().map { it.actionId }
    assertTrue("VimQuickJavaDoc" in ids, "got $ids")
  }
}

private fun commandsExecuted(): dynamic = js("require('vscode')").commands.executed

private fun executed(): List<String> {
  val raw = commandsExecuted()
  return (0 until (raw.length as Int)).map { raw[it] as String }
}
