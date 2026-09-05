/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `youcompleteme`'s two keys, which live in `package.json` because the engine cannot see the popup.
 *
 * The third extension whose VS Code half is a manifest binding, and the first where that is the
 * whole extension rather than part of it. What can be checked without a window is what the clauses
 * say, and here they carry more weight than usual: `<Tab>` is the most heavily used key in the
 * editor, and the pair of clauses is the only thing keeping this feature inside the moment it
 * belongs to.
 */
class YouCompleteMeManifestTest {

  @Test
  fun `test both keys are gated on the popup and on the extension`() {
    val bound = bindings().associate { (it.key as String) to (it.`when` as String) }

    assertEquals(mapOf("tab" to GATE, "shift+tab" to GATE), bound)
  }

  @Test
  fun `test they cycle rather than accept`() {
    val bound = bindings().associate { (it.key as String) to (it.command as String) }

    assertEquals(
      mapOf("tab" to "selectNextSuggestion", "shift+tab" to "selectPrevSuggestion"),
      bound,
      "accepting is what VS Code does by default; cycling is the whole of this extension",
    )
  }

  /**
   * The half that is easy to lose and would be a bad day: `<Tab>` with no popup showing has to stay
   * the engine's, or it stops indenting.
   *
   * The two clauses are exact complements - the engine's binding carries `!suggestWidgetVisible`
   * and this one carries `suggestWidgetVisible` - so they can never both match, whether or not the
   * extension is enabled. That is what makes this safe to bundle at all.
   */
  @Test
  fun `test the engine keeps Tab whenever there is no popup`() {
    val all = allBindings()
    val toTheEngine = all.filter { (it.command as String) == "vimperor.key" && (it.key as String) == "tab" }

    assertEquals(1, toTheEngine.size)
    assertTrue(
      (toTheEngine[0].`when` as String).contains("!suggestWidgetVisible"),
      "the engine's Tab has to stand down exactly when this one stands up",
    )
  }

  /**
   * And `<Tab>` must still stand down for Copilot. This extension claims the key in a state ghost
   * text does not use - a suggest popup and an inline suggestion are different things - but the
   * guard is worth restating here, because "youcompleteme took my Tab" and "Copilot stopped
   * working" would be the same bug report.
   */
  @Test
  fun `test the ghost-text guard is untouched`() {
    val engineTab = allBindings().first { (it.command as String) == "vimperor.key" && (it.key as String) == "tab" }

    for (contextKey in listOf("inlineSuggestionVisible", "inlineEditIsVisible")) {
      assertTrue((engineTab.`when` as String).contains("!$contextKey"), "lost the $contextKey guard")
    }
  }

  @Test
  fun `test the commands are on the list the real window is asked about`() {
    val sent = bindings().map { it.command as String }.toSet()

    assertTrue(sent.all { it in VsCodeCommands.all }, "not asked about at activation: ${sent - VsCodeCommands.all.toSet()}")
  }

  /** The reading has to be able to fail, or every assertion above passes over an empty list. */
  @Test
  fun `test the bindings are actually read`() {
    assertEquals(2, bindings().size)
  }

  private fun allBindings(): List<dynamic> {
    val manifest = JSON.parse<dynamic>(readText("${repositoryRoot()!!}/vscode-extension/package.json"))
    return (manifest.contributes.keybindings as Array<dynamic>).toList()
  }

  private fun bindings(): List<dynamic> =
    allBindings().filter { (it.`when` as? String)?.contains("vimperor.youcompleteme") == true }
}

private const val GATE = "editorTextFocus && suggestWidgetVisible && vimperor.youcompleteme"
