/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.host
import com.github.neshkeev.vimperor.message.MessageHistory
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.host.HeadlessExecutionContext
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.host.HeadlessMessages
import com.maddyhome.idea.vim.host.HeadlessOutputPanelService
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `:messages`, and the funnel that had to exist before it could.
 *
 * `VimMessages` used to say outright that a message "does not pass through any one place in the
 * engine on its way to a host", and that was true and was the reason each host consulted `:silent`
 * on its own. A flag each host reads can answer "should I draw this?"; nothing can answer "what was
 * said?" without a single place to record it. `VimMessagesBase` is now that place - the four `show*`
 * methods are final there and record before delegating to a `display*` each host implements.
 *
 * So the two tests that matter are the ones about what is *not* obvious: an error is remembered
 * even when `:silent!` swallowed it, and `:echo` is not remembered at all while `:echomsg` is.
 * Both are Vim's rules and both would be easy to get backwards.
 */
class HeadlessMessagesTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one two", listOf(caret)).also { caret.editorRef = it }
    val panel: HeadlessOutputPanelService get() = injector.outputPanel as HeadlessOutputPanelService

    fun run(line: String): ExecutionResult =
      injector.vimscriptExecutor.execute(
        line,
        editor,
        HeadlessExecutionContext,
        skipHistory = true,
        indicateErrors = true,
        CommandLineVimLContext,
      )

    fun clearPanel() = injector.outputPanel.clear(editor, HeadlessExecutionContext)

    /** What `:messages` prints, which is the only way a user reads the history. */
    fun printed(): String {
      clearPanel()
      run("messages")
      return panel.lines.joinToString("")
    }
  }

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session()
  }

  @Test
  fun `test an error is still there after the status line has moved on`() {
    val s = session()
    s.run("set nosuchoption")

    assertTrue("E518" in s.printed(), "the error should be in the history, got: ${s.printed()}")
  }

  /**
   * `:silent` decides what is drawn; the history is about what was said.
   *
   * The recording sits in front of every host's silence check, so a message that was hidden is
   * still findable afterwards. `:silent!` is the one exception and it is Vim's, not this fork's:
   * a bang stops the error before it is ever reported, in `emsg_core` there and in the executor
   * here, so there is nothing to record. That is why the two are tested as a pair.
   */
  @Test
  fun `test a message silent hid is remembered anyway`() {
    val s = session()
    s.run("silent echomsg 'hidden but said'")

    assertTrue("hidden but said" in s.printed(), "silent hides the drawing, not the saying")
  }

  @Test
  fun `test an error silent bang swallowed was never said at all`() {
    val s = session()
    s.run("silent! set nosuchoption")

    assertEquals("No messages.\n", s.printed(), "a banged silent stops the error being reported")
  }

  /** Vim's whole difference between the two commands, and the reason `:echomsg` exists. */
  @Test
  fun `test echomsg is remembered and echo is not`() {
    val s = session()
    s.run("echo 'not remembered'")
    s.run("echomsg 'remembered'")

    val printed = s.printed()
    assertTrue("remembered" in printed)
    assertFalse("not remembered" in printed, "`:echo` is not message history, got: $printed")
  }

  /**
   * The status line is a third path, and it had to be in the funnel too.
   *
   * A failed `:s` reports through `showStatusBarMessage` rather than `showErrorMessage` - it is one
   * of two dozen places that do - so a history built from the error method alone would miss exactly
   * the kind of message somebody runs `:messages` to find.
   */
  @Test
  fun `test a report that only went to the status line is remembered`() {
    val s = session()
    s.run("s/nosuchtext/x/")

    assertTrue("E486" in s.printed(), "got: ${s.printed()}")
  }

  @Test
  fun `test messages are printed oldest first`() {
    val s = session()
    s.run("echomsg 'first'")
    s.run("echomsg 'second'")

    assertEquals("first\nsecond\n", s.printed())
  }

  @Test
  fun `test messages clear empties the history`() {
    val s = session()
    s.run("echomsg 'gone'")
    s.run("messages clear")

    assertEquals("No messages.\n", s.printed())
  }

  @Test
  fun `test an empty history says so rather than showing a blank panel`() {
    val s = session()

    assertEquals("No messages.\n", s.printed())
  }

  @Test
  fun `test an argument that is not clear is rejected`() {
    val s = session()
    s.run("messages sideways")

    assertEquals("E474: Invalid argument: sideways", (injector.messages as HeadlessMessages).lastError)
  }

  /** A script in a loop must not be able to grow this without bound. */
  @Test
  fun `test the history stops at the limit and keeps the newest`() {
    session()
    for (index in 1..MessageHistory.LIMIT + 10) {
      MessageHistory.record("message $index", com.maddyhome.idea.vim.api.MessageType.STANDARD)
    }

    val all = MessageHistory.all()
    assertEquals(MessageHistory.LIMIT, all.size)
    assertEquals("message ${MessageHistory.LIMIT + 10}", all.last().text)
    assertEquals("message 11", all.first().text)
  }

  /** Nothing was said, so nothing is remembered - and a blank does not push a real message out. */
  @Test
  fun `test a blank message is not remembered`() {
    val s = session()
    s.run("echomsg ''")

    assertEquals("No messages.\n", s.printed())
  }
}
