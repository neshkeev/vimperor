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
 * `VimEverywhere`, which is a switch and nothing else - and the switch is the whole test.
 *
 * Every other extension can be tested by pressing a key. This one cannot, because the keys it is
 * about are pressed where this extension never sees them: a key in the sidebar, the Problems panel
 * or the Source Control tree does not reach a VS Code extension at all. Those keys are declared in
 * `package.json` and run by VS Code, and what decides whether they apply is the context key this
 * extension sets.
 *
 * So what can be checked here is that the switch is a switch: enabling it registers nothing, and
 * the host reports it in the one way the manifest depends on. What the keys do is
 * [VimEverywhereManifestTest]'s, and whether VS Code honours the `when` clause is VS Code's.
 */
class VimEverywhereTest {

  private class Session {
    val fake = FakeEditor("one\ntwo\n")
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      commandsExecuted().length = 0
    }

    fun enable() {
      injector.extensionRegistrator.setOptionByPluginAlias("VimEverywhere")
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }

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
      listOf("VimEverywhere"),
      injector.extensionLoader.getEnabledExtensions().map { it.extensionName },
    )
  }

  /** The manifest's `when` clauses are driven by this, so a wrong answer arms or disarms the keys. */
  @Test
  fun `test the host reports whether it is on`() {
    val session = Session()

    assertTrue(!session.host.isExtensionEnabled("VimEverywhere"))
    session.enable()
    assertTrue(session.host.isExtensionEnabled("VimEverywhere"))

    injector.extensionLoader.disableExtension("VimEverywhere")
    assertTrue(!session.host.isExtensionEnabled("VimEverywhere"))
  }

  /**
   * It takes no keys away from the editor, which is not an oversight - see the engine file.
   *
   * `j`, `k`, `h` and `l` are the keys this extension is *about*, and in the editor they have to
   * stay Vim's. That they do is the check worth making, because the alternative design - install
   * mappings and hope - would have broken exactly these four and nothing else.
   */
  @Test
  fun `test the keys it is about are untouched in the editor`() {
    val session = Session()
    session.enable()

    session.type("jlll")

    // `j` to the second line, then `l` three times clamped at its last character - which is Vim,
    // and is what it would not be if this extension had taken those keys.
    assertEquals(6, session.host.editorFor(session.fake).primaryCaret().offset, "j then lll")
    assertTrue(commandsSent().isEmpty(), "it sends nothing; VS Code runs its keys itself")
  }

  /**
   * `set VimEverywhere` is the *only* way anyone turns this on - it has no repository and no `Plug`
   * line anywhere - so the option is not a nicety here, it is the interface.
   */
  @Test
  fun `test set VimEverywhere enables it`() {
    val session = Session()

    session.ex("set VimEverywhere")

    assertEquals(
      listOf("VimEverywhere"),
      injector.extensionLoader.getEnabledExtensions().map { it.extensionName },
    )
  }

  @Test
  fun `test set noVimEverywhere turns it off again`() {
    val session = Session()
    session.ex("set VimEverywhere")

    session.ex("set noVimEverywhere")

    assertTrue(injector.extensionLoader.getEnabledExtensions().isEmpty())
    assertTrue(!session.host.isExtensionEnabled("VimEverywhere"))
  }
}

private fun commandsExecuted(): dynamic = js("require('vscode')").commands.executed

private fun commandsSent(): List<String> {
  val raw = commandsExecuted()
  return (0 until (raw.length as Int)).map { raw[it] as String }
}
