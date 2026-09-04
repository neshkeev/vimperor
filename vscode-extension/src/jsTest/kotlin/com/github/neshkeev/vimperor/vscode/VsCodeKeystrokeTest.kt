/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vim keystrokes, on a VS Code document.
 *
 * The engine already ran headlessly and in a JavaScript runtime; what is new here is the host. A
 * key arrives, `KeyHandler` maps it, builds a command and runs the handler, the handler mutates a
 * buffer VS Code has not seen yet, and the flush turns all of it into one edit on the document.
 *
 * Which is to say: this is the first test where a Vim command changes a VS Code document.
 */
class VsCodeKeystrokeTest {

  private class Session(text: String, caretOffset: Int) {
    val fake = FakeEditor(text)
    val editor: VsCodeEditor

    init {
      injector = VsCodeInjector().also { it.register(VsCodeEditor(fake)) }
      // The engine owns the builtin command trie but does not fill it; without this the key
      // handler recognises nothing. See `VsCodeInjector.keyGroup`.
      engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      VsCodeCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      injector.functionService.registerHandlers()

      editor = injector.editorGroup.getEditors().first() as VsCodeEditor
      editor.primaryCaret().moveToOffsetNative(caretOffset)

      // `KeyHandler` is a singleton and its state outlives a test. Without this, one test that
      // throws mid-command leaves a half-built command behind and the next test's keys vanish into
      // it - which reads as a broken command rather than as a broken neighbour.
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

  @Test
  fun `test x deletes the character under the caret`() {
    assertEquals("bc", type("abc", "x"))
  }

  @Test
  fun `test a count repeats the deletion`() {
    assertEquals("c", type("abc", "2x"))
  }

  @Test
  fun `test dw deletes a word`() {
    assertEquals("two three", type("one two three", "dw"))
  }

  @Test
  fun `test x reaches the document as a one-character edit`() {
    // Not just the right text: the right edit. A Vim command that rewrote the file would pass an
    // assertion on the content and cost the undo stack, decorations and folding on every keystroke.
    val session = Session("the quick brown fox", 4)
    session.type("x")

    assertEquals("the uick brown fox", session.fake.document.content)
    assertEquals(listOf(RecordedEdit(4, 5, "")), session.fake.recordedEdits)
  }

  @Test
  fun `test the caret VS Code ends up with is the one Vim moved`() {
    val session = Session("one two three", 0)
    session.type("dw")

    assertEquals("two three", session.fake.document.content)
    assertEquals(0, session.fake.selection.active.character)
  }

  @Test
  fun `test a whole command reaches the document as a single edit`() {
    // `2x` deletes twice inside the engine. VS Code hears once, which is also what makes one
    // keystroke one undo step without the CommandProcessor wrapper IntelliJ needs.
    val session = Session("abcdef", 0)
    session.type("2x")

    assertEquals("cdef", session.fake.document.content)
    assertEquals(1, session.fake.recordedEdits.size)
  }

  /**
   * The column `j` and `k` come back to.
   *
   * Vim remembers the column you were aiming for, so travelling down through a short line and out
   * the other side puts you back where you started rather than at the short line's end. IntelliJ's
   * caret keeps that value itself and IdeaVim only overrides it; this host has to keep it, and for
   * a while kept it badly - the remembered column was whatever the last vertical motion had left
   * there, so the first `k` after any other movement went to column zero. See
   * [VsCodeCaret.vimLastColumn].
   */
  @Test
  fun `test k keeps the column when nothing has moved vertically yet`() {
    val session = Session("one\ntwo", 5)
    session.type("k")
    assertEquals(1, session.editor.primaryCaret().offset)
  }

  @Test
  fun `test k after a horizontal motion uses the new column`() {
    val session = Session("abcd\nefgh", 5)
    session.type("llk")
    assertEquals(2, session.editor.primaryCaret().offset)
  }

  @Test
  fun `test a short line does not shorten the remembered column`() {
    // Down onto `x`, which has no column 3, and down again - Vim comes back to column 3.
    val session = Session("abcd\nx\nefgh", 3)
    session.type("j")
    assertEquals(5, session.editor.primaryCaret().offset)
    session.type("j")
    assertEquals(10, session.editor.primaryCaret().offset)
  }

  /**
   * A column past the end of a line, which is how `j` asks for the column it is aiming for.
   *
   * The conversion used to add the column to the line's start and clamp only to the end of the
   * *file*, so a column that overshot a short line came back as an offset on the line below - and
   * `j` skipped the short line entirely rather than landing on it. See
   * [VsCodeEditor.bufferPositionToOffset].
   */
  @Test
  fun `test a column past the end of a line clamps to that line`() {
    val session = Session("abcd\nx\nefgh", 0)
    val editor = session.editor
    assertEquals(6, editor.bufferPositionToOffset(BufferPosition(1, 3)))
    assertEquals(6, editor.bufferPositionToOffset(BufferPosition(1, 1)))
    assertEquals(5, editor.bufferPositionToOffset(BufferPosition(1, 0)))
    assertEquals(11, editor.bufferPositionToOffset(BufferPosition(2, 99)))
  }

  // Enter, in Normal mode: down a line, to the first non-blank. Before the host could answer "no
  // live template is running" this key threw, which made an ordinary keypress take the plugin down.

  @Test
  fun `test Enter moves down to the first non-blank`() {
    val session = Session("one\n    two\nthree", 0)
    session.type("<CR>")
    assertEquals(8, session.editor.primaryCaret().offset)
  }

  @Test
  fun `test Enter with a count moves that many lines`() {
    val session = Session("one\n    two\n  three", 0)
    session.type("2<CR>")
    // Two lines down is "  three", whose first non-blank is the `t` at 14.
    assertEquals(14, session.editor.primaryCaret().offset)
  }

  @Test
  fun `test Enter on the last line stays put`() {
    val session = Session("one\ntwo", 4)
    session.type("<CR>")
    assertEquals(4, session.editor.primaryCaret().offset)
  }
}

/**
 * The caret's shape, which is how Vim says which mode you are in.
 *
 * Vim's own default `guicursor` is `n-v-c:block,i-ci:ver25,r-cr:hor20` - a block in Normal, Visual
 * and on the command line, a bar in Insert, an underline in Replace - and a Vim user reads it a
 * hundred times a minute without looking at the status bar. It is the one editor option this
 * emulator has any business writing.
 */
class CursorStyleTest {

  private val host = VimHost().also { it.start() }

  @Test
  fun `test normal and visual modes draw a block`() {
    assertEquals(TextEditorCursorStyle.Block, host.cursorStyleFor("NORMAL"))
    assertEquals(TextEditorCursorStyle.Block, host.cursorStyleFor("VISUAL"))
    assertEquals(TextEditorCursorStyle.Block, host.cursorStyleFor("VISUAL BLOCK"))
    assertEquals(TextEditorCursorStyle.Block, host.cursorStyleFor("SELECT"))
  }

  /** A block on the command line too, which is where Vim leaves it while `:` is being typed. */
  @Test
  fun `test the command line draws a block`() {
    assertEquals(TextEditorCursorStyle.Block, host.cursorStyleFor("COMMAND"))
  }

  /** Operator-pending is not a mode you type in - `d` is a command half-finished. */
  @Test
  fun `test operator pending draws a block`() {
    assertEquals(TextEditorCursorStyle.Block, host.cursorStyleFor("OP PENDING"))
  }

  @Test
  fun `test insert draws a bar and replace an underline`() {
    assertEquals(TextEditorCursorStyle.Line, host.cursorStyleFor("INSERT"))
    assertEquals(TextEditorCursorStyle.Underline, host.cursorStyleFor("REPLACE"))
  }
}
