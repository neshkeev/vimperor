/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.Options
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.common.CommandAliasHandler
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.key.OperatorFunction
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two services a plugin needs from the host, and the two reasons that said they were missing.
 *
 * Both reasons were wrong, in different ways, and both were on the list that exists so that this
 * kind of thing is written down rather than rediscovered.
 *
 * `pluginService` was recorded as blocked behind `modalInput.activate` - the blocking key read that
 * `getchar()` needs, which JavaScript cannot do on one thread. That is a real limit and it is about
 * a different part of the extension system. None of these three methods reads a key.
 *
 * `fallbackWindow` was recorded as "the editor the engine reaches for when there is no window at
 * all", which reads like a corner nobody hits. It is what *every* thin-API scope falls back to when
 * there is no project id - and an extension's `init` runs with none, because there is no editor yet
 * while a plugin is declaring its mappings. It is the first thing the extension chain asks for.
 */
class PluginServicesTest {

  private class Session(text: String = "one two three") {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }
    val editor = host.editorFor(fake)

    init {
      KeyHandler.getInstance().fullReset(editor)
    }
  }

  /** `:normal!` from a plugin: the keys run, and the user's mappings do not apply to them. */
  @Test
  fun `test executeNormalWithoutMapping runs the keys`() {
    val session = Session("one two three")

    injector.pluginService.executeNormalWithoutMapping("dw", session.editor)
    session.editor.flush()

    assertEquals("two three", session.fake.document.content)
  }

  /**
   * And without mapping is not a detail: a plugin that feeds `dw` means the built-in `dw`.
   *
   * `KeySource.NORMAL_COMMAND_NOT_MAPPED` is the whole of it, and the way to see that it is being
   * passed is to map the key to something else and watch the plugin's keys ignore the mapping.
   */
  @Test
  fun `test executeNormalWithoutMapping ignores the user's mappings`() {
    val session = Session("one two three")
    injector.vimscriptExecutor.execute("nmap d x", session.editor, VsCodeExecutionContext, skipHistory = true)

    injector.pluginService.executeNormalWithoutMapping("dw", session.editor)
    session.editor.flush()

    assertEquals("two three", session.fake.document.content)
  }

  /** `addCommand` is a command alias, so the name becomes a `:` command that runs the handler. */
  @Test
  fun `test addCommand makes a colon command that runs the handler`() {
    val session = Session()
    var ran = 0

    injector.pluginService.addCommand(
      "Greet",
      object : CommandAliasHandler {
        override fun execute(command: String, range: Range, editor: VimEditor, context: ExecutionContext) {
          ran++
        }
      },
    )
    injector.vimscriptExecutor.execute("Greet", session.editor, VsCodeExecutionContext, skipHistory = true)

    assertEquals(1, ran, "the alias should have run its handler")
  }

  /**
   * `exportOperatorFunction` declares a Vimscript function, which is what `g@` calls.
   *
   * Which is most of what an operator plugin *is* - `cx`, `gr` and `gc` are all a mapping to `g@`
   * plus a function the plugin exported. The whole path is checked rather than the declaration:
   * `:set operatorfunc=` names it and `g@w` runs it over a word.
   */
  @Test
  fun `test an exported operator function is called by g@`() {
    val session = Session("one two three")
    var calledWith: SelectionType? = null

    injector.pluginService.exportOperatorFunction(
      "Upcase",
      object : OperatorFunction {
        override fun apply(editor: VimEditor, context: ExecutionContext, selectionType: SelectionType?): Boolean {
          calledWith = selectionType
          return true
        }
      },
    )
    injector.vimscriptExecutor.execute(
      "set operatorfunc=Upcase",
      session.editor,
      VsCodeExecutionContext,
      skipHistory = true,
    )

    for (key in injector.parser.parseKeys("g@w")) {
      KeyHandler.getInstance().handleKey(session.editor, key, VsCodeExecutionContext, KeyHandler.getInstance().keyHandlerState)
    }

    assertEquals(SelectionType.CHARACTER_WISE, calledWith, "g@w should have called the exported function")
  }

  /**
   * The fallback window is a real editor with real text, because that is what the engine reads.
   *
   * Empty to begin with, and its own buffer: an extension asking for the text before any file is
   * open has to get an answer rather than an exception, and it must not be some open file's.
   */
  @Test
  fun `test the fallback window is an editor over an empty buffer`() {
    Session()

    val fallback = injector.fallbackWindow

    assertEquals("", fallback.text().toString())
    assertEquals(0, fallback.primaryCaret().offset)
  }

  /**
   * A window-local option can be set on it and read back, which is what a plugin's `:set` does
   * during `init` - before there is any window but this one.
   *
   * Deliberately not a claim that the eager initialisation beside it in `VsCodeInjector` is what
   * makes this work: it passes without that line too, because setting a value initialises the
   * storage on the way past. The initialisation is there because IdeaVim's fallback window has it
   * and because the per-window "global" values of window-local options have to live somewhere, and
   * this test does not distinguish it. Saying so beats a test that looks like it checks something
   * it does not.
   */
  @Test
  fun `test a window-local option can be set on the fallback window`() {
    Session()

    // `'scroll'` is local to a window, so setting it stores a value against this editor and reading
    // it back is the whole round trip a plugin's `:set` makes during init.
    injector.optionGroup.setOptionValue(
      Options.scroll,
      OptionAccessScope.LOCAL(injector.fallbackWindow),
      VimInt(7),
    )

    assertEquals(
      VimInt(7),
      injector.optionGroup.getOptionValue(Options.scroll, OptionAccessScope.LOCAL(injector.fallbackWindow)),
    )
  }

  /** The same one every time, because window-local option values are stored against the editor. */
  @Test
  fun `test the fallback window is kept rather than remade`() {
    Session()

    assertTrue(injector.fallbackWindow === injector.fallbackWindow, "a fresh fallback window each time would lose its options")
  }

  /**
   * And it is not one of the open editors, which is the mistake worth guarding against.
   *
   * Answering with the focused editor would look right until an extension wrote to it during
   * `init` - and `init` runs before there is any editor the user has chosen.
   */
  @Test
  fun `test the fallback window is not an open editor`() {
    val session = Session("real file")

    assertTrue(injector.fallbackWindow !== session.editor)
    assertEquals("real file", session.editor.text().toString())
  }
}
