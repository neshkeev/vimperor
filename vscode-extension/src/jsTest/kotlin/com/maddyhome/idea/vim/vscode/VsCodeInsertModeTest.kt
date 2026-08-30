/*
 * Copyright 2003-2026 The IdeaVim authors
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
 * Insert mode, which is where a host stops being a spectator.
 *
 * Normal-mode commands compute a change and hand it over once. Insert mode is a key at a time, and
 * IdeaVim's answer on IntelliJ - hand each character to the IDE's own typed-action handler, so its
 * auto-indent and bracket closing run - is unavailable here: VS Code's equivalent is a command, and
 * commands are asynchronous. So characters go into the buffer directly, which is what Vim itself
 * does, and the editor's own typing features are a later decision rather than a missing piece.
 */
class VsCodeInsertModeTest {

  private class Session(text: String, caretOffset: Int) {
    val fake = FakeEditor(text)
    val editor: VsCodeEditor

    init {
      injector = VsCodeInjector().also { it.register(VsCodeEditor(fake)) }
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

  @Test
  fun `test i inserts before the caret`() {
    assertEquals("Xabc", type("abc", "iX<Esc>"))
  }

  @Test
  fun `test a inserts after the caret`() {
    assertEquals("aXbc", type("abc", "aX<Esc>"))
  }

  @Test
  fun `test A appends at the end of the line`() {
    assertEquals("abcX", type("abc", "AX<Esc>"))
  }

  @Test
  fun `test I inserts before the first non-blank`() {
    assertEquals("  Xabc", type("  abc", "IX<Esc>", caretOffset = 4))
  }

  @Test
  fun `test typing several characters keeps them in order`() {
    assertEquals("hello world", type(" world", "ihello<Esc>"))
  }

  @Test
  fun `test o opens a line below`() {
    assertEquals("one\ntwo", type("one", "otwo<Esc>"))
  }

  @Test
  fun `test O opens a line above`() {
    assertEquals("one\ntwo", type("two", "Oone<Esc>"))
  }

  @Test
  fun `test cw changes a word`() {
    assertEquals("ONE two", type("one two", "cwONE<Esc>"))
  }

  @Test
  fun `test backspace deletes what was just typed`() {
    assertEquals("ab", type("", "iabX<BS><Esc>"))
  }

  @Test
  fun `test a whole insert session reaches the document as one edit`() {
    // Every keystroke mutates the buffer; VS Code hears once, at the end. That is what makes an
    // insert session a single undo step without any of the grouping IntelliJ needs a wrapper for.
    val session = Session("", 0)
    session.type("ihello<Esc>")

    assertEquals("hello", session.fake.document.content)
    assertEquals(1, session.fake.recordedEdits.size)
  }

  @Test
  fun `test Escape leaves the caret where Vim puts it`() {
    // Vim steps the caret back one on leaving insert mode, so `x` next would delete the last
    // character typed rather than the one after it.
    val session = Session("", 0)
    session.type("iabc<Esc>")

    assertEquals("abc", session.fake.document.content)
    assertEquals(2, session.editor.primaryCaret().offset)
  }

  /**
   * `<CR>` in Insert mode, which for a long time did nothing at all here.
   *
   * The engine does not insert the newline itself: [VimChangeGroupBase.processEnter] asks the key
   * group what the host has bound to Enter and runs that, because IntelliJ's Enter knows about
   * indenting and brace matching and Vim would rather defer to it. This host answered "nothing",
   * so Insert mode could type every character except a line break - and every test here typed on
   * one line, so nothing noticed.
   */
  @Test
  fun `test Enter in insert mode inserts a line break`() {
    assertEquals("one\ntwo", type("", "ione<CR>two<Esc>"))
  }

  @Test
  fun `test Enter splits the line at the caret`() {
    assertEquals("ab\ncd", type("abcd", "lli<CR><Esc>"))
  }

  @Test
  fun `test Enter opens a line when the caret is at the end`() {
    assertEquals("ab\n", type("ab", "A<CR><Esc>"))
  }
}
