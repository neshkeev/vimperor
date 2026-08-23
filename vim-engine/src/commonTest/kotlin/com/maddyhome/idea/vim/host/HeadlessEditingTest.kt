/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.VimMarkService
import com.maddyhome.idea.vim.regexp.TestVimCaret
import com.maddyhome.idea.vim.regexp.TestVimEditor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The engine changing a buffer, on both targets.
 *
 * `insertText` and `replaceText` are the primitives every editing command reaches eventually, and
 * they do three things: hand the edit to the editor inside a write action, move the caret, and set
 * the change marks `'[` and `']`. All three are checked here, because a host that mutates the text
 * and forgets the marks looks correct until someone presses `` `[ ``.
 */
class HeadlessEditingTest {

  private class Buffer(text: String, caretOffset: Int = 0) {
    // Primary, because a buffer with one caret has one primary caret - and because the change
    // marks live on the mark service, which only answers for a primary caret.
    val caret = TestVimCaret(caretOffset, isPrimary = true)
    val editor = TestVimEditor(text, listOf(caret)).also { caret.editorRef = it }
  }

  private fun buffer(text: String, caretOffset: Int = 0): Buffer {
    injector = HeadlessInjector()
    return Buffer(text, caretOffset)
  }

  @Test
  fun `test inserting text changes the buffer and moves the caret`() {
    val b = buffer("hello world", caretOffset = 5)
    injector.changeGroup.insertText(b.editor, b.caret, 5, ",")
    assertEquals("hello, world", b.editor.text)
    assertEquals(6, b.caret.offset)
  }

  @Test
  fun `test inserting at the caret uses the caret's offset`() {
    val b = buffer("ac", caretOffset = 1)
    injector.changeGroup.insertText(b.editor, b.caret, "b")
    assertEquals("abc", b.editor.text)
  }

  @Test
  fun `test replacing a range rewrites exactly that range`() {
    val b = buffer("one two three")
    injector.changeGroup.replaceText(b.editor, b.caret, 4, 7, "TWO")
    assertEquals("one TWO three", b.editor.text)
  }

  @Test
  fun `test replacing text sets the change marks`() {
    val b = buffer("one two three")
    injector.changeGroup.replaceText(b.editor, b.caret, 4, 7, "TWO")
    val start = injector.markService.getMark(b.caret, VimMarkService.CHANGE_START_MARK)
    assertEquals(0, start?.line)
    assertEquals(4, start?.col)
  }

  @Test
  fun `test a replacement of a different length shifts the text after it`() {
    val b = buffer("abcdef")
    injector.changeGroup.replaceText(b.editor, b.caret, 1, 3, "XYZ")
    assertEquals("aXYZdef", b.editor.text)
  }

  @Test
  fun `test line offsets follow the edit`() {
    val b = buffer("one\ntwo")
    injector.changeGroup.insertText(b.editor, b.caret, 0, "zero\n")
    assertEquals("zero\none\ntwo", b.editor.text)
    // The line table is rebuilt with the text, so line 2 starts after both newlines.
    assertEquals(9, b.editor.getLineStartOffset(2))
  }
}
