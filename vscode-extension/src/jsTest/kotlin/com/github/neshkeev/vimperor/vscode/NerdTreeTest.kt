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
 * `NERDTree`, which is two things sharing a name and only one of them a port.
 *
 * The six ex commands say "show me the file tree" and nothing more, and both hosts have one - so
 * they moved to `vim-engine` and say `injector.fileTree` where IdeaVim wrote IntelliJ action ids.
 * That half is ordinary code and is tested here like any other: the command, the VS Code command it
 * sends, and the teardown.
 *
 * The other half is thirty keys that apply while the cursor is inside the tree, and they are not
 * here because they cannot be. A key pressed in the sidebar never reaches this extension - `type`
 * is the editor's command and the Explorer is not an editor - so what VS Code offers instead is
 * `package.json` keybindings with a `when` clause. [NerdTreeManifestTest] is what can be checked
 * about those, which is that they are declared, gated, and gated on the right thing.
 */
class NerdTreeTest {

  private class Session {
    val fake = FakeEditor("one\n")
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      commandsExecuted().length = 0
    }

    fun enable() {
      injector.extensionRegistrator.setOptionByPluginAlias("preservim/nerdtree")
    }

    fun ex(command: String) {
      ":$command".forEach { host.type(fake, it.toString()) }
      host.key(fake, "<CR>")
    }
  }

  @Test
  fun `test a Plug line enables it`() {
    val session = Session()

    session.enable()

    assertEquals(
      listOf("NERDTree"),
      injector.extensionLoader.getEnabledExtensions().map { it.extensionName },
    )
  }

  @Test
  fun `test the six commands reach the Explorer`() {
    val session = Session()
    session.enable()

    session.ex("NERDTree")
    session.ex("NERDTreeFocus")
    session.ex("NERDTreeToggle")
    session.ex("NERDTreeClose")
    session.ex("NERDTreeFind")
    session.ex("NERDTreeRefreshRoot")

    assertEquals(
      listOf(
        VsCodeCommands.FOCUS_EXPLORER,
        VsCodeCommands.FOCUS_EXPLORER,
        VsCodeCommands.TOGGLE_SIDEBAR,
        VsCodeCommands.CLOSE_SIDEBAR,
        VsCodeCommands.REVEAL_IN_EXPLORER,
        VsCodeCommands.REFRESH_EXPLORER,
      ),
      commandsSent(),
    )
  }

  /**
   * `:NERDTree` and `:NERDTreeFocus` are the same command here, as they are in IdeaVim. Real
   * NERDTree's `:NERDTree` opens a tree rooted at a directory you can name, and neither host has a
   * tree whose root Vim can set.
   */
  @Test
  fun `test NERDTree and NERDTreeFocus do the same thing`() {
    val session = Session()
    session.enable()

    session.ex("NERDTree")
    session.ex("NERDTreeFocus")

    assertEquals(listOf(VsCodeCommands.FOCUS_EXPLORER, VsCodeCommands.FOCUS_EXPLORER), commandsSent())
  }

  /**
   * A command alias is held by the command group under its name, and the loader's teardown removes
   * mappings and listeners by owner - so without `disposeNerdTree` in `VsCodeExtensions.TEARDOWN`,
   * `:NERDTreeToggle` would keep working after `set noNERDTree`.
   */
  @Test
  fun `test disabling it takes the commands away`() {
    val session = Session()
    session.enable()
    session.ex("NERDTree")
    assertEquals(1, commandsSent().size)

    injector.extensionLoader.disableExtension("NERDTree")
    commandsExecuted().length = 0
    session.ex("NERDTree")

    assertTrue(commandsSent().isEmpty(), "the alias outlived the extension")
  }

  @Test
  fun `test the commands are not there until it is enabled`() {
    val session = Session()

    session.ex("NERDTreeToggle")

    assertTrue(commandsSent().isEmpty())
  }

  /** The `when` clause in the manifest is driven by this, so a wrong answer would arm `d`. */
  @Test
  fun `test the host reports whether it is on`() {
    val session = Session()

    assertTrue(!session.host.isExtensionEnabled("NERDTree"))
    session.enable()
    assertTrue(session.host.isExtensionEnabled("NERDTree"))

    injector.extensionLoader.disableExtension("NERDTree")
    assertTrue(!session.host.isExtensionEnabled("NERDTree"))
  }
}

private fun commandsExecuted(): dynamic = js("require('vscode')").commands.executed

private fun commandsSent(): List<String> {
  val raw = commandsExecuted()
  return (0 until (raw.length as Int)).map { raw[it] as String }
}
