/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

/**
 * Every VS Code command this extension asks for, in one place so that they can be checked.
 *
 * These are the least verifiable thing in the module and it is not close. `checkVsCodeApiDeclarations`
 * holds the `external` declarations to `@types/vscode`, and the compiler holds everything else - but a
 * command id is a string, VS Code publishes no list of them to check against, and the stub the tests
 * run against answers to whatever this file says. A test asserting `workbench.action.files.save` is
 * asserting that this file and that test agree, which they will even if the real id is something else.
 *
 * So the check happens where the real list exists: `vscode.commands.getCommands` at activation, which
 * says what this VS Code actually has. [verify] compares the two and writes the difference to the
 * output channel. It cannot run in a test - a stub's command list is written from the same reading of
 * the documentation as this file - so it earns nothing until the extension runs in a real window, and
 * that is exactly the point: it turns the largest untested assumption here into a line of output.
 *
 * [all] has to be complete for that to mean anything. `checkVsCodeCommandIds` enforces it by failing
 * on any `workbench.` or `editor.` literal elsewhere in this module.
 */
internal object VsCodeCommands {
  const val UNDO = "undo"
  const val REDO = "redo"

  const val FOLD = "editor.fold"
  const val FOLD_ALL = "editor.foldAll"
  const val FOLD_RECURSIVELY = "editor.foldRecursively"
  const val UNFOLD = "editor.unfold"
  const val UNFOLD_ALL = "editor.unfoldAll"
  const val UNFOLD_RECURSIVELY = "editor.unfoldRecursively"
  const val TOGGLE_FOLD = "editor.toggleFold"

  const val REVEAL_DEFINITION = "editor.action.revealDefinition"

  /** `:e file`. One of the few built-ins that takes an argument, which is why the runner carries one. */
  const val OPEN = "vscode.open"

  const val SAVE = "workbench.action.files.save"
  const val SAVE_ALL = "workbench.action.files.saveAll"
  const val CLOSE_ACTIVE_EDITOR = "workbench.action.closeActiveEditor"
  const val CLOSE_OTHER_EDITORS = "workbench.action.closeOtherEditors"

  const val NEXT_EDITOR = "workbench.action.nextEditor"
  const val PREVIOUS_EDITOR = "workbench.action.previousEditor"
  const val PREVIOUS_USED_EDITOR_IN_GROUP = "workbench.action.openPreviousRecentlyUsedEditorInGroup"
  const val MOVE_EDITOR_LEFT_IN_GROUP = "workbench.action.moveEditorLeftInGroup"
  const val MOVE_EDITOR_RIGHT_IN_GROUP = "workbench.action.moveEditorRightInGroup"

  const val FOCUS_ABOVE_GROUP = "workbench.action.focusAboveGroup"
  const val FOCUS_BELOW_GROUP = "workbench.action.focusBelowGroup"
  const val FOCUS_LEFT_GROUP = "workbench.action.focusLeftGroup"
  const val FOCUS_RIGHT_GROUP = "workbench.action.focusRightGroup"
  const val FOCUS_NEXT_GROUP = "workbench.action.focusNextGroup"
  const val FOCUS_PREVIOUS_GROUP = "workbench.action.focusPreviousGroup"
  const val SPLIT_EDITOR_DOWN = "workbench.action.splitEditorDown"
  const val SPLIT_EDITOR_RIGHT = "workbench.action.splitEditorRight"
  const val CLOSE_EDITORS_AND_GROUP = "workbench.action.closeEditorsAndGroup"
  const val CLOSE_EDITORS_IN_OTHER_GROUPS = "workbench.action.closeEditorsInOtherGroups"
  const val CLOSE_ALL_GROUPS = "workbench.action.closeAllGroups"

  /**
   * The nth tab of the current group, which VS Code numbers into the command id itself rather than
   * taking as an argument. One through nine; there is no tenth, and `:tabmove 10` has nowhere to go.
   */
  fun openEditorAtIndex(index: Int): String = "workbench.action.openEditorAtIndex${index + 1}"

  val TAB_INDEXES: IntRange = 0..8

  /**
   * Editor group number [index], one-based. VS Code numbers its groups into eight separate command
   * ids and spells the number as a word, rather than taking it as an argument - which it cannot,
   * because `executeCommand` here carries none. Past the eighth there is nothing to call.
   */
  fun focusEditorGroup(index: Int): String? =
    GROUP_ORDINALS.getOrNull(index - 1)?.let { "workbench.action.focus${it}EditorGroup" }

  private val GROUP_ORDINALS =
    listOf("First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth")

  val all: List<String> = listOf(
    UNDO, REDO,
    FOLD, FOLD_ALL, FOLD_RECURSIVELY, UNFOLD, UNFOLD_ALL, UNFOLD_RECURSIVELY, TOGGLE_FOLD,
    REVEAL_DEFINITION, OPEN,
    SAVE, SAVE_ALL, CLOSE_ACTIVE_EDITOR, CLOSE_OTHER_EDITORS,
    NEXT_EDITOR, PREVIOUS_EDITOR, PREVIOUS_USED_EDITOR_IN_GROUP,
    MOVE_EDITOR_LEFT_IN_GROUP, MOVE_EDITOR_RIGHT_IN_GROUP,
    FOCUS_ABOVE_GROUP, FOCUS_BELOW_GROUP, FOCUS_LEFT_GROUP, FOCUS_RIGHT_GROUP,
    FOCUS_NEXT_GROUP, FOCUS_PREVIOUS_GROUP,
    SPLIT_EDITOR_DOWN, SPLIT_EDITOR_RIGHT,
    CLOSE_EDITORS_AND_GROUP, CLOSE_EDITORS_IN_OTHER_GROUPS, CLOSE_ALL_GROUPS,
  ) + TAB_INDEXES.map { openEditorAtIndex(it) } +
    GROUP_ORDINALS.indices.mapNotNull { focusEditorGroup(it + 1) }

  /**
   * Which of [all] this VS Code does not have, given the list it answered with.
   *
   * Separate from the reporting so that it can be tested against a list that is deliberately short.
   * The check itself is only meaningful against a real VS Code, but *this* part - that a missing id
   * is noticed and a present one is not - is ordinary code and is tested like any other.
   */
  fun missingFrom(available: Collection<String>): List<String> = all.filterNot { it in available }
}

/**
 * Colours by the id the user's theme gives them, so highlights match what the editor's own find
 * does. A literal colour is unreadable in half of the themes people use.
 */
internal object VsCodeThemeColors {
  const val FIND_MATCH = "editor.findMatchBackground"
  const val FIND_MATCH_HIGHLIGHT = "editor.findMatchHighlightBackground"
  const val SELECTION_HIGHLIGHT = "editor.selectionHighlightBackground"
}
