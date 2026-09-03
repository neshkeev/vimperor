/*
 * Copyright 2026 Nikita Eshkeev
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

/**
 * `has()`, which was the IntelliJ plugin's alone and is the engine's now.
 *
 * The tests come in two halves and the second is the important one. Claiming a feature is easy and
 * costs nothing to write; *not* claiming one is the part that has to be checked, because a
 * `has('python')` that answered 1 would send a config down a branch that cannot work, and it would
 * look correct in any test that only ever asked about features this fork does have.
 *
 * The operating system is deliberately not tested here. It comes from the host, and a headless run
 * has no platform to report - which is itself the design: the engine has no way to ask what it is
 * running on and no business guessing.
 */
class HeadlessHasTest {

  private fun evaluate(expression: String): String {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    val parsed = injector.vimscriptParser.parseExpression(expression)
      ?: throw AssertionError("failed to parse: $expression")
    val editor = TestVimEditor("", listOf(TestVimCaret(0)))
    return parsed.evaluate(editor, HeadlessExecutionContext, CommandLineVimLContext).toOutputString()
  }

  @Test
  fun `test the features this fork actually has`() {
    for (feature in listOf(
      "ide",
      "eval",
      "autocmd",
      "user_commands",
      "digraphs",
      "folding",
      "syntax",
      "jumplist",
      "quickfix",
      "signs",
      "mksession",
      "windows",
      "vertsplit",
      "textobjects",
      "clipboard",
    )) {
      assertEquals("1", evaluate("has('$feature')"), "has('$feature') should be true")
    }
  }

  /**
   * The half that matters: what is not claimed.
   *
   * Each of these would send a config down a branch that cannot work. `gui_running` is the one
   * worth pausing on - both hosts are unmistakably graphical, and answering 1 would be the honest-
   * looking wrong answer, because what a config does behind that guard is set `guifont` and
   * `guioptions`, neither of which exists here. One skipped block becomes two `E518`s.
   */
  @Test
  fun `test the features this fork does not have`() {
    for (feature in listOf(
      "nvim",
      "gui_running",
      "terminal",
      "python",
      "python3",
      "perl",
      "ruby",
      "lua",
      "job",
      "channel",
      "timers",
      "conceal",
      "popupwin",
      "textprop",
      "patch-9.1.0",
      "nosuchfeatureatall",
    )) {
      assertEquals("0", evaluate("has('$feature')"), "has('$feature') should be false")
    }
  }

  /** `spell` is the host's answer, and a headless host has no spellchecker to report. */
  @Test
  fun `test a host feature is the host's to claim`() {
    assertEquals("0", evaluate("has('spell')"))
    assertEquals("0", evaluate("has('unix')"), "a headless run has no platform")
  }

  /** True only while the configuration file is being read, which is what makes it useful. */
  @Test
  fun `test vim_starting is false outside a configuration load`() {
    assertEquals("0", evaluate("has('vim_starting')"))
  }

  @Test
  fun `test the second argument is accepted`() {
    assertEquals("1", evaluate("has('eval', 1)"))
  }
}
