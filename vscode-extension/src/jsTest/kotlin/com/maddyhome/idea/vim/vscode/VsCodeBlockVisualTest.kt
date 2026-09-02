/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `<C-V>` - Visual mode over a rectangle rather than a run of text.
 *
 * This is the last large thing on the port's inventory, and the only Vim mode that needs the editor
 * to have more than one caret. The engine's model is IntelliJ's: a block selection is N carets with
 * N one-line selections, rebuilt from scratch on every motion, with one of them designated primary
 * and carrying the block's anchor. VS Code has the same idea under a different name - `selections`
 * is an array and the first entry is the primary - so the model ports; what does not port is the
 * assumption that the primary caret is the first one in the document, because after `<C-V>k` it is
 * the last.
 */
class VsCodeBlockVisualTest {

  private class Session(text: String, caretOffset: Int = 0) {
    val fake = FakeEditor(text)
    val editor: VsCodeEditor

    init {
      // Assigned before anything is registered, which is not a style choice. `register` initialises
      // the editor's local options and the option group reaches for the global `injector` while
      // doing it - so building the whole expression first and assigning afterwards left this class
      // unable to run on its own, and passing only because some earlier test had installed one.
      val vimInjector = VsCodeInjector()
      injector = vimInjector
      vimInjector.register(VsCodeEditor(fake))
      engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      VsCodeCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      injector.functionService.registerHandlers()

      editor = injector.editorGroup.getEditors().first() as VsCodeEditor
      editor.primaryCaret().moveToOffsetNative(caretOffset)
      KeyHandler.getInstance().fullReset(editor)
    }

    fun type(keys: String) {
      val handler = KeyHandler.getInstance()
      val state = handler.keyHandlerState
      for (stroke in injector.parser.parseKeys(keys)) {
        handler.handleKey(editor, stroke, VsCodeExecutionContext, state)
      }
      editor.flush()
    }
  }

  private fun type(text: String, keys: String, caretOffset: Int = 0): String {
    val session = Session(text, caretOffset)
    session.type(keys)
    return session.fake.document.content
  }

  private val three = "one two\nthree four\nfive six"

  // Deleting a rectangle.

  @Test
  fun `test blockwise delete takes a column out of every line`() {
    assertEquals("ne two\nhree four\nive six", type(three, "<C-V>jjd"))
  }

  @Test
  fun `test blockwise delete takes a rectangle out of every line`() {
    assertEquals(" two\nee four\ne six", type(three, "<C-V>jjlld"))
  }

  @Test
  fun `test blockwise delete from the middle of the lines`() {
    assertEquals("on two\nthee four\nfie six", type(three, "<C-V>jjd", caretOffset = 2))
  }

  @Test
  fun `test a block drawn upwards covers the same rectangle`() {
    val session = Session(three, caretOffset = 19)
    session.type("<C-V>kkd")
    assertEquals("ne two\nhree four\nive six", session.fake.document.content)
  }

  // Inserting down the side of one.

  @Test
  fun `test blockwise insert prepends to every line`() {
    assertEquals("Xone two\nXthree four\nXfive six", type(three, "<C-V>jjIX<Esc>"))
  }

  @Test
  fun `test blockwise append adds to the end of every line`() {
    assertEquals("one two;\nthree four;\nfive six;", type(three, "<C-V>jj\$A;<Esc>"))
  }

  @Test
  fun `test blockwise change replaces the rectangle on every line`() {
    assertEquals("X two\nXee four\nXe six", type(three, "<C-V>jjllcX<Esc>"))
  }

  // Ragged edges, which is where a rectangle stops being a rectangle.

  @Test
  fun `test a line too short for the block contributes what it has`() {
    assertEquals("er\n\ner", type("longer\nab\nlonger", "<C-V>jjllld"))
  }

  // The carets themselves, which is what makes this different from every other mode.

  @Test
  fun `test the block reaches VS Code as one selection per line`() {
    val session = Session(three)
    session.type("<C-V>jjll")
    assertEquals(3, session.fake.selections.size)
  }

  @Test
  fun `test the primary caret is the corner the motion moved`() {
    val session = Session(three)
    session.type("<C-V>jj")
    assertEquals(2, session.editor.primaryCaret().getBufferPosition().line)
  }

  @Test
  fun `test a block drawn upwards makes the top line primary`() {
    val session = Session(three, caretOffset = 19)
    session.type("<C-V>kk")
    assertEquals(0, session.editor.primaryCaret().getBufferPosition().line)
  }

  @Test
  fun `test VS Code is told which caret is primary`() {
    val session = Session(three)
    session.type("<C-V>jjll")
    // VS Code takes its primary from `selections[0]`, so the block's moving corner has to be first
    // even though it is the last caret in the document.
    assertEquals(2, session.fake.selections[0].active.line)
  }

  @Test
  fun `test leaving blockwise visual leaves one caret`() {
    val session = Session(three)
    session.type("<C-V>jj<Esc>")
    assertEquals(1, session.editor.carets().size)
    assertEquals(1, session.fake.selections.size)
  }

  // Yank and put, which is the register carrying a rectangle rather than a run of text.

  @Test
  fun `test blockwise yank and put inserts a rectangle`() {
    assertEquals("oone two\ntthree four\nffive six", type(three, "<C-V>jjyP"))
  }

  /**
   * `<C-V>j$` - the block that reaches the end of every line, however ragged they are.
   *
   * Vim's `curswant` is a sentinel here rather than a column: `$` sets it larger than any line, and
   * the block's right edge follows it to each line's own end. `EngineVisualGroup` asks for exactly
   * that, with `if (lastColumn >= VimMotionGroupBase.LAST_COLUMN)`, so a host that lets the sentinel
   * be replaced by the caret's actual column turns "to the end of each line" into "to column four".
   *
   * The sentinel is written out rather than imported from `VimMotionGroupBase`: naming that class
   * here loads it when this test module does, and it reaches for `injector` before any test has
   * installed one.
   *
   * The other order - `$` and then `<C-V>` - is a corner this host does not keep. Vim carries
   * `curswant` into the block; here entering block Visual moves the caret to lay the block out, and
   * the remembered column is recomputed at the new position by the rule IdeaVim uses. It is the
   * documented idiom that matters, and this is it.
   */
  @Test
  fun `test block visual then dollar reaches the end of every line`() {
    val LAST_COLUMN = 9999
    val session = Session("one two\nthree\nfour five six", caretOffset = 0)

    session.type("<C-V>jj$")

    assertEquals(
      LAST_COLUMN,
      session.editor.primaryCaret().vimLastColumn,
      "the dollar motion inside a block should ask for the end of every line",
    )
    assertEquals(
      listOf(0 to 7, 8 to 13, 14 to 27),
      session.editor.carets().map { it.selectionStart to it.selectionEnd },
      "each line's selection should reach that line's own end",
    )
  }

  /**
   * A block drawn upwards over ragged lines does not widen as it goes.
   *
   * Reported from a real window, and the sharpest bug this port has had: every `k` widened the
   * block by another column, so a selection meant to be one character across came out as a
   * staircase. The remembered column is what a block's edges are drawn at, and a block's carets are
   * rebuilt from scratch on every motion - so a caret built for a line it had never been on had no
   * column of its own and answered with whatever its line clamped to, and the next motion drew the
   * block from *that*. IdeaVim never sees it, because it keeps the column against the editor and
   * every caret of a block shares one. This host seeds them all from the caret the block is being
   * drawn by, which is the same thing said differently.
   *
   * The expected values were taken from IdeaVim by running the same keys against it, and two things
   * in them are worth knowing.
   *
   * The block's edge follows the caret's clamp while it is over a line too short to hold it - 2..3
   * becomes 1..3 on the one-character line and 0..3 on the empty one - and comes *back* to 2..3 on
   * a line long enough. The recovery is `curswant` working; the narrowing is `blockToNativeSelection`
   * computing the block's corners from the two offsets and never consulting it, which is a
   * divergence from Vim. Measured against IdeaVim rather than assumed: the same keys give `[(1, 3)]`
   * there. It is shared, so it belongs in the engine and not in a workaround here.
   *
   * And a line too short to contribute still gets a caret here, where IdeaVim has none - `[(1, 1),
   * (1, 3)]` against `[(1, 3)]`. That is the same difference as the "carets the engine narrowed away
   * and this host kept" group in `known-fixture-failures.txt`.
   */
  @Test
  fun `test a block drawn upwards over ragged lines does not widen`() {
    val text = "aaaaaaaa\n\nb\ncccccccc"
    val session = Session(text, caretOffset = text.lastIndexOf("cccccccc") + 2)

    session.type("<C-V>")
    assertEquals(listOf(2 to 3), columnsOf(session), "one line, one column")

    session.type("k")
    assertEquals(listOf(1 to 1, 1 to 3), columnsOf(session), "clamped onto the one-character line")

    // The block narrows to what a short line can hold and *recovers* on a line long enough. Before
    // the fix the column was lost rather than borrowed, so it never came back.
    session.type("k")
    assertEquals(listOf(0 to 0, 0 to 1, 0 to 3), columnsOf(session), "the empty line drags it to column zero")

    session.type("k")
    assertEquals(
      listOf(2 to 3, 0 to 0, 1 to 1, 2 to 3),
      columnsOf(session),
      "and on a line long enough it is back to the column the user asked for",
    )
  }

  /** Each caret's selection as the columns it covers, which is what a block is described by. */
  private fun columnsOf(session: Session): List<Pair<Int, Int>> =
    session.editor.carets().map { caret ->
      session.editor.offsetToBufferPosition(caret.selectionStart).column to
        session.editor.offsetToBufferPosition(caret.selectionEnd).column
    }
}
