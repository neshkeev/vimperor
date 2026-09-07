/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.host
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.host.HeadlessExecutionContext
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.host.HeadlessMessages
import com.maddyhome.idea.vim.host.HeadlessSpellchecker
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three spell commands a borrowed checker can answer, and the five it cannot.
 *
 * Vim owns its spell checking - `.spl` files, a good list, a bad list, a rare list - and this
 * engine owns none of it. What it has is the three-method checker the IDE already runs, which `zg`,
 * `zw` and `z=` have used for years. `:spellgood` and its two relatives are those same three calls
 * with the word named rather than pointed at, which is what Vim says they are.
 *
 * The line between the two groups is the interesting part, and it is the same line `zw` already
 * sits on: a checker that answers "add", "remove" and "suggest" has no list to dump, no file to
 * describe and nothing to compile.
 */
class HeadlessSpellTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one\ntwo", listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val spelling: HeadlessSpellchecker get() = injector.spellcheckerService as HeadlessSpellchecker

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

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session()
  }

  @Test
  fun `test spellgood adds the word to the dictionary`() {
    val s = session()
    s.run("spellgood Vimperor")

    assertEquals(listOf("Vimperor"), s.spelling.added)
  }

  /** A `:spellgood` in a config is usually a line of project jargon rather than one word. */
  @Test
  fun `test several words on one command line are all added`() {
    val s = session()
    s.run("spellgood Vimperor IdeaVim commonMain")

    assertEquals(listOf("Vimperor", "IdeaVim", "commonMain"), s.spelling.added)
  }

  /**
   * Both remove, which is the approximation `zw` already makes: an IDE's dictionary has words it
   * knows and words it does not, and no third state for one it has been told is wrong.
   */
  @Test
  fun `test spellwrong and spellundo both take the word out`() {
    val s = session()
    s.run("spellwrong teh")
    s.run("spellundo Vimperor")

    assertEquals(listOf("teh", "Vimperor"), s.spelling.removed)
    assertEquals(emptyList(), s.spelling.added)
  }

  @Test
  fun `test a spell command with no word is an error`() {
    val s = session()
    s.messages.clearError()
    s.run("spellgood")

    assertTrue(s.messages.lastError != null, "a word is required")
    assertEquals(emptyList(), s.spelling.added)
  }

  /** `spe` is the shortest Vim accepts, because `sp` is `:split`. */
  @Test
  fun `test the abbreviations are Vim's`() {
    val s = session()
    s.run("spe Vimperor")
    s.run("spellw teh")

    assertEquals(listOf("Vimperor"), s.spelling.added)
    assertEquals(listOf("teh"), s.spelling.removed)
  }

  @Test
  fun `test the commands that need a word list report E319`() {
    val s = session()
    for (line in listOf("spelldump", "spellinfo", "spellrare x", "spellrepall", "mkspell x")) {
      s.messages.clearError()
      s.run(line)
      assertTrue(s.messages.lastError?.contains("E319") == true, "`:$line` gave ${s.messages.lastError}")
    }
  }

  /** `:mkspell` must not swallow `:mksession`, which is a different command with a shorter name. */
  @Test
  fun `test mks is still mksession`() {
    val s = session()
    s.messages.clearError()
    s.run("mks")

    assertTrue(s.messages.lastError?.contains("E319") == true, "got ${s.messages.lastError}")
  }
}
