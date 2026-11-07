/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.host
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.handler.Motion
import com.maddyhome.idea.vim.host.HeadlessExecutionContext
import com.maddyhome.idea.vim.host.HeadlessFile
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.key.KeySource
import com.maddyhome.idea.vim.mark.Jump
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * `<C-O>` across files.
 *
 * A jump into another file moves *that* caret and then has to return [Motion.NoMotion].
 * [Motion.Error] is what [com.maddyhome.idea.vim.handler.MotionActionHandler] beeps on, so returning
 * it after a jump that worked reported an error. A file the host cannot open is still an error,
 * and a jump that stays in this file is still an offset the handler applies here.
 */
class HeadlessJumpMotionTest {

  private class Session {
    val caret = TestVimCaret(2, isPrimary = true)
    val editor = TestVimEditor("aaaa\nbbbb\ncccc", listOf(caret), path = "/work/here.txt").also { caret.editorRef = it }

    val otherCaret = TestVimCaret(0, isPrimary = true)
    val other = TestVimEditor("one\ntwo\nthree", listOf(otherCaret), path = "/work/there.txt").also {
      otherCaret.editorRef = it
    }

    private val files: HeadlessFile get() = injector.file as HeadlessFile

    /** The other buffer is open, so [com.maddyhome.idea.vim.api.VimFile.selectEditor] can land in it. */
    fun openOther() {
      files.openEditors[other.getPath()] = other
    }

    fun record(jump: Jump) {
      injector.jumpService.addJump(editor.projectId, jump, true)
    }

    fun press(keys: String) {
      injector.messages.clearError()
      val handler = KeyHandler.getInstance()
      handler.reset(editor)
      val state = handler.keyHandlerState
      for (stroke in injector.parser.parseKeys(keys)) {
        handler.handleKey(editor, stroke, KeySource.TYPED, HeadlessExecutionContext, state)
      }
    }
  }

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
    return Session()
  }

  @Test
  fun `test control-O onto another open file moves that caret and is not an error`() {
    val s = session()
    s.openOther()
    s.record(Jump(1, 2, s.other.getPath(), "file"))

    val motion = injector.motion.moveCaretToJump(s.editor, s.caret, -1)

    assertEquals(Motion.NoMotion, motion)
    assertEquals(1 to 2, s.otherCaret.lineAndColumn())
    assertEquals(2, s.caret.offset, "the editor the key was typed in is not moved")
    assertFalse(injector.messages.isError())
  }

  @Test
  fun `test control-O onto another open file does not beep`() {
    val s = session()
    s.openOther()
    s.record(Jump(1, 2, s.other.getPath(), "file"))

    s.press("<C-O>")

    assertEquals(1 to 2, s.otherCaret.lineAndColumn())
    assertEquals(2, s.caret.offset, "the editor the key was typed in is not moved")
    assertFalse(injector.messages.isError())
  }

  @Test
  fun `test a jump to a file that cannot be opened is an error`() {
    val s = session()
    s.record(Jump(0, 0, "/work/missing.txt", "file"))

    assertEquals("/work/missing.txt", injector.jumpService.getJumps(s.editor.projectId).single().filepath)
    val motion = injector.motion.moveCaretToJump(s.editor, s.caret, -1)
    assertEquals(Motion.Error, motion)
    assertEquals(2, s.caret.offset)

    // getJump walked the spot past that entry, so `<C-O>` needs its own or it beeps for an empty list.
    s.record(Jump(0, 0, "/work/missing.txt", "file"))
    s.press("<C-O>")
    assertEquals(2, s.caret.offset)
    assertEquals(true, injector.messages.isError())
  }

  @Test
  fun `test a jump that stays in this file moves this caret and is not an error`() {
    val s = session()
    s.record(Jump(2, 1, s.editor.getPath(), "file"))

    s.press("<C-O>")

    assertEquals(2 to 1, s.caret.lineAndColumn())
    assertEquals(0, s.otherCaret.offset, "the other file is not the jump")
    assertFalse(injector.messages.isError())
  }

  @Test
  fun `test a jump that stays in this file returns an offset`() {
    val s = session()
    s.record(Jump(2, 1, s.editor.getPath(), "file"))

    val motion = injector.motion.moveCaretToJump(s.editor, s.caret, -1)

    assertIs<Motion.AbsoluteOffset>(motion)
    assertEquals(s.editor.bufferPositionToOffset(BufferPosition(2, 1, false)), motion.offset)
    assertFalse(injector.messages.isError())
  }
}

/** [BufferPosition] does not implement equals, so a pair is what a test can compare. */
private fun TestVimCaret.lineAndColumn(): Pair<Int, Int> {
  val position = getBufferPosition()
  return position.line to position.column
}
