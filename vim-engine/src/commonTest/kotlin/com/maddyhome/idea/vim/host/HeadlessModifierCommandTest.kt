/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.MessageSuppression
import com.maddyhome.idea.vim.api.Options
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.autocmd.AutoCmdEvent
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vim's command modifiers, on both targets.
 *
 * A modifier is a command whose argument is another command, so all four of them are one question
 * asked four ways: does the thing this modifier changes hold for exactly the length of the command
 * it wraps? Every test here checks the change *and* checks that it was put back, because a modifier
 * that leaks is worse than one that does nothing - `:silent!` that forgot to stop being silent
 * would make the rest of the session lie.
 */
class HeadlessModifierCommandTest {

  private class Session(text: String = "one\ntwo\nthree") {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor(text, listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    // Trimmed, because `:echo` ends its line and what is being asserted here is what was said.
    val output: List<String>
      get() = (injector.outputPanel as HeadlessOutputPanelService).lines.map { it.trimEnd('\n') }

    fun run(line: String): ExecutionResult =
      injector.vimscriptExecutor.execute(
        line,
        editor,
        HeadlessExecutionContext,
        skipHistory = true,
        indicateErrors = true,
        CommandLineVimLContext,
      )
  }

  private fun session(text: String = "one\ntwo\nthree"): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session(text)
  }

  // `:silent`

  @Test
  fun `test silent hides what the command had to say`() {
    val s = session()
    s.run("""echo "loud"""")
    assertEquals(listOf("loud"), s.output)

    s.run("""silent echo "quiet"""")
    assertEquals(listOf("loud"), s.output, "the second echo should not have reached the panel")
  }

  /**
   * The bang is the whole difference between the two, so it is the thing to pin.
   *
   * `:set` on an option that does not exist, rather than a command that does not exist: an unknown
   * *command* is looked up among the user's aliases before it is reported, and a headless host has
   * no alias list. The failing line only has to fail.
   */
  @Test
  fun `test silent still reports an error and silent bang does not`() {
    val s = session()
    s.run("silent set nosuchoptionatall")
    assertTrue(s.messages.lastError?.contains("E518") == true, "got ${s.messages.lastError}")

    val bang = session()
    bang.run("silent! set nosuchoptionatall")
    assertNull(bang.messages.lastError, "`:silent!` is how a config guards a line that may fail")
  }

  /** `:silent!` is "try this", so the script carries on as though it had worked. */
  @Test
  fun `test silent bang reports success whatever happened`() {
    val s = session()
    assertEquals(ExecutionResult.Error, s.run("silent set nosuchoptionatall"))
    assertEquals(ExecutionResult.Success, s.run("silent! set nosuchoptionatall"))
  }

  @Test
  fun `test the silence lasts exactly one command`() {
    val s = session()
    s.run("""silent! echo "quiet"""")
    assertEquals(MessageSuppression.NONE, injector.messages.suppression)

    s.run("""echo "loud"""")
    assertEquals(listOf("loud"), s.output)
  }

  /** A modifier with nothing after it is a no-op in Vim, not an error. */
  @Test
  fun `test a bare modifier does nothing and says nothing`() {
    val s = session()
    assertEquals(ExecutionResult.Success, s.run("silent"))
    assertNull(s.messages.lastError)
  }

  // `:verbose`

  @Test
  fun `test verbose runs the command and puts the option back`() {
    val s = session()
    s.run("verbose set number")

    assertTrue(injector.optionGroup.getOptionValue(Options.number, OptionAccessScope.EFFECTIVE(s.editor)).toVimNumber().booleanValue)
    assertEquals(
      0,
      injector.optionGroup.getOptionValue(Options.verbose, OptionAccessScope.GLOBAL(s.editor)).value,
      "'verbose' is raised for the length of one command",
    )
  }

  // `:noautocmd`

  @Test
  fun `test noautocmd runs the command and leaves events on afterwards`() {
    val s = session()
    s.run("""noautocmd echo "ran"""")

    assertEquals(listOf("ran"), s.output)
    assertEquals(false, injector.autoCmd.eventsSuppressed)
  }

  /**
   * The other half: what the flag `:noautocmd` sets actually does.
   *
   * Fired directly rather than through a wrapped command, because no command in the engine fires
   * an event of its own - the hosts do that, on opening and closing a file.
   */
  @Test
  fun `test a suppressed event fires nothing`() {
    val s = session()
    injector.autoCmd.registerEventCommand("""echo "fired"""", AutoCmdEvent.BufEnter)

    injector.autoCmd.eventsSuppressed = true
    injector.autoCmd.handleEvent(AutoCmdEvent.BufEnter, filePath = "/test/buffer.txt", editor = s.editor)
    assertEquals(emptyList(), s.output)

    injector.autoCmd.eventsSuppressed = false
    injector.autoCmd.handleEvent(AutoCmdEvent.BufEnter, filePath = "/test/buffer.txt", editor = s.editor)
    assertEquals(listOf("fired"), s.output, "the handler is registered; only the flag was stopping it")
  }

  // `:lockmarks`

  @Test
  fun `test lockmarks runs the command and leaves adjustment on afterwards`() {
    val s = session()
    s.run("lockmarks 1delete")

    assertEquals("two\nthree", s.editor.text)
    assertEquals(false, injector.markService.adjustmentSuppressed)
  }

  /**
   * The other half: what the flag `:lockmarks` sets actually does.
   *
   * The adjustment is driven from outside the engine - a host tells the mark service that text was
   * deleted, because a host is what notices - so this says what it says by telling it directly.
   */
  @Test
  fun `test a suppressed mark does not follow the text`() {
    val s = session()
    s.caret.moveToOffset(s.editor.getLineStartOffset(2))
    injector.markService.setMarkForCaret(s.caret, 'a', s.caret.offset)
    assertEquals(2, injector.markService.getMark(s.caret, 'a')?.line)

    injector.markService.adjustmentSuppressed = true
    injector.markService.updateMarksFromDelete(s.editor, 0, 4)
    assertEquals(2, injector.markService.getMark(s.caret, 'a')?.line, "the mark should not have moved")

    injector.markService.adjustmentSuppressed = false
    injector.markService.updateMarksFromDelete(s.editor, 0, 4)
    assertEquals(1, injector.markService.getMark(s.caret, 'a')?.line, "only the flag was holding it")
  }
}
