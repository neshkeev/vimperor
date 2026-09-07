/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `:s` from typed text to changed buffer, on both targets.
 *
 * This is the first vertical slice: the vimscript parser, `CommandVisitor` building a
 * `SubstituteCommand`, the Vim regex engine finding the match, and the change group rewriting the
 * text - four layers this port proved separately, now proved together. `:%s/foo/bar/g` running in a
 * JS runtime is the thing a VS Code extension ultimately has to do.
 */
class HeadlessSubstituteTest {

  private class Buffer(text: String) {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor(text, listOf(caret)).also { caret.editorRef = it }
  }

  private fun run(text: String, command: String): String {
    injector = HeadlessInjector()
    // `:s` publishes each match through `submatch()`, which it looks up by name and casts - so the
    // builtins have to be registered even for a substitution that never mentions a function.
    injector.functionService.registerHandlers()
    val buffer = Buffer(text)
    val parsed = injector.vimscriptParser.parseCommand(command)
      ?: throw AssertionError("failed to parse: $command")
    // The executor normally sets this while walking a script; a command executed on its own has to
    // be told where it sits, and `:s` reads it to decide whether it is running from the command
    // line or from a sourced file.
    parsed.vimContext = CommandLineVimLContext
    parsed.execute(buffer.editor, HeadlessExecutionContext)
    return buffer.editor.text
  }

  @Test
  fun `test an unregistered submatch reports what is wrong`() {
    // The precondition that used to fail as a NullPointerException from a cast, nowhere near its
    // cause. A host that forgets `registerHandlers()` should be told which call it missed.
    injector = HeadlessInjector()
    val buffer = Buffer("foo")
    val parsed = injector.vimscriptParser.parseCommand("s/foo/bar/")!!
    parsed.vimContext = CommandLineVimLContext
    val failure = assertFailsWith<IllegalStateException> {
      parsed.execute(buffer.editor, HeadlessExecutionContext)
    }
    assertTrue(
      failure.message!!.contains("registerHandlers"),
      "the message should name the call that was missed, but was: ${failure.message}",
    )
  }

  @Test
  fun `test substitute replaces the first match on a line`() {
    assertEquals("bar foo", run("foo foo", "s/foo/bar/"))
  }

  @Test
  fun `test the g flag replaces every match on the line`() {
    assertEquals("bar bar", run("foo foo", "s/foo/bar/g"))
  }

  @Test
  fun `test a range applies to the lines it names`() {
    assertEquals("bar\nfoo", run("foo\nfoo", "1s/foo/bar/"))
  }

  @Test
  fun `test a percent range applies to the whole buffer`() {
    assertEquals("bar\nbar", run("foo\nfoo", "%s/foo/bar/"))
  }

  @Test
  fun `test a pattern with a quantifier matches through the regex engine`() {
    assertEquals("X", run("aaa", "s/a\\+/X/"))
  }
}
