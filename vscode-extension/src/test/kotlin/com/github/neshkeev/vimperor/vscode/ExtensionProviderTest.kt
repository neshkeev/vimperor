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
import com.maddyhome.idea.vim.extension.ExtensionBean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The host half of the extension system, which was three `TODO`s until now.
 *
 * `VsCodeInjectorBase` said `TODO("the VS Code host does not provide extensionRegistrator yet")`
 * for all three of `extensionRegistrator`, `jsonExtensionProvider` and `extensionLoader`, so any
 * path that reached them threw `NotImplementedError` - `:Plug` among them. Nothing is bundled yet,
 * and that is deliberate: the wiring is what was missing, and it is worth having complete and empty
 * before an extension is put through it, because then a missing extension fails as a missing
 * extension rather than as a missing host.
 */
class ExtensionProviderTest {

  private class Session {
    val fake = FakeEditor("one two three")
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

  }

  // ---- the seams answer at all ---------------------------------------------------------------

  @Test
  fun `test the injector provides all three extension services`() {
    Session()

    // Reading them used to throw. That is the whole of this assertion.
    assertTrue(injector.jsonExtensionProvider.getAllExtensions().isNotEmpty(), "the catalogue answers")
    assertTrue(injector.extensionLoader.getEnabledExtensions().isEmpty(), "and nothing is on until asked")
    assertNull(injector.extensionRegistrator.getExtensionNameByAlias("nothing/at-all"))
  }

  // ---- the catalogue -------------------------------------------------------------------------

  @Test
  fun `test an extension can be added to the catalogue and found again`() {
    Session()
    val bean = ExtensionBean("demo", VsCodeExtensions.PLUGIN_ID, "init", "")

    injector.jsonExtensionProvider.addExtension(bean)

    assertEquals(bean, injector.jsonExtensionProvider.getExtension("demo"))
    assertTrue(bean in injector.jsonExtensionProvider.getExtensionsForPlugin(VsCodeExtensions.PLUGIN_ID))
  }

  // There was a test here asserting that `getBundledExtensions()` returns the same list as
  // `getAllExtensions()`. It could not fail: the implementation is `= getAllExtensions()`, so it
  // asserted that the code is itself. The claim it was making - that this host has no second kind
  // of extension - is a design statement and belongs in the KDoc on the override, where it is.

  @Test
  fun `test removing a plugin takes its extensions with it`() {
    Session()
    injector.jsonExtensionProvider.addExtension(ExtensionBean("demo", "someone.else", "init", ""))
    injector.jsonExtensionProvider.addExtension(ExtensionBean("ours", VsCodeExtensions.PLUGIN_ID, "init", ""))

    injector.jsonExtensionProvider.removeExtensionForPlugin("someone.else")

    val names = injector.jsonExtensionProvider.getAllExtensions().map { it.extensionName }
    assertTrue("ours" in names && "demo" !in names, "only the other plugin's extension goes")
  }

  // ---- enabling ------------------------------------------------------------------------------

  /**
   * An extension in the catalogue with no function behind it must not be reported as enabled.
   *
   * This is the case IntelliJ cannot have and this host can: over there a bean carries a class name
   * and the classloader either finds it or the plugin is broken. Here the bean is one half and the
   * compiled-in function is the other, and the halves can disagree - so the loader checks before it
   * records, or `:set` would report success for an extension that did nothing.
   */
  @Test
  fun `test enabling an extension with nothing behind it does not report success`() {
    Session()
    val bean = ExtensionBean("not-compiled-in", VsCodeExtensions.PLUGIN_ID, "init", "")
    injector.jsonExtensionProvider.addExtension(bean)

    injector.extensionLoader.enableExtension(bean)

    assertTrue(
      injector.extensionLoader.getEnabledExtensions().none { it.extensionName == "not-compiled-in" },
      "an extension with no function compiled in is not enabled, whatever the catalogue says",
    )
  }

  @Test
  fun `test disabling something that was never enabled is not an error`() {
    Session()

    injector.extensionLoader.disableExtension("never-enabled")

    assertTrue(injector.extensionLoader.getEnabledExtensions().isEmpty())
  }

  // ---- the alias table, which is what a borrowed vimrc names -----------------------------------

  /**
   * A `.vimrc` says `Plug 'tpope/vim-surround'`; the extension is called `surround`. Resolving one
   * to the other is the whole of `VimExtensionRegistrator`, and it is why a config written for Vim
   * can enable an extension without being rewritten.
   */
  @Test
  fun `test a plugin line resolves to the extension name`() {
    Session()
    val registrator = injector.extensionRegistrator

    assertEquals("surround", registrator.getExtensionNameByAlias("tpope/vim-surround"))
    assertEquals("commentary", registrator.getExtensionNameByAlias("tpope/vim-commentary"))
    assertEquals("targets", registrator.getExtensionNameByAlias("wellle/targets.vim"))
  }

  @Test
  fun `test the quotes a vimrc writes are not part of the name`() {
    Session()

    assertEquals("surround", injector.extensionRegistrator.getExtensionNameByAlias("'tpope/vim-surround'"))
    assertEquals("surround", injector.extensionRegistrator.getExtensionNameByAlias("\"tpope/vim-surround\""))
    assertEquals("surround", injector.extensionRegistrator.getExtensionNameByAlias("  tpope/vim-surround  "))
  }

  /** Vim plugins are named by repository, and the author half is not part of the identity. */
  @Test
  fun `test the author of the repository does not matter`() {
    Session()

    assertEquals("surround", injector.extensionRegistrator.getExtensionNameByAlias("someone-else/vim-surround"))
    assertEquals("surround", injector.extensionRegistrator.getExtensionNameByAlias("vim-surround"))
  }

  /**
   * Every bundled extension has to be reachable by the name its upstream repository actually has,
   * and this is the test that says so, because nothing else would.
   *
   * The first version of the alias table was written from the documentation rather than from the
   * plugin's own `IdeaVIM.ideavim-frontend.xml`, and got two of them wrong: `camelcasemotion` for an
   * extension named `CamelCaseMotion`, and `miniai` for one named `mini-ai`. Both failures are
   * silent - an alias that resolves to a name no extension has fails exactly the way an unknown
   * alias does - so `Plug 'bkad/CamelCaseMotion'` did nothing at all and said nothing about it.
   *
   * The repositories are spelled out rather than derived from [VsCodeExtensions.BUNDLED], since a
   * test that derived them would agree with whatever the table said.
   */
  @Test
  fun `test each bundled extension is reachable by its own repository name`() {
    Session()
    val registrator = injector.extensionRegistrator

    assertEquals("ReplaceWithRegisterNew", registrator.getExtensionNameByAlias("vim-scripts/ReplaceWithRegister"))
    assertEquals("vim-paragraph-motion", registrator.getExtensionNameByAlias("dbakker/vim-paragraph-motion"))
    assertEquals("textobj-entire", registrator.getExtensionNameByAlias("kana/vim-textobj-entire"))
    assertEquals("mini-ai", registrator.getExtensionNameByAlias("echasnovski/mini.ai"))
    assertEquals("CamelCaseMotion", registrator.getExtensionNameByAlias("bkad/CamelCaseMotion"))
    assertEquals("indentwise", registrator.getExtensionNameByAlias("jeetsukumaran/vim-indentwise"))
    assertEquals("easymotion", registrator.getExtensionNameByAlias("easymotion/vim-easymotion"))
  }

  /** And the resolved name has to name something, which is the half an alias table cannot check. */
  @Test
  fun `test each of those names then enables the extension`() {
    Session()

    val repositories = listOf(
      "vim-scripts/ReplaceWithRegister",
      "dbakker/vim-paragraph-motion",
      "kana/vim-textobj-entire",
      "echasnovski/mini.ai",
      "bkad/CamelCaseMotion",
      "jeetsukumaran/vim-indentwise",
      "easymotion/vim-easymotion",
    )
    repositories.forEach {
      assertTrue(injector.extensionRegistrator.setOptionByPluginAlias(it), "`Plug '$it'` enabled nothing")
    }

    assertEquals(repositories.size, injector.extensionLoader.getEnabledExtensions().size)
  }

  @Test
  fun `test a plugin this fork has never heard of resolves to nothing`() {
    Session()

    assertNull(injector.extensionRegistrator.getExtensionNameByAlias("someone/vim-does-not-exist"))
  }

  /**
   * The aliases are kept for extensions that are not bundled yet, and this is why: setting one has
   * to fail because the extension is absent, not because the name was never recognised. The two
   * fail identically to a user and differently to whoever adds the extension.
   *
   * `matchit` is the example, and the list of predecessors is the reason to expect it to be the
   * last one: `surround`, then `yankring`, then `NERDTree`, each of which looked unportable until
   * it was looked at. (`VimEverywhere` went the other way - it was bundled for a while and then
   * taken out, because what could be ported was not the half the name is for.) `matchit` is
   * different in kind. It makes `%` jump between `if` and `endif` and between an opening and
   * closing HTML tag, which needs to know what a *token* is - and the one thing VS Code will tell
   * an extension about a file's structure is where its symbols are, which is functions and classes
   * and nothing smaller.
   */
  @Test
  fun `test a known alias with no extension behind it still fails`() {
    Session()

    assertEquals("matchit", injector.extensionRegistrator.getExtensionNameByAlias("vim-matchit"))
    assertTrue(!injector.extensionRegistrator.setOptionByPluginAlias("vim-matchit"))
  }
}
