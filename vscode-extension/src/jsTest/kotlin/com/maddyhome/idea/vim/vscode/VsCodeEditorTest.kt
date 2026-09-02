/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.BufferPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The editor the engine is handed, over a VS Code document.
 *
 * Offsets and line arithmetic look like busywork until you notice that every motion, every range
 * and every mark is expressed in them, and that they are computed over the *buffer* rather than the
 * document - so an off-by-one here is an off-by-one in every command at once.
 */
class VsCodeEditorTest {

  private fun editorOver(text: String): Pair<FakeEditor, VsCodeEditor> {
    val fake = FakeEditor(text)
    return fake to VsCodeEditor(fake)
  }

  @Test
  fun `test line offsets over the buffer`() {
    val (_, editor) = editorOver("one\ntwo\nthree")

    assertEquals(3, editor.nativeLineCount())
    assertEquals(0, editor.getLineStartOffset(0))
    assertEquals(3, editor.getLineEndOffset(0))
    assertEquals(4, editor.getLineStartOffset(1))
    assertEquals(7, editor.getLineEndOffset(1))
    assertEquals(8, editor.getLineStartOffset(2))
    assertEquals(13, editor.getLineEndOffset(2))
  }

  /**
   * This test used to assert two, and it was wrong.
   *
   * A file ending in a newline is two lines; an editor *buffer* ending in one shows a third, empty,
   * and lets a caret sit on it - IntelliJ and VS Code both. The engine is built on IntelliJ's
   * document and expects that line to exist. Nothing here noticed until IdeaVim's own fixtures were
   * replayed and seven of them disagreed at once, which is the whole reason for harvesting them:
   * this test and the code it tested were written by the same hand and agreed with each other.
   */
  @Test
  fun `test a trailing newline opens an empty last line`() {
    val (_, editor) = editorOver("one\ntwo\n")
    assertEquals(3, editor.nativeLineCount())
    assertEquals(8, editor.getLineStartOffset(2))
    assertEquals(8, editor.getLineEndOffset(2))
  }

  /**
   * `BufferPosition` is `Comparable` but declares no `equals`, so `==` on it is identity and
   * `assertEquals` on two of them always fails. Worth knowing before writing a host: the engine
   * never compares them that way, so nothing is broken by it, but a test that does will be.
   */
  private fun assertPosition(line: Int, column: Int, actual: BufferPosition) {
    assertEquals(line to column, actual.line to actual.column)
  }

  @Test
  fun `test offsets and positions agree in both directions`() {
    val (_, editor) = editorOver("one\ntwo\nthree")

    assertPosition(0, 0, editor.offsetToBufferPosition(0))
    assertPosition(1, 1, editor.offsetToBufferPosition(5))
    assertPosition(2, 4, editor.offsetToBufferPosition(12))
    assertEquals(5, editor.bufferPositionToOffset(BufferPosition(1, 1)))
    assertEquals(12, editor.bufferPositionToOffset(BufferPosition(2, 4)))
  }

  @Test
  fun `test line offsets follow the buffer rather than the document`() {
    // The whole reason the index is built over the buffer: after an edit and before its flush, the
    // document still has the old line structure, and a motion asking about lines would use it.
    val (fake, editor) = editorOver("one\ntwo")
    editor.replaceString(3, 4, "\nmiddle\n")

    assertEquals("one\nmiddle\ntwo", editor.text)
    assertEquals(3, editor.nativeLineCount(), "the buffer has three lines even though the document has two")
    assertEquals(4, editor.getLineStartOffset(1))
    assertEquals("one\ntwo", fake.document.content, "the document should not have moved yet")
  }

  @Test
  fun `test deleting through the editor reaches the document on flush`() {
    val (fake, editor) = editorOver("hello world")
    editor.deleteString(com.maddyhome.idea.vim.common.TextRange(5, 11))

    var applied: Boolean? = null
    editor.flush { applied = it }

    assertEquals(true, applied)
    assertEquals("hello", fake.document.content)
    assertEquals(listOf(RecordedEdit(5, 11, "")), fake.recordedEdits)
  }

  @Test
  fun `test adding a line opens it before the given one`() {
    val (_, editor) = editorOver("one\ntwo")
    val start = editor.addLine(1)

    assertEquals(4, start)
    assertEquals("one\n\ntwo", editor.text)
  }

  @Test
  fun `test the caret comes from the VS Code selection`() {
    val fake = FakeEditor("one\ntwo\nthree")
    fake.selections = arrayOf(Selection(Position(1, 2), Position(1, 2)))
    val editor = VsCodeEditor(fake)

    assertEquals(1, editor.carets().size)
    assertEquals(6, editor.primaryCaret().offset)
    assertTrue(editor.primaryCaret().isPrimary)
  }

  @Test
  fun `test every VS Code cursor becomes a caret, the first one primary`() {
    val fake = FakeEditor("one\ntwo\nthree")
    fake.selections = arrayOf(
      Selection(Position(0, 1), Position(0, 1)),
      Selection(Position(2, 3), Position(2, 3)),
    )
    val editor = VsCodeEditor(fake)

    assertEquals(listOf(1, 11), editor.carets().map { it.offset })
    assertEquals(listOf(true, false), editor.carets().map { it.isPrimary })
  }

  @Test
  fun `test where the engine left the carets is where VS Code puts them`() {
    val fake = FakeEditor("hello world")
    val editor = VsCodeEditor(fake)
    editor.primaryCaret().moveToOffsetNative(6)
    editor.replaceString(0, 5, "goodbye")

    editor.flush()

    assertEquals("goodbye world", fake.document.content)
    // Against the edited document: offset 6 is inside "goodbye", not where it was before the edit.
    assertEquals(0, fake.selection.active.line)
    assertEquals(6, fake.selection.active.character)
  }

  @Test
  fun `test the buffer has an identity, so marks are possible`() {
    // `VimMarkServiceBase` returns null outright for an editor with no virtual file and no path -
    // no `'[`, no `']`, no lowercase mark, and no error saying why. VS Code's URI is what every
    // buffer has, untitled ones included.
    val (_, editor) = editorOver("text")

    assertEquals("file:///test/buffer.txt", editor.getPath())
    assertEquals("file", editor.extractProtocol())
    assertNotNull(editor.getVirtualFile())
    assertEquals(editor.getPath(), editor.getVirtualFile().path, "marks are stored by one and read back by the other")
    assertEquals("txt", editor.getVirtualFile().extension)
  }
}
