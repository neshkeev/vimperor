/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.options.ToggleOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `set surround`, which is how an `.ideavimrc` turns an extension on - and which did nothing here.
 *
 * This is the gap `VimEverywhere` walked into and the reason it is worth a file of its own: it
 * affected every bundled extension, not one. IdeaVim's documentation enables all of them with
 * `set <name>`; `Plug` is the other way in, for a config borrowed from Vim. This host only had
 * `Plug`, so `set surround` was `E518: Unknown option` - and a config runs with errors suppressed,
 * so the line failed **silently** and the user's surround simply was not there.
 *
 * `VimEverywhere` made it impossible to leave rather than merely wrong: it has no repository and no
 * `Plug` line anywhere in the wild, so the option is the only way in.
 */
class ExtensionOptionTest {

  private class Session {
    val fake = FakeEditor("one two three\n")
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      injector.extensionLoader.getEnabledExtensions().map { it.extensionName }.forEach {
        injector.extensionLoader.disableExtension(it)
      }
    }

    fun ex(command: String) {
      ":$command".forEach { host.type(fake, it.toString()) }
      host.key(fake, "<CR>")
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    val content: String get() = fake.document.content
    val enabled: List<String> get() = injector.extensionLoader.getEnabledExtensions().map { it.extensionName }
  }

  @Test
  fun `test every bundled extension has an option named after it`() {
    Session()

    val without = VsCodeExtensions.BUNDLED.keys.filter { injector.optionGroup.getOption(it) !is ToggleOption }

    assertEquals(emptyList(), without, "these cannot be enabled with `set`")
  }

  @Test
  fun `test set enables and set no disables`() {
    val session = Session()

    session.ex("set surround")
    assertEquals(listOf("surround"), session.enabled)

    session.ex("set nosurround")
    assertEquals(emptyList(), session.enabled)
  }

  /** The extension has to actually work, not merely appear in a list. */
  @Test
  fun `test an extension enabled with set does its job`() {
    val session = Session()

    session.ex("set surround")
    session.type("ysiw\"")

    assertEquals("\"one\" two three\n", session.content)
  }

  /**
   * The option and the loader are one state, whichever way it is written.
   *
   * `Plug` reaches the loader without going near the option - the engine's own `:Plug` and
   * `:packadd` call `enableExtension` straight - so if the loader did not write the option back,
   * `set nosurround` after a `Plug` line would find the option already false and do nothing at all.
   */
  @Test
  fun `test a Plug line leaves the option set`() {
    val session = Session()

    injector.extensionRegistrator.setOptionByPluginAlias("tpope/vim-surround")

    val option = injector.optionGroup.getOption("surround") as ToggleOption
    assertTrue(injector.optionGroup.getOptionValue(option, com.maddyhome.idea.vim.options.OptionAccessScope.GLOBAL(null)).booleanValue)

    session.ex("set nosurround")
    assertEquals(emptyList(), session.enabled, "set no... after a Plug line has to reach it")
  }

  /** And the other way: turning it off by hand must not leave the option saying it is on. */
  @Test
  fun `test disabling it by hand clears the option`() {
    val session = Session()
    session.ex("set surround")

    injector.extensionLoader.disableExtension("surround")

    val option = injector.optionGroup.getOption("surround") as ToggleOption
    assertTrue(
      !injector.optionGroup.getOptionValue(option, com.maddyhome.idea.vim.options.OptionAccessScope.GLOBAL(null)).booleanValue,
    )
    // And so `set surround` works again rather than being a no-op against a value already true.
    session.ex("set surround")
    assertEquals(listOf("surround"), session.enabled)
  }

  /**
   * `NERDTree` is the one extension whose name is not its own abbreviation, and the abbreviation is
   * IdeaVim's: option names are matched case-sensitively, so `set nerdtree` needs one to work.
   */
  @Test
  fun `test NERDTree can be spelled in lower case`() {
    val session = Session()

    session.ex("set nerdtree")

    assertEquals(listOf("NERDTree"), session.enabled)
  }
}
