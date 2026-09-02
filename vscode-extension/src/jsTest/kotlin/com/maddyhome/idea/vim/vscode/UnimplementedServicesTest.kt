/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The services this host does not provide, listed from the host's side rather than the engine's.
 *
 * Every other sweep here works from the outside in: press a key, type a command, set an option,
 * call a function, and see what falls over. They share one blind spot, and it took three separate
 * discoveries to name it. Each of them exercises the surface *with no arguments* - a bare `:g`, an
 * `echo split()` with nothing to split, `gx` on a line with no URL - and a service that is only
 * reached once there is a real argument is invisible to all of them. `:g` needed a range marker,
 * `split()` needed a regex service, and both were sitting unimplemented behind a Vim error.
 *
 * This one works from the inside out. `VsCodeInjectorBase` declares every service as a `TODO`, and
 * `VsCodeInjector` overrides the ones that exist; the difference is the list, read out of the two
 * source files the same way `ExCommandsOnlyInIntelliJTest` reads IdeaVim's. Nothing has to be
 * reachable for it to appear here - which is the point, since reachability is what the other four
 * were measuring by accident.
 *
 * Both directions are asserted. Implementing a service has to come with taking it off the list, and
 * a service the engine adds has to be noticed rather than quietly joining it.
 */
class UnimplementedServicesTest {

  @Test
  fun `test the services this host does not provide are the ones listed here`() {
    val root = repositoryRoot()
    assertTrue(root != null, "could not find the repository root, so the sources could not be read")
    val host = "$root/vscode-extension/src/jsMain/kotlin/com/maddyhome/idea/vim/vscode"

    val declared = NOT_PROVIDED.findAll(readText("$host/VsCodeInjectorBase.kt")).map { it.groupValues[1] }
    val overridden = OVERRIDE.findAll(readText("$host/VsCodeInjector.kt")).map { it.groupValues[1] }.toSet()
    val missing = declared.filterNot { it in overridden }.toList().sorted()

    assertTrue(
      declared.count() > 40,
      "only ${declared.count()} services were read out of VsCodeInjectorBase, so this has stopped reading it",
    )
    assertEquals(EXPECTED.trim(), missing.joinToString("\n").trim())
  }

  /**
   * The comparison works from both sides.
   *
   * Without this the test above would pass just as well if the override regex matched nothing - and
   * that failure is silent, because a service that is implemented would simply appear on the list
   * and the list would be updated to match. `motion` is implemented and `spellcheckerService` is not.
   */
  @Test
  fun `test an implemented service is not listed and an unimplemented one is`() {
    val root = repositoryRoot()!!
    val host = "$root/vscode-extension/src/jsMain/kotlin/com/maddyhome/idea/vim/vscode"
    val overridden = OVERRIDE.findAll(readText("$host/VsCodeInjector.kt")).map { it.groupValues[1] }.toSet()

    assertTrue("motion" in overridden, "motion is implemented and should be read as overridden")
    assertTrue("spellcheckerService" !in overridden, "spellcheckerService is not implemented")
    assertTrue("spellcheckerService" in EXPECTED, "spellcheckerService should be on the list")
  }

  private companion object {
    val NOT_PROVIDED = Regex("""override val (\w+):[^\n]*\n\s*get\(\) = TODO\("the VS Code host does not provide""")
    val OVERRIDE = Regex("""override val (\w+):""")

    /**
     * What is left, and why each one is left.
     *
     * Four of these reasons were wrong and have been corrected rather than deleted, because a wrong
     * reason is worse than none: it stops the next person from looking. Two services came off the
     * list when the reason was checked.
     *
     * `extensionLoader` and `jsonExtensionProvider` are the thin-API plugin registry, reached by
     * `:Plug`, `:PlugEnable` and `:IdeaPlug` - so a `.ideavimrc` with a `Plug` line in it hits this,
     * which is not a rare thing to have. They were recorded as blocked behind `modalInput.activate`,
     * and that was about a different part of the extension system: the real blocker is *class
     * loading*. `LazyVimExtension` lives in `vim-engine/src/jvmMain` and resolves a class by name
     * through a `ClassLoader`, which JavaScript does not have. That is the same problem this build
     * already solves for commands, functions and ex commands by generating a registry at build time,
     * so the answer has a known shape.
     *
     * They were also described as how "IdeaVim's twenty-six bundled extensions are found and
     * started". They start exactly one: `ideavim_extensions.json` has a single entry. The other
     * twenty-five use the older `VimExtension` extension point, which is not a `VimInjector` service
     * at all - so it never appears here, and this list understates what is missing. That is the
     * blind spot this whole file exists to have less of, arriving from the one direction it cannot
     * see: a thing that was never a service.
     *
     * The thing to know before starting that work is that the mechanism is not finished upstream
     * either. IdeaVim's single thin-API extension is `ReplaceWithRegisterNew`, its test class is
     * `@Disabled("The test is flaky because of an unknown reason")`, and the two halves of the
     * registration do not meet: the annotation processor writes `ksp-generated/ideavim_extensions
     * .json` while `IjPluginExtensionsScanner` looks for `META-INF/extensions.json`, which is the
     * path a *third-party* plugin uses. So building the JavaScript half now would be building
     * against a moving target with no working example to check it against. The generated-registry
     * shape is the right answer; the time to apply it is when there is one extension that demonstrably
     * runs.
     *
     * `pluginActivator` has no caller anywhere in `vim-engine`. It exists so that IdeaVim can be
     * switched off and on from its status-bar icon; this host's on and off are VS Code's own
     * `activate` and `deactivate`, in `Extension.kt`.
     *
     * `highlightingService` adds a coloured range by request. This was recorded here as `matchadd()`
     * and that was wrong - `matchadd` does not exist anywhere in this repository. Its only caller is
     * `Transaction.addHighlight` in the thin API, so it belongs with the extension services above
     * and is reachable from nowhere else.
     *
     * `searchWindowGroup` and `virtualBufferGroup` are Vim's command-line window - `q:`, `q/` - and
     * the buffers behind it. A real editor buffer that is not a file, holding history, that closes
     * on Enter.
     *
     * `spellcheckerService` is `z=`, `zg`, `]s`. VS Code has no spellchecker; the popular ones are
     * extensions, and an extension cannot ask another extension for a word list.
     *
     * Gone from this list, and why the reasons were wrong:
     *
     * `pluginService` was blocked by nothing. Its three methods are running normal-mode keys,
     * declaring a Vimscript function and adding a command alias - all the engine's, and IdeaVim's
     * implementation was three one-line delegations to a facade whose bodies had no IntelliJ in
     * them. It is `VimPluginServiceBase` in `vim-engine` now and both hosts share it.
     *
     * `fallbackWindow` was described as the editor for "no window at all", which reads like a corner
     * nobody reaches. Every scope in the thin API resolves its editor as
     * `projectId?.let { getSelectedEditor(it) } ?: injector.fallbackWindow`, and a plugin's `init`
     * runs with a null project id by construction - there is no editor yet while a plugin declares
     * its mappings. It is the *first* thing the extension chain asks for, not the last.
     */
    val EXPECTED = """
      extensionLoader
      highlightingService
      jsonExtensionProvider
      pluginActivator
      searchWindowGroup
      spellcheckerService
      virtualBufferGroup
    """.trimIndent()
  }
}
