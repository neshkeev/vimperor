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
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Blocks and quotes - `di(`, `ci{`, `da[`, `%`, `ci"`.
 *
 * These are the text objects that need to know something about the text they are scanning. The
 * engine asks `injector.psiService` whether an offset is inside a string or a comment, so that a
 * bracket written inside one is not mistaken for a bracket in the code. IdeaVim answers from
 * IntelliJ's syntax tree. This host had no answer at all - the service was a `TODO()`, and so every
 * one of these crashed on the first bracket.
 *
 * That is why these tests are here rather than among the motion tests: they are the first thing to
 * ask the question, and the crash they found was nothing to do with `g@`, which merely walked into
 * it first.
 */
class VsCodeTextObjectTest {

  private class Session(text: String, caretOffset: Int = 0) {
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

  // Brackets.

  @Test
  fun `test di paren deletes what the parentheses hold`() {
    assertEquals("call()", type("call(one, two)", "di(", caretOffset = 6))
  }

  @Test
  fun `test da paren deletes the parentheses too`() {
    assertEquals("call", type("call(one, two)", "da(", caretOffset = 6))
  }

  @Test
  fun `test ci brace replaces the body of a block`() {
    assertEquals("if {x}", type("if {body}", "ci{x<Esc>", caretOffset = 5))
  }

  @Test
  fun `test di bracket works from the bracket itself`() {
    assertEquals("a[]b", type("a[one]b", "di[", caretOffset = 1))
  }

  @Test
  fun `test di angle bracket`() {
    assertEquals("List<>", type("List<String>", "di<", caretOffset = 6))
  }

  @Test
  fun `test a nested block takes the innermost pair`() {
    assertEquals("f(g())", type("f(g(x))", "di(", caretOffset = 4))
  }

  // `%` - the same scan, used to jump rather than to select.

  @Test
  fun `test percent jumps to the matching paren`() {
    val session = Session("call(one, two)", caretOffset = 4)
    session.type("%")
    assertEquals(13, session.editor.primaryCaret().offset)
  }

  @Test
  fun `test percent jumps back from the closing paren`() {
    val session = Session("call(one, two)", caretOffset = 13)
    session.type("%")
    assertEquals(4, session.editor.primaryCaret().offset)
  }

  // Quotes. These never went through the psi service, and are here to say so: if they ever break,
  // it will not be for the same reason.

  @Test
  fun `test ci quote replaces the contents of a string`() {
    assertEquals("say \"x\" now", type("say \"hello\" now", "ci\"x<Esc>", caretOffset = 6))
  }

  @Test
  fun `test ca quote takes the quotes and the space after them`() {
    // `a"` takes the trailing space too, so the word after it closes up. :help a"
    assertEquals("say xnow", type("say \"hello\" now", "ca\"x<Esc>", caretOffset = 6))
  }

  // The rule the bracket scan leans on, asserted directly, because the way it fails is not a wrong
  // answer but a hang: `findBlock` skips a string by jumping to the far end of the range it is
  // handed, so a range that has not begun yet sends a backwards scan forwards, back on to the same
  // bracket, for ever. The test below found that by not finishing, which is an expensive way to be
  // told, so the rule it depends on is asserted first and fails in a second instead.

  @Test
  fun `test an offset before a string on the line is not inside one`() {
    val session = Session("f(\"s\", x)")
    assertNull(injector.psiService.getDoubleQuotedString(session.editor, 1, false))
  }

  @Test
  fun `test an offset inside a string is inside one`() {
    val range = injector.psiService.getDoubleQuotedString(Session("f(\"s\", x)").editor, 3, false)
    assertEquals(2, range?.startOffset)
    assertEquals(5, range?.endOffset)
  }

  @Test
  fun `test the inner range leaves the quotes out`() {
    val range = injector.psiService.getDoubleQuotedString(Session("f(\"s\", x)").editor, 3, true)
    assertEquals(3, range?.startOffset)
    assertEquals(4, range?.endOffset)
  }
  // A bracket written inside a string is text, not a bracket. This is the whole reason the engine
  // asks about strings at all, and the only one of these a bracket-counting scan gets wrong.

  @Test
  fun `test a paren inside a string is not a paren`() {
    assertEquals("f()", type("f(\"(\", x)", "di(", caretOffset = 7))
  }
}
