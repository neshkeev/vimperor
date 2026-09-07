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
 * The file-tree keys, which live in `package.json` because they can live nowhere else.
 *
 * Everything else in this port is installed at runtime by the engine, and everything else can be
 * tested by pressing a key. These cannot. A key pressed in the sidebar never reaches this
 * extension - `type` is the editor's command and the Explorer is not an editor - so `j`, `o`, `d`
 * and the rest are declared in the manifest and handled by VS Code, and the only thing left to
 * decide at runtime is a `when` clause.
 *
 * That makes the manifest the implementation, and this the test of it. Three things can be checked
 * without a window, and the third is the one that matters most:
 *
 *  - the keys are declared at all;
 *  - each maps to a VS Code command rather than to `vimperor.key`, which would go nowhere;
 *  - **every one of them is gated on `vimperor.nerdtree`**. `d` in the Explorer deletes a file. An
 *    ungated binding would arm that for everyone who installs this extension, whether or not they
 *    ever wrote `set NERDTree`.
 *
 * What cannot be checked here is whether the command ids exist. `VsCodeCommands.verify` asks the
 * real window that question at activation for the ids this extension sends; these are sent by VS
 * Code on our behalf, so they are in [ALSO_VERIFIED] to be asked about alongside them.
 */
class NerdTreeManifestTest {

  @Test
  fun `test every file-tree binding is gated on the extension being enabled`() {
    val ungated = treeBindings().filter { it.`when` as String != GATE }.map { it.key as String }

    assertTrue(
      ungated.isEmpty(),
      "these would apply to everyone, and one of them deletes a file: $ungated",
    )
  }

  @Test
  fun `test the bindings are the ones this host claims to support`() {
    val bound = treeBindings().associate { (it.key as String) to (it.command as String) }

    assertEquals(SUPPORTED, bound.toList().sortedBy { it.first }.toMap())
  }

  /**
   * Not `vimperor.key`. Every other binding in the manifest hands a keystroke to the engine; these
   * hand a command to VS Code, because the engine cannot see the tree and has nothing to do with
   * the key. A `vimperor.key` binding here would silently do nothing.
   */
  @Test
  fun `test they go to VS Code rather than to the engine`() {
    val toTheEngine = treeBindings().filter { (it.command as String).startsWith("vimperor.") }

    assertTrue(toTheEngine.isEmpty(), "the engine never sees a key pressed in the file tree")
  }

  @Test
  fun `test the commands they send are on the list the real window is asked about`() {
    val sent = treeBindings().map { it.command as String }.toSet()

    assertTrue(sent.all { it in VsCodeCommands.all }, "not asked about at activation: ${sent - VsCodeCommands.all.toSet()}")
  }

  /**
   * The reading has to be able to fail: if the manifest stopped parsing, every assertion above
   * would pass over an empty list.
   */
  @Test
  fun `test the bindings are actually read`() {
    assertEquals(SUPPORTED.size, treeBindings().size)
  }

  private fun treeBindings(): List<dynamic> {
    val manifest = JSON.parse<dynamic>(readText("${repositoryRoot()!!}/vscode-extension/package.json"))
    val bindings = manifest.contributes.keybindings as Array<dynamic>
    return bindings.filter { (it.`when` as? String)?.contains("explorerViewletFocus") == true }
  }
}

/**
 * What the `when` clause has to be, spelled out once.
 *
 * `!inputFocus` is the half that is easy to leave out and expensive to leave out: the Explorer's
 * rename box and its new-file box are inputs *inside* the tree, and without this, typing `d` in a
 * filename would delete the file being renamed.
 */
private const val GATE = "explorerViewletFocus && !inputFocus && vimperor.nerdtree"

/**
 * NERDTree's keys and what VS Code does for each, which is the whole of what this host supports.
 *
 * What is deliberately absent, and why - these are NERDTree keys with no VS Code equivalent, not
 * an unfinished list:
 *
 *  - `i`, `gi`, `gs`, `go`, `T` - open in a split or without moving focus. VS Code's Explorer has
 *    `explorer.openToSide` and nothing else; there is no "open below" and no "open but stay here".
 *  - `p`, `P`, `J`, `K`, `<C-J>`, `<C-K>` - jump to a parent, a root, or a sibling at the same
 *    depth. VS Code's list commands move by row and know nothing about depth.
 *  - `X` - close a node's children. `list.collapseAll` collapses the whole tree, which is a
 *    different thing.
 *  - `I`, `f`, `F`, `B` - toggle what is shown. Filtering is `files.exclude` in settings, not a
 *    command.
 *  - `m` - NERDTree's own menu, which is a NERDTree UI.
 *  - `A` - zoom the tree. `workbench.action.toggleSidebarVisibility` hides it; nothing maximises it.
 *  - `C`, `u`, `U`, `cd`, `CD` - change the tree's root. VS Code's Explorer is rooted at the
 *    workspace folder and an extension cannot move it.
 */
private val SUPPORTED: Map<String, String> = mapOf(
  "ctrl+r" to "renameFile",
  "d" to "deleteFile",
  "g g" to "list.focusFirst",
  "j" to "list.focusDown",
  "k" to "list.focusUp",
  "n" to "explorer.newFile",
  "o" to "list.select",
  "q" to "workbench.action.closeSidebar",
  "r" to "workbench.files.action.refreshFilesExplorer",
  "s" to "explorer.openToSide",
  "shift+g" to "list.focusLast",
  "shift+n" to "explorer.newFolder",
  "shift+o" to "list.expandAll",
  "shift+r" to "workbench.files.action.refreshFilesExplorer",
  "v" to "filesExplorer.paste",
  "x" to "list.collapse",
  "y" to "filesExplorer.copy",
)
