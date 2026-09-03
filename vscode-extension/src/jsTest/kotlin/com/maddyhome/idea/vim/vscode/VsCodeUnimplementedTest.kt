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
import com.maddyhome.idea.vim.vimscript.model.commands.engineExCommandProvider
import com.maddyhome.idea.vim.vimscript.model.functions.engineFunctionProvider
import com.maddyhome.idea.vim.api.VimCommandGroup
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every key the engine registers, pressed, to find out which ones land on a part of this host that
 * is not built yet.
 *
 * The port is a set of host services behind an engine that assumes all of them exist. The ones this
 * host has not written throw `NotImplementedError` when reached, which is the right thing for a
 * half-built host to do - but only if somebody finds out. Until this test there was no way to know
 * which keys were live and which were mines, and the way it got discovered was a crash: nothing had
 * ever used a bracket text object, so nothing had noticed `psiService` was a `TODO()`, until a test
 * for something else walked into it.
 *
 * So this presses everything. The list below is the whole remaining gap, and it is asserted rather
 * than printed: implementing a service shrinks it, and a service that quietly stops being reached -
 * or starts being reached - shows up as a diff. It is not a list of bugs. Most of these are
 * features this host does not have yet and one or two are features it can never have.
 *
 * A key is listed once per distinct message, deduplicated across the modes it is registered in.
 */
class VsCodeUnimplementedTest {

  @Test
  fun `test the keys that reach an unbuilt part of the host are the ones listed here`() {
    assertEquals(EXPECTED.trim(), sweep().trim())
  }

  /**
   * The same question asked of the ex commands, which the key sweep cannot reach.
   *
   * It only presses keys, so everything behind `:` was invisible to it - and that blind spot hid
   * `:w` and `:q`, which are not obscure. A colon command is a name rather than a key, so this
   * types each registered one at the command line and watches for the same crash.
   *
   * Bare, with no arguments and no range. That is enough to find a missing *service*, which is what
   * this is looking for; a command that needs an argument reports a Vim error rather than failing,
   * and an error is a working command.
   *
   * It watches the messages rather than the exception, which is the whole reason this had to be
   * written separately. The Vimscript executor catches `NotImplementedError` on purpose and turns
   * it into "Not implemented yet :(" - so an ex command standing on an unbuilt service does not
   * crash, it apologises. Nothing that looked for a crash would ever have found `:w`.
   */
  @Test
  fun `test the ex commands that reach an unbuilt part of the host are the ones listed here`() {
    assertEquals(EXPECTED_EX.trim(), sweepExCommands().trim())
  }

  /**
   * The same question again, of `:set` and of the Vimscript functions.
   *
   * Asked because the last time this file gained a sweep it was because the previous one had a
   * blind spot the size of `:w`. Keys were swept, then colon commands; options and functions are
   * the other two surfaces a user can reach, and assuming they are fine because nobody has
   * complained is exactly the reasoning that hid `:w` for a dozen commits.
   *
   * `:set name?` for every registered option, `echo Name()` for every registered function. Neither
   * needs to succeed - an option that will not print and a function called with no arguments both
   * report a Vim error, and a Vim error is a working command. Only the apology counts.
   */
  @Test
  fun `test no option or function reaches an unbuilt part of the host`() {
    assertEquals(EXPECTED_SETTINGS.trim(), sweepOptionsAndFunctions().trim())
  }

  /**
   * That the sweep above can fail.
   *
   * It found nothing, and a sweep that finds nothing is indistinguishable from a sweep that is not
   * looking - which is not a hypothetical: an earlier version of the ex-command sweep passed
   * silently while `:w` was broken, because it watched for a crash and the executor turns the crash
   * into a message.
   *
   * So the detector is pointed at the same command twice: once at this host, and once at a host
   * with one service taken back out. Earlier versions of this test named a command that happened
   * to be unbuilt, and both of them rotted - `:tabclose` one commit after it was written, and
   * `:loadkeymap` the commit that emptied the list. A hole this test digs itself cannot be filled
   * in by accident.
   */
  @Test
  fun `test the sweep can tell an unbuilt command from a working one`() {
    assertFalse(apologises(":comclear"), "`:comclear` works on this host")
    assertTrue(
      apologises(":comclear") { WithoutCommandGroup(it) },
      "the same command must be caught when the service behind it is missing",
    )
  }

  /** This host, minus one service, to prove the sweep would notice if a service went missing. */
  private class WithoutCommandGroup(sink: MessageSink) : VsCodeInjector(messageSink = sink) {
    override val commandGroup: VimCommandGroup
      get() = TODO("the VS Code host does not provide commandGroup yet")
  }

  /** How many probes the sweep makes, so that an empty result cannot be an empty loop. */
  @Test
  fun `test the sweep covers every option and every function`() {
    injector = VsCodeInjector().also { it.register(VsCodeEditor(FakeEditor(""))) }
    assertTrue(injector.optionGroup.getAllOptions().size > 50, "the option list looks too short")
    assertTrue(engineFunctionProvider.getFunctions().size > 50, "the function list looks too short")
  }

  private fun sweepOptionsAndFunctions(): String {
    injector = VsCodeInjector().also { it.register(VsCodeEditor(FakeEditor(""))) }
    val probes = injector.optionGroup.getAllOptions().map { ":set ${it.name}?" } +
      engineFunctionProvider.getFunctions().map { "echo ${it.name}()" }

    return probes.sorted().filter { probe -> apologises(probe) }.joinToString(" ")
  }

  /** Runs one `:` line and says whether the host admitted to not being built. */
  private fun apologises(
    probe: String,
    host: (MessageSink) -> VsCodeInjector = { VsCodeInjector(messageSink = it) },
  ): Boolean {
    val said = mutableListOf<String>()
    val sink = object : MessageSink {
      override fun message(text: String?) { said += text.orEmpty() }
      override fun error(text: String?) { said += text.orEmpty() }
      override fun status(text: String?) { said += text.orEmpty() }
    }
    try {
      val fake = FakeEditor("one two\nthree four")
      injector = host(sink).also { it.register(VsCodeEditor(fake)) }
      engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      VsCodeCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      injector.functionService.registerHandlers()
      val editor = injector.editorGroup.getEditors().first() as VsCodeEditor
      val handler = KeyHandler.getInstance()
      handler.fullReset(editor)
      for (stroke in injector.parser.stringToKeys(":" + probe.removePrefix(":"))) {
        handler.handleKey(editor, stroke, VsCodeExecutionContext, handler.keyHandlerState)
      }
      for (stroke in injector.parser.parseKeys("<CR>")) {
        handler.handleKey(editor, stroke, VsCodeExecutionContext, handler.keyHandlerState)
      }
      editor.flush()
    } catch (e: NotImplementedError) {
      said += NOT_IMPLEMENTED
    } catch (e: Throwable) {
      // A Vim error is the option or function working.
    }
    return said.any { it.contains(NOT_IMPLEMENTED) }
  }

  private fun sweepExCommands(): String {
    val unbuilt = mutableListOf<String>()
    injector = VsCodeInjector().also { it.register(VsCodeEditor(FakeEditor(""))) }

    // "w[rite]" names both `:w` and `:write`; the short form is the one to type.
    val names = engineExCommandProvider.getCommands().keys
      .map { it.substringBefore('[') }
      .filter { it.isNotEmpty() && it.all { c -> c.isLetter() } }
      .distinct()
      .sorted()

    for (name in names) {
      val said = mutableListOf<String>()
      val sink = object : MessageSink {
        override fun message(text: String?) { said += text.orEmpty() }
        override fun error(text: String?) { said += text.orEmpty() }
        override fun status(text: String?) { said += text.orEmpty() }
      }
      try {
        val fake = FakeEditor("one two\nthree four\nfive six")
        injector = VsCodeInjector(messageSink = sink).also { it.register(VsCodeEditor(fake)) }
        engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
        VsCodeCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
        injector.functionService.registerHandlers()
        val editor = injector.editorGroup.getEditors().first() as VsCodeEditor
        val handler = KeyHandler.getInstance()
        handler.fullReset(editor)
        for (stroke in injector.parser.parseKeys(":" + name + "<CR>")) {
          handler.handleKey(editor, stroke, VsCodeExecutionContext, handler.keyHandlerState)
        }
        editor.flush()
      } catch (e: NotImplementedError) {
        // Reached when the command throws outside the executor's own catch.
        said += NOT_IMPLEMENTED
      } catch (e: Throwable) {
        // A Vim error, a bad argument, a command that needs a range - all of them are the command
        // working. Only an unbuilt part of the host is being looked for here.
      }
      if (said.any { it.contains(NOT_IMPLEMENTED) }) unbuilt += ":" + name
    }

    return unbuilt.sorted().joinToString(" ")
  }


  private fun sweep(): String {
    val found = mutableMapOf<String, MutableSet<String>>()

    // A first injector purely to have a parser to name keys with; each press then gets its own.
    injector = VsCodeInjector().also { it.register(VsCodeEditor(FakeEditor(""))) }
    val commands = engineCommandProvider.getCommands() + VsCodeCommandProvider.getCommands()

    for (command in commands) {
      for (keys in command.keys) {
        val notation = injector.parser.toKeyNotation(keys)
        try {
          val fake = FakeEditor("one two\nthree four\nfive six")
          injector = VsCodeInjector().also { it.register(VsCodeEditor(fake)) }
          engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
          VsCodeCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
          injector.functionService.registerHandlers()
          val editor = injector.editorGroup.getEditors().first() as VsCodeEditor
          editor.primaryCaret().moveToOffsetNative(4)
          val handler = KeyHandler.getInstance()
          handler.fullReset(editor)
          for (stroke in keys) {
            handler.handleKey(editor, stroke, VsCodeExecutionContext, handler.keyHandlerState)
          }
          editor.flush()
        } catch (e: NotImplementedError) {
          val what = (e.message ?: "?").removePrefix("An operation is not implemented: ")
          found.getOrPut(what) { mutableSetOf() } += notation
        }
        // Anything else a key does here - an error message, a beep, nothing at all - is that key
        // working. This test is only asking which parts of the host are missing.
      }
    }

    return found.entries.sortedBy { it.key }.joinToString("\n") { (what, keys) ->
      "$what\n    ${keys.sorted().joinToString(" ")}"
    }
  }

  private companion object {
    /** What the Vimscript executor says instead of letting a NotImplementedError out. */
    const val NOT_IMPLEMENTED = "Not implemented yet"

    /**
     * What is not built. Each line is one missing piece of host and the keys that reach it.
     *
     * What is left is no longer a matter of writing more host. `U` needs a history this host does
     * not keep - VS Code owns the undo stack and will not say what is on it - and the rest need a
     * language server or Vim's command-line window, neither of which VS Code has. The ordinary Vim
     * keys are off this list as of `S`.
     *
     * `z=`, `zg` and `zw` came off it without a spell checker being written, which is the other way
     * a key leaves: they now reach [NoSpellchecker], which reports that VS Code has none and says
     * what to do instead. That is a better answer than "Not implemented yet :(" and it is a real
     * answer, so the keys are no longer unimplemented - they are implemented as a refusal. `[s`
     * and `]s` are still here because they are motions and a motion has nowhere to put a message.
     */
    const val EXPECTED_SETTINGS = """
"""

    const val EXPECTED_EX = """
"""

    const val EXPECTED = """
VS Code host: findMethodEnd needs a language server
    [M ]M
VS Code host: findMethodStart needs a language server
    [m ]m
VS Code host: findMisspelledWord needs a spellchecker
    [s ]s
the VS Code host does not provide searchWindowGroup yet
    q/ q: q?
"""
  }
}
