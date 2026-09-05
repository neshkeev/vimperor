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
 * `VimEverywhere`'s keys, which live in `package.json` because they can live nowhere else.
 *
 * The same situation as `NerdTreeManifestTest`, one step wider. NERDTree's keys apply in the file
 * tree; these apply in *every* list and tree in the workbench, and `<C-W>hjkl` applies wherever the
 * focus is not an editor. A key pressed in any of those places never reaches this extension, so the
 * manifest is the implementation and this is the test of it.
 *
 * What is checked is what can be: the bindings are declared, they are the documented set, they run
 * VS Code's commands rather than the engine's, and every one is gated. The gate matters more here
 * than anywhere: `<C-W>` alone is *close editor* in VS Code, and `h`, `j`, `k` and `l` are how you
 * type in a list's search-as-you-type. An ungated binding would take all of that from every user of
 * this extension.
 */
class VimEverywhereManifestTest {

  @Test
  fun `test every binding is gated on the extension being enabled`() {
    val ungated = bindings().filterNot { (it.`when` as String).contains("vimperor.vimeverywhere") }

    assertTrue(ungated.isEmpty(), "these would apply to everyone: ${ungated.map { it.key as String }}")
  }

  /**
   * Two scopes, and they are not interchangeable.
   *
   * The list keys are `listFocus`, because `j` has to mean "down a row" only where there are rows -
   * bound any wider it would fight ordinary typing. `<C-W>` is `!editorTextFocus`, because its
   * whole purpose is to work where the list keys do not: in the terminal, in a webview, on a
   * focused panel that is not a list. Both carry `!inputFocus`, without which these keys would be
   * stolen from every search box in the workbench.
   */
  @Test
  fun `test the two scopes are the ones intended`() {
    val scopes = bindings().groupBy({ it.`when` as String }, { it.key as String })

    assertEquals(
      mapOf(
        "listFocus && !inputFocus && vimperor.vimeverywhere" to LIST_KEYS.keys.toList().sorted(),
        "!editorTextFocus && !inputFocus && vimperor.vimeverywhere" to PANE_KEYS.keys.toList().sorted(),
      ),
      scopes.mapValues { it.value.sorted() },
    )
  }

  @Test
  fun `test the bindings are the ones this host claims to support`() {
    val bound = bindings().associate { (it.key as String) to (it.command as String) }

    assertEquals((LIST_KEYS + PANE_KEYS).toList().sortedBy { it.first }.toMap(), bound.toList().sortedBy { it.first }.toMap())
  }

  /** Not `vimperor.key`: the engine never sees a key pressed outside an editor. */
  @Test
  fun `test they go to VS Code rather than to the engine`() {
    assertTrue(bindings().none { (it.command as String).startsWith("vimperor.") })
  }

  @Test
  fun `test the commands they send are on the list the real window is asked about`() {
    val sent = bindings().map { it.command as String }.toSet()

    assertTrue(sent.all { it in VsCodeCommands.all }, "not asked about at activation: ${sent - VsCodeCommands.all.toSet()}")
  }

  /**
   * `<C-W>` inside an editor still belongs to the engine, which is what makes `<C-W>s` and
   * `<C-W>v` work. These bindings must not claim it back: they are for the case where there is no
   * editor to hand the key to.
   */
  @Test
  fun `test C-W in the editor is still the engine's`() {
    val manifest = JSON.parse<dynamic>(readText("${repositoryRoot()!!}/vscode-extension/package.json"))
    val all = manifest.contributes.keybindings as Array<dynamic>
    val editorCtrlW = all.filter { (it.key as String) == "ctrl+w" }

    assertEquals(1, editorCtrlW.size)
    assertEquals("vimperor.key", editorCtrlW[0].command as String)
    assertEquals("editorTextFocus", editorCtrlW[0].`when` as String)
  }

  /** The reading has to be able to fail, or every assertion above passes over an empty list. */
  @Test
  fun `test the bindings are actually read`() {
    assertEquals(LIST_KEYS.size + PANE_KEYS.size, bindings().size)
  }

  private fun bindings(): List<dynamic> {
    val manifest = JSON.parse<dynamic>(readText("${repositoryRoot()!!}/vscode-extension/package.json"))
    val all = manifest.contributes.keybindings as Array<dynamic>
    return all.filter { (it.`when` as? String)?.contains("vimperor.vimeverywhere") == true }
  }
}

/**
 * Vim keys in any list or tree - the Explorer, Search, Problems, Source Control, Testing, Outline,
 * and any tree a third-party extension contributes.
 *
 * `h` and `l` collapse and expand rather than moving a column, which is where this parts company
 * with the IntelliJ version: `TableEverywhere` gives them to a `JTable`'s columns, and VS Code's
 * lists have nesting rather than columns. Collapse and expand is what the nesting affords, and it
 * is what every other list-plus-Vim binding people write for VS Code uses.
 *
 * Absent, and not by oversight: `0` and `$` (columns again), and `/`, whose equivalent is VS Code's
 * own list find - already on `ctrl+f` and already type-to-search in most lists.
 */
private val LIST_KEYS: Map<String, String> = mapOf(
  "j" to "list.focusDown",
  "k" to "list.focusUp",
  "h" to "list.collapse",
  "l" to "list.expand",
  "g g" to "list.focusFirst",
  "shift+g" to "list.focusLast",
  "o" to "list.select",
  "ctrl+d" to "list.focusPageDown",
  "ctrl+u" to "list.focusPageUp",
)

/**
 * `<C-W>hjkl` between panes, in all three spellings IdeaVim's `ToolWindowNavEverywhere` accepts -
 * `<C-W>h`, `<C-W><C-H>` and `<C-W><Left>`.
 *
 * `workbench.action.navigate*` rather than `focus*Group`: the group commands move between editor
 * groups and stop at the edge of the editor area, and crossing that edge - from the Explorer to the
 * editor, from the editor to the terminal - is the entire point.
 */
private val PANE_KEYS: Map<String, String> = mapOf(
  "ctrl+w h" to "workbench.action.navigateLeft",
  "ctrl+w ctrl+h" to "workbench.action.navigateLeft",
  "ctrl+w left" to "workbench.action.navigateLeft",
  "ctrl+w j" to "workbench.action.navigateDown",
  "ctrl+w ctrl+j" to "workbench.action.navigateDown",
  "ctrl+w down" to "workbench.action.navigateDown",
  "ctrl+w k" to "workbench.action.navigateUp",
  "ctrl+w ctrl+k" to "workbench.action.navigateUp",
  "ctrl+w up" to "workbench.action.navigateUp",
  "ctrl+w l" to "workbench.action.navigateRight",
  "ctrl+w ctrl+l" to "workbench.action.navigateRight",
  "ctrl+w right" to "workbench.action.navigateRight",
)
