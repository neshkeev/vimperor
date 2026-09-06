/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

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

  /**
   * `K` - what Vim calls the keyword-lookup program and every IDE answers with documentation.
   *
   * Vim runs `'keywordprg'`, which is `man` by default; IdeaVim substitutes IntelliJ's Quick
   * Documentation, on the grounds that a documentation popup is what a user pressing `K` in an IDE
   * wants. This is that same substitution with VS Code's own hover, which is where its
   * documentation lives.
   */
  const val SHOW_HOVER = "editor.action.showHover"

  /**
   * Where the functions and classes in a file are, asked of whichever language server owns it.
   *
   * One of VS Code's "execute a provider" commands, which is how an extension reaches a provider it
   * does not own - the same mechanism the Outline view and Go to Symbol use, so the answer is
   * exactly what a user sees there. It resolves with `DocumentSymbol[]`, or `SymbolInformation[]`
   * from an older provider, or nothing at all for a language that has no provider.
   *
   * It is a promise, and `am`, `aM`, `im` and `ac` are asked their range in the middle of a
   * keystroke - so it is never asked *for* a keystroke. See [DocumentSymbols].
   */
  const val DOCUMENT_SYMBOLS = "vscode.executeDocumentSymbolProvider"

  /**
   * The Explorer, which is what `NERDTree`'s ex commands ask about.
   *
   * `workbench.view.explorer` both reveals the sidebar and puts the keyboard in the file tree,
   * which is the whole of what `:NERDTree` and `:NERDTreeFocus` mean here.
   *
   * `TOGGLE_SIDEBAR` is a toggle of the *sidebar*, not of the Explorer: with the Search view
   * showing, `:NERDTreeToggle` hides the sidebar rather than switching to the files. That is the
   * closest VS Code has - there is no "toggle this view" - and it is the same thing the Explorer's
   * own `Ctrl+B` does, so it is at least what a user expects from the key they already press.
   */
  const val FOCUS_EXPLORER = "workbench.view.explorer"
  const val TOGGLE_SIDEBAR = "workbench.action.toggleSidebarVisibility"
  const val CLOSE_SIDEBAR = "workbench.action.closeSidebar"
  const val REVEAL_IN_EXPLORER = "workbench.files.action.showActiveFileInExplorer"
  const val REFRESH_EXPLORER = "workbench.files.action.refreshFilesExplorer"

  /**
   * What NERDTree's in-tree keys are bound to in `package.json`.
   *
   * This extension never sends these - VS Code runs them itself when a key is pressed in the
   * Explorer, because a key pressed in the sidebar never reaches an extension. They are listed here
   * for one reason: [all] is what activation asks the real window about, and an id in a manifest is
   * exactly as unverifiable offline as an id in this file, with the added problem that a wrong one
   * fails by doing nothing at all. Putting them here turns that into a line of output.
   *
   * See `NerdTreeManifestTest` for which NERDTree keys these cover and which have no equivalent.
   */
  const val LIST_FOCUS_DOWN = "list.focusDown"
  const val LIST_FOCUS_UP = "list.focusUp"
  const val LIST_FOCUS_FIRST = "list.focusFirst"
  const val LIST_FOCUS_LAST = "list.focusLast"
  const val LIST_SELECT = "list.select"
  const val LIST_EXPAND_ALL = "list.expandAll"
  const val LIST_COLLAPSE = "list.collapse"
  const val EXPLORER_OPEN_TO_SIDE = "explorer.openToSide"
  const val EXPLORER_NEW_FILE = "explorer.newFile"
  const val EXPLORER_NEW_FOLDER = "explorer.newFolder"
  const val DELETE_FILE = "deleteFile"
  const val RENAME_FILE = "renameFile"
  const val EXPLORER_COPY = "filesExplorer.copy"
  const val EXPLORER_PASTE = "filesExplorer.paste"

  /**
   * `youcompleteme`: `<Tab>` and `<S-Tab>` walking the completion list rather than accepting from
   * it. Same arrangement as the NERDTree block above - this extension never sends them, VS Code
   * runs them when the manifest's `when` clause matches, and they are listed here so that
   * activation asks the real window whether they exist.
   *
   * These two are the only place in this host where a `when` clause reads a state the extension
   * itself cannot see: `suggestWidgetVisible` is write-only for an extension, which is why
   * `lookupManager` answers null and why this cannot be done in the engine.
   */
  const val SELECT_NEXT_SUGGESTION = "selectNextSuggestion"
  const val SELECT_PREV_SUGGESTION = "selectPrevSuggestion"


  /**
   * `=`. Re-indents the selected lines, which is what Vim's `=` does and the whole of what it does.
   *
   * Measured rather than argued, with Vim 9.1 and `-u NONE`: `ggVG=` on `{ "hello": "world"}`
   * leaves it exactly as it was, with json indent rules loaded or without; the same key on a
   * six-line JSON with the indentation wrong fixes every indent and leaves six lines. `=` changes
   * leading whitespace. It does not split a line, join one, or touch spacing inside one.
   *
   * Not `editor.action.formatSelection`, then, even though IdeaVim's `=` does call the IDE's
   * formatter - that is IdeaVim diverging from Vim, and this fork's stated goal is Vim. The cost
   * is that `=` on a one-line document appears to do nothing, because there is nothing an indenter
   * can do to it. Anybody who wants the IdeaVim behaviour has it in one line of config:
   * `xnoremap = <Action>(editor.action.formatSelection)`.
   *
   * Vim's own answer to "run a real formatter instead" is `'equalprg'`, which this fork does not
   * implement yet. That is where the choice belongs when it is built.
   */
  const val REINDENT_SELECTED_LINES = "editor.action.reindentselectedlines"

  /**
   * `gq` and `gw`, which are *format* rather than *indent* - so this is the command `=` deliberately
   * does not use.
   *
   * The note above explains why `=` is not allowed to reach a formatter: Vim's `=` changes leading
   * whitespace and nothing else. `gq` has no such restraint in IdeaVim, which hands it straight to
   * the IDE's reformat, and this is the same bargain: whatever the language's formatter does.
   */
  const val FORMAT_SELECTION = "editor.action.formatSelection"

  /**
   * `gc`, `gcc` and `:Commentary`, which VS Code answers from the language configuration every
   * language extension ships - the same knowledge IntelliJ keeps in a `Commenter`.
   *
   * Both are toggles, which is what Vim's `gc` is, and both act on the selection - so the caller
   * puts a selection over the range first, exactly as `=` does. `addCommentLine` and
   * `removeCommentLine` are the one-way forms and are the wrong shape: deciding whether a mixed
   * selection counts as commented is the part worth letting the host do.
   */
  const val COMMENT_LINE = "editor.action.commentLine"
  const val BLOCK_COMMENT = "editor.action.blockComment"

  /**
   * Moves the view by a number of lines, which is the only API that does exactly that.
   *
   * `revealRange` is the other half of VS Code's scrolling API and the obvious thing to reach for,
   * and it is the wrong shape twice over. It says where a *range* should end up rather than where
   * the view should be, and a real window put every `AtTop` request five lines above the line it
   * named - measured, over sixty reveals, in a window whose reported viewport was nineteen lines
   * tall. `editorScroll` has no such arithmetic to disagree about: it moves the scroll position by
   * the number of lines it is given.
   *
   * It takes an object argument - `{to, by, value, revealCursor}` - and acts on the *focused*
   * editor rather than on the one it is handed, because it is not handed one. Every scroll here
   * happens while the user is typing into the editor being scrolled, so that is the same editor.
   */
  const val EDITOR_SCROLL = "editorScroll"

  /** `:e file`. One of the few built-ins that takes an argument, which is why the runner carries one. */
  const val OPEN = "vscode.open"

  const val SAVE = "workbench.action.files.save"
  const val SAVE_ALL = "workbench.action.files.saveAll"
  /**
   * Closes the command-line window without asking to save it.
   *
   * `q:` is an untitled document - the only kind of non-file editor VS Code lets an extension write
   * to - and closing a dirty untitled document normally raises a save prompt. Reverting first
   * leaves nothing to ask about, which is right for a scratch buffer whose whole life is one
   * keystroke. See [VsCodeVirtualBuffers].
   */
  const val REVERT_AND_CLOSE = "workbench.action.revertAndCloseActiveEditor"

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
  /**
   * `<C-W>+` and friends: how big the current editor group is.
   *
   * VS Code resizes in steps of its own choosing and has no command that takes a size, which is why
   * `ResizeArgument.Absolute` is refused rather than approximated - see [VsCodeWindowResize].
   *
   * `EVEN_EDITOR_WIDTHS` is the whole of `<C-W>=` here: there is no command for evening the
   * heights. `MAXIMIZE_EDITOR_GROUP` takes both dimensions, which is more than `<C-W>_` asks for
   * and the nearest thing to it.
   */
  const val INCREASE_VIEW_HEIGHT = "workbench.action.increaseViewHeight"
  const val DECREASE_VIEW_HEIGHT = "workbench.action.decreaseViewHeight"
  const val INCREASE_VIEW_WIDTH = "workbench.action.increaseViewWidth"
  const val DECREASE_VIEW_WIDTH = "workbench.action.decreaseViewWidth"
  const val EVEN_EDITOR_WIDTHS = "workbench.action.evenEditorWidths"
  const val TOGGLE_EDITOR_WIDTHS = "workbench.action.toggleEditorWidths"
  const val MAXIMIZE_EDITOR_GROUP = "workbench.action.toggleMaximizeEditorGroup"

  const val SPLIT_EDITOR_DOWN = "workbench.action.splitEditorDown"
  const val SPLIT_EDITOR_RIGHT = "workbench.action.splitEditorRight"
  const val CLOSE_EDITORS_AND_GROUP = "workbench.action.closeEditorsAndGroup"
  const val CLOSE_EDITORS_IN_OTHER_GROUPS = "workbench.action.closeEditorsInOtherGroups"
  const val CLOSE_ALL_GROUPS = "workbench.action.closeAllGroups"

  /** `:enew` - VS Code's blank page, which it calls an untitled file. */
  const val NEW_UNTITLED_FILE = "workbench.action.files.newUntitledFile"

  /**
   * `:diffsplit` and `:diffthis` - VS Code's diff editor, over two files.
   *
   * The other built-in that takes arguments, like [OPEN]: two URIs and a title. The view it opens
   * owns its own hunks from then on, which is why `:diffget` and `:diffput` report `E319`.
   */
  const val DIFF = "vscode.diff"

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
    REVEAL_DEFINITION, DOCUMENT_SYMBOLS, SHOW_HOVER, REINDENT_SELECTED_LINES, EDITOR_SCROLL, OPEN,
    FORMAT_SELECTION,
    COMMENT_LINE, BLOCK_COMMENT,
    FOCUS_EXPLORER, TOGGLE_SIDEBAR, CLOSE_SIDEBAR, REVEAL_IN_EXPLORER, REFRESH_EXPLORER,
    LIST_FOCUS_DOWN, LIST_FOCUS_UP, LIST_FOCUS_FIRST, LIST_FOCUS_LAST, LIST_SELECT,
    LIST_EXPAND_ALL, LIST_COLLAPSE, EXPLORER_OPEN_TO_SIDE, EXPLORER_NEW_FILE, EXPLORER_NEW_FOLDER,
    DELETE_FILE, RENAME_FILE, EXPLORER_COPY, EXPLORER_PASTE,
    SELECT_NEXT_SUGGESTION, SELECT_PREV_SUGGESTION,
    SAVE, SAVE_ALL, CLOSE_ACTIVE_EDITOR, CLOSE_OTHER_EDITORS, REVERT_AND_CLOSE,
    NEXT_EDITOR, PREVIOUS_EDITOR, PREVIOUS_USED_EDITOR_IN_GROUP,
    MOVE_EDITOR_LEFT_IN_GROUP, MOVE_EDITOR_RIGHT_IN_GROUP,
    FOCUS_ABOVE_GROUP, FOCUS_BELOW_GROUP, FOCUS_LEFT_GROUP, FOCUS_RIGHT_GROUP,
    FOCUS_NEXT_GROUP, FOCUS_PREVIOUS_GROUP,
    INCREASE_VIEW_HEIGHT, DECREASE_VIEW_HEIGHT, INCREASE_VIEW_WIDTH, DECREASE_VIEW_WIDTH,
    EVEN_EDITOR_WIDTHS, TOGGLE_EDITOR_WIDTHS, MAXIMIZE_EDITOR_GROUP,
    SPLIT_EDITOR_DOWN, SPLIT_EDITOR_RIGHT,
    CLOSE_EDITORS_AND_GROUP, CLOSE_EDITORS_IN_OTHER_GROUPS, CLOSE_ALL_GROUPS,
    NEW_UNTITLED_FILE, DIFF,
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
 * IntelliJ action ids, and the VS Code command that does the same job.
 *
 * The point of this is one line of a bug report: "so `~/.ideavimrc` can be picked up without
 * modifications". A config written for IdeaVim is full of `:action GotoClass` and
 * `<Action>(Back)`, those are IntelliJ's names, and this host speaks VS Code's - so every one of
 * them failed, and the ones in mappings failed silently. The engine does not care which vocabulary
 * a host uses; it hands the name through. This is the translation.
 *
 * A null value is a real answer and not a gap: some of these are IntelliJ and nothing else.
 * `MakeGradleModule` and `Maven.ReimportProject` are the IDE's build model, `Annotate` is its VCS
 * integration, `StructuralSearchPlugin.StructuralSearchAction` is a feature VS Code has no version
 * of. Saying so at the `:` prompt is worth more than a command id that does not exist, and much
 * more than nothing happening.
 *
 * Every mapping here is a judgement about what two editors mean by the same word, and some are
 * closer than others - `HideAllWindows` against a sidebar toggle is a practical answer rather than
 * an exact one. The ids themselves are strings typed from documentation, which is this module's
 * least checkable kind of fact, so [missingFrom] compares them against the real window at
 * activation the way [VsCodeCommands.missingFrom] does.
 */
internal object IdeaActionAliases {

  private val aliases: Map<String, String?> = mapOf(
    // Navigation. IntelliJ separates "go to class" from "go to file"; VS Code separates "symbol in
    // workspace" from "file", which is the same cut in a different place.
    "GotoClass" to "workbench.action.showAllSymbols",
    "GotoSymbol" to "workbench.action.showAllSymbols",
    "GotoFile" to "workbench.action.quickOpen",
    "SearchEverywhere" to "workbench.action.quickOpen",
    "GotoAction" to "workbench.action.showCommands",
    "GotoLine" to "workbench.action.gotoLine",
    "FileStructurePopup" to "workbench.action.gotoSymbol",
    "RecentFiles" to "workbench.action.openRecent",
    "RecentChangedFiles" to "workbench.action.openRecent",
    // `:e` with no argument reaches this one from the engine, so a bare `:e` opens the picker.
    "OpenFile" to "workbench.action.quickOpen",

    "GotoDeclaration" to "editor.action.revealDefinition",
    "GotoTypeDeclaration" to "editor.action.goToTypeDefinition",
    "GotoImplementation" to "editor.action.goToImplementation",
    "QuickImplementations" to "editor.action.peekDefinition",
    "FindUsages" to "editor.action.goToReferences",
    "ShowUsages" to "editor.action.goToReferences",
    "CallHierarchy" to "references-view.showCallHierarchy",
    "TypeHierarchy" to "references-view.showTypeHierarchy",
    // IntelliJ walks up to the overridden method. VS Code goes down to implementations and has
    // nothing that goes up.
    "GotoSuperMethod" to null,
    "GotoRelated" to null,

    "Back" to "workbench.action.navigateBack",
    "Forward" to "workbench.action.navigateForward",
    "JumpToLastChange" to "workbench.action.navigateToLastEditLocation",
    "GotoNextError" to "editor.action.marker.next",
    "GotoPreviousError" to "editor.action.marker.prev",

    // Editing and refactoring.
    "RenameElement" to "editor.action.rename",
    "ReformatCode" to "editor.action.formatDocument",
    "Refactorings.QuickListPopupAction" to "editor.action.refactor",
    "ShowIntentionActions" to "editor.action.quickFix",
    "CommentByLineComment" to "editor.action.commentLine",
    "CommentByBlockComment" to "editor.action.blockComment",
    "OptimizeImports" to "editor.action.organizeImports",
    "ExpandRegion" to "editor.action.smartSelect.expand",
    "ShrinkRegion" to "editor.action.smartSelect.shrink",
    "EditorSelectWord" to "editor.action.smartSelect.expand",
    "EditorUnSelectWord" to "editor.action.smartSelect.shrink",
    "ParameterInfo" to "editor.action.triggerParameterHints",
    "CodeCompletion" to "editor.action.triggerSuggest",
    "SmartTypeCompletion" to "editor.action.triggerSuggest",
    "QuickJavaDoc" to "editor.action.showHover",
    "MoveLineUp" to "editor.action.moveLinesUpAction",
    "MoveLineDown" to "editor.action.moveLinesDownAction",
    "EditorDuplicate" to "editor.action.copyLinesDownAction",

    // The `$`-prefixed ones are IntelliJ's own editor actions, which people bind when a Vim key is
    // in the way of something they still want.
    "\$Undo" to "undo",
    "\$Redo" to "redo",
    "\$Copy" to "editor.action.clipboardCopyAction",
    "\$Cut" to "editor.action.clipboardCutAction",
    "\$Paste" to "editor.action.clipboardPasteAction",
    "\$SelectAll" to "editor.action.selectAll",

    // Editors, splits and tool windows.
    "CloseContent" to "workbench.action.closeActiveEditor",
    "CloseAllEditorsButActive" to "workbench.action.closeOtherEditors",
    "CloseAllEditors" to "workbench.action.closeAllEditors",
    "NextTab" to "workbench.action.nextEditor",
    "PreviousTab" to "workbench.action.previousEditor",
    // IntelliJ names a split by the divider's direction and VS Code by where the new editor lands,
    // so these read as swapped and are not: a vertical divider puts the new editor to the right.
    "SplitVertically" to "workbench.action.splitEditorRight",
    "SplitHorizontally" to "workbench.action.splitEditorDown",
    "ToggleFullScreen" to "workbench.action.toggleFullScreen",
    "ToggleDistractionFreeMode" to "workbench.action.toggleZenMode",
    // Not exact. IntelliJ hides every tool window at once; the sidebar is the one that is usually
    // in the way, and toggling it is what people bind this to.
    "HideAllWindows" to "workbench.action.toggleSidebarVisibility",
    "ActivateProjectToolWindow" to "workbench.view.explorer",
    "ActivateTerminalToolWindow" to "workbench.action.terminal.toggleTerminal",
    "ActivateVersionControlToolWindow" to "workbench.view.scm",
    "ActivateDebugToolWindow" to "workbench.view.debug",
    "ActivateFindToolWindow" to "workbench.view.search",
    "ActivateInspectionResultsToolWindow" to "workbench.actions.view.problems",

    // Run and debug.
    "Run" to "workbench.action.debug.run",
    "Debug" to "workbench.action.debug.start",
    "Stop" to "workbench.action.debug.stop",
    "Resume" to "workbench.action.debug.continue",
    "StepOver" to "workbench.action.debug.stepOver",
    "StepInto" to "workbench.action.debug.stepInto",
    "StepOut" to "workbench.action.debug.stepOut",
    "ToggleLineBreakpoint" to "editor.debug.action.toggleBreakpoint",
    "editRunConfigurations" to "workbench.action.debug.configure",
    // VS Code enables or disables all breakpoints with two separate commands and has no toggle.
    "XDebugger.MuteBreakpoints" to null,

    // Version control.
    "Vcs.UpdateProject" to "git.pull",
    "Vcs.Push" to "git.push",
    "CheckinProject" to "workbench.view.scm",
    "Git.Branches" to "git.checkout",
    // Blame. VS Code has none built in - it is what people install GitLens for.
    "Annotate" to null,

    // The IDE's own model of a project, which is where the two editors stop resembling each other.
    "MakeGradleModule" to null,
    "CompileDirty" to "workbench.action.tasks.build",
    "Maven.ReimportProject" to null,
    "Maven.Reimport" to null,
    "StructuralSearchPlugin.StructuralSearchAction" to null,
  )

  /** Whether this is a name this table has an answer for, including "nothing does this". */
  fun contains(ideaId: String): Boolean = ideaId in aliases

  /** The VS Code command for [ideaId], or null when nothing in VS Code does that job. */
  fun commandFor(ideaId: String): String? = aliases[ideaId]

  /** The whole table, for the tests that hold it to the shape it claims. */
  val all: Map<String, String?> get() = aliases

  /** Every command this table can send, so activation can check them against the real window. */
  val targets: List<String> = aliases.values.filterNotNull().distinct().sorted()

  fun missingFrom(available: Collection<String>): List<String> = targets.filterNot { it in available }
}

/**
 * Settings this host reads, by the name VS Code knows them by.
 *
 * Here rather than where they are used for the same reason the command ids are: `checkVsCodeCommandIds`
 * refuses a `workbench.`, `editor.` or `vscode.` literal anywhere else, so that nothing addresses VS
 * Code from a corner no check can see. They are not commands, so they are not in
 * [VsCodeCommands.all] - a settings name cannot be executed, and the activation check runs commands.
 */
internal object VsCodeSettings {
  /** The section every one of these lives in. */
  const val EDITOR = "editor"

  /** `off`, `on`, `wordWrapColumn` or `bounded`. Vim's `'wrap'` is the first against the rest. */
  const val WORD_WRAP = "wordWrap"

  /** The two of those four Vim can ask for. */
  const val WORD_WRAP_ON = "on"
  const val WORD_WRAP_OFF = "off"

  /** Section and key together, for a message that has to name the setting a user would search for. */
  /**
   * `indentSize`'s "however wide a tab is", which is VS Code's spelling of Vim's `shiftwidth=0`.
   *
   * A value rather than a setting name, and the only string of its kind here - `indentSize` is
   * `number | string` and this is the string half.
   */
  const val INDENT_SIZE_TAB_SIZE: String = "tabSize"

  const val WORD_WRAP_SETTING = "$EDITOR.$WORD_WRAP"
}

/**
 * Colours by the id the user's theme gives them, so highlights match what the editor's own find
 * does. A literal colour is unreadable in half of the themes people use.
 */
internal object VsCodeThemeColors {
  const val FIND_MATCH = "editor.findMatchBackground"
  const val FIND_MATCH_HIGHLIGHT = "editor.findMatchHighlightBackground"
  const val SELECTION_HIGHLIGHT = "editor.selectionHighlightBackground"

  /** `:match ErrorMsg /.../` and its neighbours - see [VsCodeMatchHighlighter]. */
  const val ERROR_BACKGROUND = "inputValidation.errorBackground"
  const val WARNING_BACKGROUND = "inputValidation.warningBackground"
  const val WORD_HIGHLIGHT = "editor.wordHighlightBackground"
  const val SELECTION = "editor.selectionBackground"
  const val MATCH_BRACKET = "editorBracketMatch.background"

  /**
   * Vim's highlight groups, in the nearest colour this editor already has.
   *
   * This fork has no `:highlight` to define a group, so a name is only ever one of Vim's standard
   * ones - and each of these has an obvious counterpart here. A name not on the list falls back to
   * the find colour, which lights the text up and lets the reader see that the pattern worked.
   *
   * Theme colours rather than literals, for the same reason the search highlighting uses them: a
   * hardcoded yellow is unreadable in half the themes people use.
   */
  val VIM_HIGHLIGHT_GROUPS: Map<String, String> = mapOf(
    "Search" to FIND_MATCH_HIGHLIGHT,
    "IncSearch" to FIND_MATCH,
    "ErrorMsg" to ERROR_BACKGROUND,
    "WarningMsg" to WARNING_BACKGROUND,
    "Todo" to WARNING_BACKGROUND,
    "Underlined" to WORD_HIGHLIGHT,
    "Visual" to SELECTION,
    "MatchParen" to MATCH_BRACKET,
  )
}
