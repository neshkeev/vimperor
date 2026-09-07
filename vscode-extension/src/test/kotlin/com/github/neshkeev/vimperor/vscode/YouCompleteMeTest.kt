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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `youcompleteme`: `<Tab>` walks the completion list instead of accepting from it.
 *
 * The extension is one sentence and this host implements all of it, which is what makes it worth
 * bundling where `VimEverywhere` was not: there, the manifest could carry two of four features and
 * not the one the name is for; here it carries the only feature there is.
 *
 * The two keys themselves are `package.json`'s - a completion popup is not a text editor, and
 * VS Code will not even tell an extension one is open. So this file covers the switch and the
 * thing that must *not* change: `<Tab>` with no popup showing is still Vim's, and still indents.
 * [YouCompleteMeManifestTest] covers the bindings.
 */
class YouCompleteMeTest {

  private class Session(text: String = "one\n") {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      injector.extensionLoader.getEnabledExtensions().map { it.extensionName }.forEach {
        injector.extensionLoader.disableExtension(it)
      }
      commandsExecuted().length = 0
    }

    fun ex(command: String) {
      ":$command".forEach { host.type(fake, it.toString()) }
      host.key(fake, "<CR>")
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val content: String get() = fake.document.content
  }

  /**
   * `set youcompleteme` is the only way in - IdeaVim gives this one no `Plug` aliases at all, so
   * the option is the whole interface. It is also the fix that came out of the `VimEverywhere`
   * attempt; without it this extension would be unreachable.
   */
  @Test
  fun `test set youcompleteme enables it`() {
    val session = Session()

    session.ex("set youcompleteme")

    assertEquals(
      listOf("youcompleteme"),
      injector.extensionLoader.getEnabledExtensions().map { it.extensionName },
    )
    assertTrue(session.host.isExtensionEnabled("youcompleteme"))
  }

  @Test
  fun `test set noyoucompleteme turns it off again`() {
    val session = Session()
    session.ex("set youcompleteme")

    session.ex("set noyoucompleteme")

    assertTrue(!session.host.isExtensionEnabled("youcompleteme"))
  }

  /**
   * The regression this could most easily cause. IdeaVim's version takes `<Tab>` away from the IDE
   * by editing `'lookupkeys'`, and a port that installed an insert-mode mapping here would take it
   * away from `VimEditorTab` - which is what indents a line, and is not a thing to break for a
   * feature that only applies while a popup is open.
   */
  @Test
  fun `test Tab with no popup showing still indents`() {
    val session = Session("one\n")
    session.ex("set youcompleteme")

    session.type("i")
    session.key("<Tab>")

    assertEquals("    one\n", session.content)
    assertTrue(commandsSent().isEmpty(), "cycling is VS Code's to run, and only when it has a popup")
  }

  @Test
  fun `test it registers nothing for the engine to hold`() {
    val session = Session()

    session.ex("set youcompleteme")

    // Whatever it did, it did not take a key: `<Tab>` in insert mode is still the host command that
    // indents, and `<S-Tab>` is still unmapped.
    assertTrue(commandsSent().isEmpty())
  }
}

private fun commandsExecuted(): dynamic = js("require('vscode')").commands.executed

private fun commandsSent(): List<String> {
  val raw = commandsExecuted()
  return (0 until (raw.length as Int)).map { raw[it] as String }
}
