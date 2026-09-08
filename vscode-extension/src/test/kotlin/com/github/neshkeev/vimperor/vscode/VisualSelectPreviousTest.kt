/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.state.mode.selectionType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `gv` after an ex command has rewritten the lines it was run over.
 *
 * Written because upstream fixed a bug here that this host does not have, and the only way to say
 * that honestly is to assert the behaviour. IdeaVim adjusted the remembered selection from *both*
 * `updateMarksFromInsert` and `updateMarksFromDelete`, so a replacement - which is a delete and an
 * insert - moved it twice, and `gv` after `:sort` came back wrong. This host adjusts it from
 * neither: its marks ride on live markers, and `lastSelectionInfo` is buffer positions that a
 * reordering of the same lines does not move.
 *
 * So there is nothing to port, and there was nothing asserting that. Now there is.
 */
class VisualSelectPreviousTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)

    fun ex(command: String) {
      type(":$command")
      key("<CR>")
    }

    val editor get() = host.editorFor(fake)
    val content: String get() = fake.document.content
    val selection: IntRange
      get() = editor.primaryCaret().let { it.selectionStart..it.selectionEnd }
  }

  /** The reported case: `:sort` reorders the lines and `gv` comes back to the same three. */
  @Test
  fun `test gv after sort selects the same lines`() {
    val session = Session("c\nb\na\nz\n")
    session.key("<Esc>")
    session.type("Vjj")

    session.ex("sort")
    session.type("gv")

    assertEquals("a\nb\nc\nz\n", session.content, "the first three lines were sorted")
    assertEquals(0..6, session.selection, "the first three lines are selected again")
    assertEquals(SelectionType.LINE_WISE, session.editor.mode.selectionType)
  }

  /** A substitute over the range rewrites every line in place; `gv` still covers them. */
  @Test
  fun `test gv after a substitute over the selection`() {
    val session = Session("one\ntwo\nthree\n")
    session.key("<Esc>")
    session.type("Vj")

    session.ex("'<,'>s/^/x/")
    session.type("gv")

    assertEquals("xone\nxtwo\nthree\n", session.content)
    // `xone\n` and `xtwo\n` - ten characters, the trailing newline included, the same way the
    // three lines above come to six.
    assertEquals(0..10, session.selection, "the first two lines, now a character longer each")
  }
}
