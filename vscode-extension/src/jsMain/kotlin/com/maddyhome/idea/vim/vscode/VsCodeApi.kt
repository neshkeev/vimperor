/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

@file:JsModule("vscode")

package com.maddyhome.idea.vim.vscode

/**
 * The slice of VS Code's API this extension uses.
 *
 * `vscode` is not a package that can be installed - the extension host injects it at runtime, and
 * `require("vscode")` only resolves inside that host. These are external declarations against it,
 * so nothing here is compiled: the shapes must match VS Code's real API or the extension fails when
 * it runs rather than when it builds.
 *
 * Declared as they are needed. The full API is enormous, and a binding nothing calls is a shape
 * nobody has checked.
 */

/** Registrations that VS Code will dispose when the extension deactivates. */
external interface Disposable {
  fun dispose()
}

external interface ExtensionContext {
  /** Push registrations here and VS Code disposes them on deactivation. */
  val subscriptions: Subscriptions
}

/**
 * VS Code hands out a plain JavaScript array, which Kotlin's [Array] cannot express: `push` is not
 * a member of it. Declaring the one method used keeps the call typed instead of `asDynamic()`.
 */
external interface Subscriptions {
  fun push(disposable: Disposable)
}

external interface OutputChannel : Disposable {
  fun appendLine(value: String)
  fun show(preserveFocus: Boolean = definedExternally)
}

external object window {
  val activeTextEditor: TextEditor?
  val visibleTextEditors: Array<TextEditor>

  /**
   * The open tabs, which is the closest VS Code has to Vim's idea of a tab page.
   *
   * Readable synchronously, unlike almost everything else about the workbench, which is what makes
   * `:tabclose` and `:tabmove` possible at all - both need to know how many tabs there are and
   * which one is current before they can work out what to do.
   */
  val tabGroups: TabGroups
  fun createOutputChannel(name: String): OutputChannel
  fun showInformationMessage(message: String): dynamic
  fun createStatusBarItem(alignment: Int = definedExternally, priority: Int = definedExternally): StatusBarItem
  fun createTextEditorDecorationType(options: dynamic): TextEditorDecorationType

  /**
   * VS Code's events are functions: calling one with a listener subscribes and hands back the
   * disposable that unsubscribes. Declared as function-typed properties because that is what they
   * are - there is no `addListener` to call.
   */
  val onDidChangeActiveTextEditor: (listener: (TextEditor?) -> Unit) -> Disposable
  val onDidChangeTextEditorSelection: (listener: (TextEditorSelectionChangeEvent) -> Unit) -> Disposable

  /** Fires when the window gains or loses focus, which is when the clipboard may have changed. */
  val onDidChangeWindowState: (listener: (WindowState) -> Unit) -> Disposable
}

external interface TextEditorSelectionChangeEvent {
  val textEditor: TextEditor
  val selections: Array<Selection>
}

external interface StatusBarItem : Disposable {
  var text: String

  /** VS Code's type is `string | MarkdownString | undefined`; only the string half is used here. */
  var tooltip: String?
  fun show()
  fun hide()
}

/** Where a status bar item sits. VS Code's `StatusBarAlignment`: Left is 1, Right is 2. */
external object StatusBarAlignment {
  val Left: Int
  val Right: Int
}

external object commands {
  fun registerCommand(command: String, callback: (dynamic) -> Unit): Disposable

  /**
   * Registers a handler for a command VS Code already has, which is how an extension takes over
   * typing: `type` is a built-in, and registering it intercepts every printable character before
   * the editor sees it.
   */
  fun executeCommand(command: String, vararg args: dynamic): Thenable<dynamic>

  /**
   * Every command this VS Code has, which is the only list of them that exists - there is no
   * published set of ids to check against at build time. `filterInternal` drops the ones VS Code
   * uses for itself and does not intend anyone to call.
   */
  fun getCommands(filterInternal: Boolean): Thenable<Array<String>>
}

external object env {
  val clipboard: Clipboard

  /** Hands a URL to whatever the operating system opens it with. `gx` and `:help` are the callers. */
  fun openExternal(target: Uri): Thenable<Boolean>
}

/** The system clipboard, which VS Code exposes only through promises in both directions. */
external interface Clipboard {
  fun readText(): Thenable<String>
  fun writeText(value: String): Thenable<Unit>
}

external interface WindowState {
  /** Whether the VS Code window has focus. False means the user is somewhere else. */
  val focused: Boolean
}

external object workspace {
  val onDidChangeTextDocument: (listener: (TextDocumentChangeEvent) -> Unit) -> Disposable

  /**
   * The folders open in this window, which is what a relative path in `:e` is relative to.
   *
   * Vim resolves against the current directory; a VS Code window has no current directory, it has a
   * workspace. Null when nothing is open - a single loose file has an editor and no folder.
   */
  val workspaceFolders: Array<WorkspaceFolder>?
}

external interface WorkspaceFolder {
  val uri: Uri
  val name: String
}

external interface TextDocumentChangeEvent {
  val document: TextDocument
}

/**
 * A position in a document. VS Code is line/character throughout; the engine is offset-first, and
 * [TextDocument.offsetAt] and [TextDocument.positionAt] are the only bridge between them.
 */
external class Position(line: Int, character: Int) {
  val line: Int
  val character: Int
}

external class Range(start: Position, end: Position) {
  val start: Position
  val end: Position
}

/**
 * The document's text is readable synchronously, which is what makes the engine's contracts
 * possible at all - only writes go through [TextEditor.edit], and only they are asynchronous.
 */
external interface TextDocument {
  val uri: Uri
  val fileName: String
  val lineCount: Int
  val isUntitled: Boolean

  /** Whether the document has changes that are not on disk. */
  val isDirty: Boolean
  val version: Int
  fun getText(range: Range? = definedExternally): String
  fun offsetAt(position: Position): Int
  fun positionAt(offset: Int): Position
}

external interface Uri {
  val scheme: String
  val path: String
  val fsPath: String
}

/**
 * `Uri`'s static side, which is how one is built from a path.
 *
 * Under its own name in JavaScript - `vscode.Uri` is a class with both halves, and Kotlin cannot
 * have an interface and an object of the same name in one package. `checkVsCodeApiDeclarations`
 * knows the alias so that `file` and `parse` are still checked against the real class.
 */
@JsName("Uri")
external object UriFactory {
  fun file(path: String): Uri
  fun parse(value: String): Uri
}

/** The builder VS Code hands to [TextEditor.edit]; its ranges are resolved against the document as it was when the edit began. */
external interface TextEditorEdit {
  fun replace(location: Range, value: String)
  fun insert(location: Position, value: String)
  fun delete(location: Range)
}

external interface Selection {
  val anchor: Position
  val active: Position
}

external interface TextEditor {
  val document: TextDocument
  var selection: Selection
  var selections: Array<Selection>

  /**
   * Applies edits and resolves with whether they landed. The callback's edits are all resolved
   * against the pre-edit document, so two edits cannot be chained inside one call.
   */
  fun edit(callback: (TextEditorEdit) -> Unit): Thenable<Boolean>

  /** Scrolls so that [range] is on screen. VS Code's only scrolling API for an extension. */
  fun revealRange(range: Range, revealType: Int = definedExternally)

  /**
   * What is on screen, as line ranges, in order.
   *
   * The other half of scrolling: [revealRange] moves the view and this says where it now is. Vim's
   * `<C-E>`, `<C-D>` and `zt` are all defined as moving the view relative to where it already is,
   * so neither call is any use without the other.
   *
   * More than one range means something between them is folded. It can be empty for an editor that
   * has not been laid out yet, which is a case worth handling rather than indexing into.
   */
  val visibleRanges: Array<Range>

  /** How this editor indents: the resolved settings, not the raw configuration. */
  val options: TextEditorOptions

  /**
   * Paints [ranges] with [decorationType], replacing whatever that type painted before.
   *
   * Replacing rather than adding is the whole model: there is no way to remove one decoration, so
   * clearing a highlight means setting its ranges to none.
   */
  fun setDecorations(decorationType: TextEditorDecorationType, ranges: Array<Range>)
}

/**
 * An editor's indentation, as VS Code has already worked it out.
 *
 * This is the answer to the question Vim asks `'expandtab'` and `'tabstop'`, and the reason the
 * engine has neither option: IdeaVim asks IntelliJ, so `createIndentBySize` is a host question by
 * construction. VS Code resolves the user's settings, the language's override and - when
 * `editor.detectIndentation` is on - what the file itself does, and reports the result here.
 *
 * Both are declared as `dynamic` because both are unions in the real API: they are a number and a
 * boolean when read off an open editor, and may be the strings VS Code accepts when written. Only
 * reading happens here, so the coercion is one-way.
 */
external interface TextEditorOptions {
  val tabSize: dynamic
  val insertSpaces: dynamic
}

/** A style that can be painted over ranges. Created once and reused; disposing it unpaints it. */
external interface TextEditorDecorationType : Disposable

/**
 * A colour from the user's theme, by the id VS Code gives it.
 *
 * Used rather than a literal so that search highlights match what the editor's own find does, in
 * whatever theme the user has - a hardcoded yellow is unreadable in half of them.
 */
external class ThemeColor(id: String)

/**
 * The tab bar, as VS Code models it: groups side by side, each holding tabs.
 *
 * Only the reading half is declared. Opening and closing tabs is done by command like everything
 * else in the workbench; this is here because counting them cannot be.
 */
external interface TabGroups {
  val all: Array<TabGroup>
  val activeTabGroup: TabGroup
}

external interface TabGroup {
  val tabs: Array<Tab>
  val activeTab: Tab?
  val isActive: Boolean
}

external interface Tab {
  val label: String
  val isActive: Boolean
}

/** VS Code's `TextEditorRevealType`, which is a numeric enum on the module object. */
external object TextEditorRevealType {
  val Default: Int
  val InCenter: Int
  val InCenterIfOutsideViewport: Int
  val AtTop: Int
}

/** VS Code's promise type. Named as VS Code names it. */
external interface Thenable<T> {
  /**
   * The rejection half is not optional here.
   *
   * A VS Code command that does not exist rejects rather than resolving, and this host holds the
   * user's keystrokes until a command it is waiting on comes back. Listening only for success means
   * one typo in an `<Action>` mapping takes the keyboard with it, for good.
   */
  fun then(onFulfilled: (T) -> Unit, onRejected: (Any?) -> Unit = definedExternally): Thenable<T>
}
