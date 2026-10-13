/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.intellij.vim.api.models.CaretId
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.thinapi.editor.EditorAccessorImpl
import com.maddyhome.idea.vim.thinapi.editor.caret.CaretTransactionImpl
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The editor accessor an extension gets inside a caret transaction, and the question it answers.
 *
 * `CaretTransactionImpl` already inherits `getLineStartOffset`, `getLineEndOffset` and `getLine`
 * through `EditorAccessor by EditorAccessorImpl(projectId)`, and it also declared its own copies.
 * The copies had drifted: `getLineEndOffset` dropped the `allowEnd` argument and always called the
 * one-argument form, so it behaved as `allowEnd = true` whatever the caller asked. The symptom is
 * one function disagreeing with itself depending on how deeply it is nested - the same call inside
 * `change { }` and inside `change { withPrimaryCaret { } }` gave different answers. The copies are
 * gone; the delegation is the one implementation. Ported from IdeaVim.
 *
 * This is the thin API, which in this fork is not a JetBrains-only surface: every bundled
 * `@VimPlugin` is written against `com.intellij.vim.api`, and `ReplaceWithRegisterNew` calls
 * `getLineEndOffset` on exactly this path - with `allowEnd = true`, which is why nothing here had
 * noticed.
 */
class CaretTransactionAccessorTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val editor: VsCodeEditor

    init {
      injector = VsCodeInjector().also { it.register(VsCodeEditor(fake)) }
      editor = injector.editorGroup.getEditors().first() as VsCodeEditor
      KeyHandler.getInstance().fullReset(editor)
    }

    /** Any non-null id: this host resolves a project to whichever editor has the focus. */
    val transaction: CaretTransactionImpl
      get() = CaretTransactionImpl("vimperor", CaretId(editor.primaryCaret().id))

    val accessor: EditorAccessorImpl get() = EditorAccessorImpl("vimperor")
  }

  @Test
  fun `test a caret transaction honours allowEnd when asked for a line's end`() {
    val session = Session("one two\nthree")

    assertEquals(
      7,
      session.transaction.getLineEndOffset(0, allowEnd = true),
      "allowEnd includes the line break's position",
    )
    assertEquals(
      6,
      session.transaction.getLineEndOffset(0, allowEnd = false),
      "without allowEnd the answer is the last character of the line",
    )
  }

  @Test
  fun `test it answers the same as the accessor it delegates to`() {
    val session = Session("one two\nthree")

    for (allowEnd in listOf(true, false)) {
      for (line in 0..1) {
        assertEquals(
          session.accessor.getLineEndOffset(line, allowEnd),
          session.transaction.getLineEndOffset(line, allowEnd),
          "nesting must not change the answer: line $line, allowEnd=$allowEnd",
        )
      }
    }
  }

  @Test
  fun `test the line it reports is still the line the accessor reports`() {
    val session = Session("one two\nthree")

    assertEquals(session.accessor.getLine(9).number, session.transaction.getLine(9).number)
    assertEquals(session.accessor.getLine(9).text, session.transaction.getLine(9).text)
    assertEquals(session.accessor.getLineStartOffset(1), session.transaction.getLineStartOffset(1))
  }
}
