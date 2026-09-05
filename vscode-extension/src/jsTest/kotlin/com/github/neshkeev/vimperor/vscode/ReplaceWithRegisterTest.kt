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
import com.maddyhome.idea.vim.command.MappingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first of IdeaVim's bundled extensions to run in VS Code.
 *
 * `gr{motion}` replaces the text a motion covers with a register's contents, and - the part that
 * makes it worth having rather than being `viw"0p` - leaves the register alone, so the same text
 * can be pasted over and over.
 *
 * It is here because it is the one extension already written against the thin API, which is the
 * only one of IdeaVim's two extension systems that is not IntelliJ-shaped. It moved from
 * `src/main/java` to `vim-engine/src/commonMain` unchanged apart from a `runBlocking` that wrapped
 * a function which is not suspend, and now compiles to both hosts from one source.
 *
 * These are behaviour tests rather than wiring tests - `ExtensionProviderTest` covers the wiring -
 * because the question this file exists to answer is whether an extension *does its job* on this
 * host, not whether it registered.
 */
class ReplaceWithRegisterTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      // What a `.ideavimrc` line does: `Plug 'vim-scripts/ReplaceWithRegister'`.
      injector.extensionRegistrator.setOptionByPluginAlias("vim-scripts/ReplaceWithRegister")
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    val content: String get() = fake.document.content
  }

  // ---- it is on at all -------------------------------------------------------------------------

  @Test
  fun `test a Plug line enables it`() {
    Session("one two")

    assertEquals(
      listOf("ReplaceWithRegisterNew"),
      injector.extensionLoader.getEnabledExtensions().map { it.extensionName },
    )
  }

  @Test
  fun `test enabling it twice is not an error`() {
    val session = Session("one two")

    session.host // already enabled once in `init`
    injector.extensionRegistrator.setOptionByPluginAlias("vim-scripts/ReplaceWithRegister")

    assertEquals(1, injector.extensionLoader.getEnabledExtensions().size)
  }

  // ---- what it does ----------------------------------------------------------------------------

  @Test
  fun `test gr over a word replaces it with the register`() {
    val session = Session("one two three")

    // `yiw` rather than `yw`: `yw` takes the trailing space with it, and `gr` would then put the
    // space back too - correctly, and not what this test is about.
    session.type("yiw")
    session.type("wgriw")

    assertEquals("one one three", session.content)
  }

  @Test
  fun `test grr replaces the whole line`() {
    val session = Session("one\ntwo\n")

    session.type("yy")
    session.type("jgrr")

    assertEquals("one\none\n", session.content)
  }

  @Test
  fun `test gr in visual replaces the selection`() {
    val session = Session("one two three")

    session.type("yiw")
    session.type("wvegr")

    assertEquals("one one three", session.content)
  }

  /**
   * The whole point of the extension, and the thing plain `p` gets wrong.
   *
   * Pasting over a selection puts the replaced text into the unnamed register, so a second paste
   * pastes what was just overwritten. `gr` does not, which is what makes it usable repeatedly.
   */
  @Test
  fun `test the register survives being used`() {
    val session = Session("aaa bbb ccc")

    session.type("yiw")
    session.type("wgriw")
    session.type("wgriw")

    assertEquals("aaa aaa aaa", session.content, "the register must not pick up what it replaced")
  }

  /**
   * Disabling takes the mappings with it, which is asserted on the mapping rather than on the text:
   * with `gr` unmapped the keys are Vim's own again, and `r` is "replace a character", so the
   * buffer does change - just not by this extension.
   */
  @Test
  fun `test it can be turned off again`() {
    Session("one two three")
    assertTrue(injector.keyGroup.getKeyMapping(MappingMode.NORMAL).hasmapto(injector.parser.parseKeys(RWR_OPERATOR)))

    injector.extensionLoader.disableExtension("ReplaceWithRegisterNew")

    assertTrue(injector.extensionLoader.getEnabledExtensions().isEmpty())
    assertTrue(
      !injector.keyGroup.getKeyMapping(MappingMode.NORMAL).hasmapto(injector.parser.parseKeys(RWR_OPERATOR)),
      "the mapping the extension installed should be gone with it",
    )
  }

  private companion object {
    const val RWR_OPERATOR = "<Plug>ReplaceWithRegisterOperator"
  }
}
