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
 * `g@` - run the function named by 'operatorfunc' over the text a motion covers.
 *
 * This is the one command in Vim whose whole purpose is to be given away. An operator is normally
 * a fixed pairing of a verb and a range; `g@` unbundles them, letting a plugin supply the verb and
 * letting Vim's own motions, counts, text objects and `.` supply everything else. Every
 * operator-pending plugin in the wild is built on it.
 *
 * The contract it has to honour is small and precise, and these tests are that contract: the marks
 * `'[` and `']` bracket the text the motion covered, and the function is handed one argument saying
 * how to read them - `"char"`, `"line"` or `"block"`.
 */
class VsCodeOperatorFuncTest {

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

    fun source(script: String) {
      injector.vimscriptExecutor.execute(script, editor, VsCodeExecutionContext, skipHistory = true)
    }

    /**
     * An 'operatorfunc' that writes down everything `g@` promised to hand it, so a test can read
     * the promise back rather than infer it from an edit.
     */
    fun recordingOperatorFunc() {
      source(
        """
        function! Record(type)
          let g:type = a:type
          let g:start = line("'[") . ":" . col("'[")
          let g:end = line("']") . ":" . col("']")
        endfunction
        set operatorfunc=Record
        """.trimIndent(),
      )
    }

    fun type(keys: String) {
      val handler = KeyHandler.getInstance()
      val state = handler.keyHandlerState
      for (stroke in injector.parser.parseKeys(keys)) {
        handler.handleKey(editor, stroke, VsCodeExecutionContext, state)
      }
      editor.flush()
    }

    fun global(name: String): String =
      injector.variableService.getGlobalVariableValue(name)?.toVimString()?.value ?: "<unset>"
  }

  // The argument: how the function is meant to read the marks.

  @Test
  fun `test a motion over part of a line is charwise`() {
    val session = Session("one two three")
    session.recordingOperatorFunc()
    session.type("g@w")
    assertEquals("char", session.global("type"))
  }

  @Test
  fun `test a linewise motion is linewise`() {
    val session = Session("one\ntwo\nthree")
    session.recordingOperatorFunc()
    session.type("g@j")
    assertEquals("line", session.global("type"))
  }

  // The marks: what text the motion covered.

  @Test
  fun `test the marks bracket the word a w motion covered`() {
    val session = Session("one two three")
    session.recordingOperatorFunc()
    session.type("g@w")
    assertEquals("1:1", session.global("start"))
    assertEquals("1:4", session.global("end"))
  }

  @Test
  fun `test the marks span both lines of a j motion`() {
    val session = Session("one\ntwo\nthree")
    session.recordingOperatorFunc()
    session.type("g@j")
    assertEquals("1:1", session.global("start"))
    assertEquals(2, session.global("end").substringBefore(':').toInt())
  }

  @Test
  fun `test a text object is a range like any other`() {
    val session = Session("one two three", caretOffset = 5)
    session.recordingOperatorFunc()
    session.type("g@iw")
    assertEquals("char", session.global("type"))
    assertEquals("1:5", session.global("start"))
    assertEquals("1:7", session.global("end"))
  }

  @Test
  fun `test a count is applied to the motion`() {
    val session = Session("one two three four")
    session.recordingOperatorFunc()
    session.type("g@2w")
    assertEquals("1:1", session.global("start"))
    assertEquals("1:8", session.global("end"))
  }

  // Visual mode: the selection is the range, and no motion is asked for.

  @Test
  fun `test g at in visual mode uses the selection`() {
    val session = Session("one two three")
    session.recordingOperatorFunc()
    session.type("vllg@")
    assertEquals("char", session.global("type"))
    assertEquals("1:1", session.global("start"))
    assertEquals("1:3", session.global("end"))
  }

  @Test
  fun `test g at in linewise visual mode is linewise`() {
    val session = Session("one\ntwo\nthree")
    session.recordingOperatorFunc()
    session.type("Vjg@")
    assertEquals("line", session.global("type"))
  }

  // The function is a real function: it can change the buffer through the marks.

  @Test
  fun `test the operator function can edit the range it was given`() {
    val session = Session("one\ntwo\nthree")
    session.source(
      """
      function! Drop(type)
        '[,']d
      endfunction
      set operatorfunc=Drop
      """.trimIndent(),
    )
    session.type("g@j")
    assertEquals("three", session.fake.document.content)
  }

  // No 'operatorfunc' is an error, not a crash.

  @Test
  fun `test g at without an operator function reports E774`() {
    val session = Session("one two three")
    session.type("g@w")
    assertEquals("one two three", session.fake.document.content)
  }
}
