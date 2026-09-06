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
 * Escape outside the editor puts the cursor back in the document, the way IntelliJ does.
 *
 * ## Why this is a manifest binding and not engine code
 *
 * A key pressed in the sidebar or the panel never reaches an extension - `type` is the editor's
 * command and the Explorer is not an editor, which is the same wall the NERDTree keys met. So this
 * is `package.json`, and the only part of it decided at runtime is the `when` clause.
 *
 * ## Why it cannot be "the panel handles it, then we do"
 *
 * IntelliJ chains: the focused component sees Escape, and if it does nothing the focus goes to the
 * editor. VS Code resolves **one** keybinding per key - among those whose `when` matches, the last
 * registered wins, and an extension's beat the built-ins - so there is no falling through. The
 * chain has to be written as exclusions instead, and each one is a context where Escape already has
 * a job worth keeping:
 *
 *  - `!terminalFocus`, because Escape belongs to the shell. Vim in a terminal would be unusable.
 *  - `!inputFocus`, because Escape clears a Search box, a tree filter, or a rename field.
 *
 * What is left is the read-only half of the workbench - a tree, a list, the Output view, Problems -
 * where Escape does nothing today, which is exactly where returning to the editor is free.
 */
class EscapeToEditorManifestTest {

  private fun bindings(): List<dynamic> {
    val manifest = JSON.parse<dynamic>(readText("${repositoryRoot()!!}/vscode-extension/package.json"))
    return (manifest.contributes.keybindings as Array<dynamic>).toList()
  }

  private fun binding(): dynamic = bindings().single { it.command == VsCodeCommands.FOCUS_EDITOR }

  private fun clause(): String = binding().`when` as String

  @Test
  fun `test escape outside the editor returns to the document`() {
    assertEquals("escape", binding().key)
  }

  /**
   * Gated, so it can be turned off.
   *
   * This one binding changes what Escape does across the whole workbench, which is more than any
   * other binding here claims. `vimperor.escapeReturnsToEditor` is a setting as well as a context
   * key, and a user who wants VS Code's Escape back sets it false.
   */
  @Test
  fun `test the binding is gated on the setting`() {
    assertTrue(
      clause().startsWith("vimperor.escapeReturnsToEditor"),
      "ungated, this would change Escape for everyone who installs the extension",
    )
  }

  @Test
  fun `test the terminal keeps its own escape`() {
    assertTrue(
      clause().contains("!terminalFocus"),
      "Escape has to reach the shell - vim in a terminal is the case that matters",
    )
  }

  @Test
  fun `test a text input keeps its own escape`() {
    assertTrue(
      clause().contains("!inputFocus"),
      "Escape clears a Search box or a rename field, and that is worth more than a focus change",
    )
  }

  /** It applies outside the editor and nowhere else - the three areas a tool window can be in. */
  @Test
  fun `test it applies only outside the editor`() {
    val clause = clause()
    listOf("sideBarFocus", "panelFocus", "auxiliaryBarFocus").forEach {
      assertTrue(clause.contains(it), "$it is missing, so that part of the workbench is stranded")
    }
  }

  /**
   * Last in the array, which decides a real case.
   *
   * The Output view is a read-only editor and reports `editorTextFocus` along with `panelFocus`, so
   * this binding and the `escape` that hands `<Esc>` to the engine can both match. VS Code takes
   * the later of two bindings from the same extension, and the one that gets out of the Output
   * panel is the one worth having.
   */
  @Test
  fun `test it is declared after the engine's own escape`() {
    val all = bindings()
    val ours = all.indexOfFirst { it.command == VsCodeCommands.FOCUS_EDITOR }
    val engine = all.indexOfFirst { it.key == "escape" && it.command == "vimperor.key" }

    assertTrue(engine in 0 until ours, "the engine's escape is at $engine and this one at $ours")
  }

  /** And the setting it is gated on is declared, or nothing could turn it off. */
  @Test
  fun `test the setting is declared`() {
    val manifest = JSON.parse<dynamic>(readText("${repositoryRoot()!!}/vscode-extension/package.json"))
    val declared = js("Object.keys")(manifest.contributes.configuration.properties) as Array<String>

    assertTrue("vimperor.escapeReturnsToEditor" in declared, "declared: ${declared.toList()}")
  }

  /** The command it sends is one activation asks the real window about. */
  @Test
  fun `test the command it sends is checked at activation`() {
    assertTrue(VsCodeCommands.FOCUS_EDITOR in VsCodeCommands.all)
  }
}
