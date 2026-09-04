/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

@file:JsModule("vscode")

package com.github.neshkeev.vimperor.vscode

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

  /**
   * A directory of this extension's own, and the only fixed point from which the user's
   * `keybindings.json` can be found - see [userKeybindingsPath]. Nothing is stored in it.
   */
  val globalStorageUri: Uri
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

  /** Puts a document in front of the user. The other half of [workspace.openTextDocument]. */
  fun showTextDocument(document: TextDocument): Thenable<TextEditor>
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

/**
 * Why the selection changed, and the only reliable way to tell the user's move from this host's own.
 *
 * `kind` is undefined for a change VS Code did not attribute to the keyboard, the mouse or a
 * command - which is exactly what an edit's own caret adjustment reports, and what setting
 * `TextEditor.selections` from an extension reports. Reading those back is reading this host's own
 * writing: `O` opened a line above and then had its caret pulled back by the edit that opened it,
 * and a blockwise Visual selection came apart one column at a time as its anchor was overwritten by
 * whichever line VS Code had last reported.
 */
external object TextEditorSelectionChangeKind {
  val Keyboard: Int
  val Mouse: Int
  val Command: Int
}

external interface TextEditorSelectionChangeEvent {
  val textEditor: TextEditor
  val selections: Array<Selection>

  /** See [TextEditorSelectionChangeKind]. Null when VS Code did not attribute the change. */
  val kind: Int?
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

/**
 * Every extension this window has loaded, which is where the second source of keybindings is.
 *
 * The built-in extensions are in here too - Git, the language features, the debuggers - so a
 * manifest that binds a chord is readable whether the user installed it or VS Code shipped it.
 */
external object extensions {
  val all: Array<Extension>
}

external interface Extension {
  /** `publisher.name`. */
  val id: String

  /** The manifest, parsed. VS Code types it `any` because only the extension knows its own shape. */
  val packageJSON: dynamic
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
   * A document that is no longer open in any editor, which is Vim's buffer being unloaded.
   *
   * Nothing was listening for this and the host kept every editor it had ever seen. That is not
   * only memory: every marker in a buffer is moved on every edit to it, `'hlsearch'` paints each
   * editor the engine knows about, and `getFocusedEditor` falls back to the last one registered.
   */
  val onDidCloseTextDocument: (listener: (TextDocument) -> Unit) -> Disposable

  /** A document written to disk, which is Vim's `BufWritePost`. */
  val onDidSaveTextDocument: (listener: (TextDocument) -> Unit) -> Disposable

  /**
   * The user's settings, which is where `vimperor.trace` lives.
   *
   * Vim's own options come from the `.ideavimrc` and nowhere else - a Vim user configures Vim the
   * way Vim is configured. This is for the handful of things that are about the *extension* rather
   * than about Vim, and there is one: whether to write what each keystroke did to the output
   * channel, which is the only way a bug that only happens in a real window can be reported.
   */
  /**
   * A document that is not on disk, which is what the tutor is opened into.
   *
   * Vim's `vimtutor` copies its file somewhere writable before opening it, because the reader is
   * meant to take the text apart. An untitled document is that copy: editable, never saved unless
   * the reader asks, and gone when the tab closes.
   *
   * Asynchronous, unlike almost everything this host asks VS Code for. That is fine here and only
   * here - opening the tutor is a command the user invoked, not a keystroke the engine is waiting
   * to finish.
   */
  fun openTextDocument(options: UntitledDocumentOptions): Thenable<TextDocument>

  /**
   * The same by URI, which for a scheme that is not `file` asks whatever provider serves it.
   *
   * One caller: the read-only document holding VS Code's own default keybindings, which is the
   * only way an extension can find out what is bound to what. See [readDefaultKeybindings].
   */
  fun openTextDocument(uri: Uri): Thenable<TextDocument>

  fun getConfiguration(section: String): WorkspaceConfiguration

  /**
   * The folders open in this window, which is what a relative path in `:e` is relative to.
   *
   * Vim resolves against the current directory; a VS Code window has no current directory, it has a
   * workspace. Null when nothing is open - a single loose file has an editor and no folder.
   */
  val workspaceFolders: Array<WorkspaceFolder>?
}

external interface WorkspaceConfiguration {
  /** VS Code's is generic; only the boolean half is used here, and it is undefined when unset. */
  fun get(section: String): Any?
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

  /** VS Code's language mode, which is `'filetype'` and `'syntax'` in one. See [languages]. */
  val languageId: String
  val lineCount: Int
  val isUntitled: Boolean

  /** Whether the document has changes that are not on disk. */
  val isDirty: Boolean

  /**
   * Which line ending this file uses, and the reason the engine cannot read the text raw.
   *
   * IntelliJ normalises a document to `\n` and applies the file's separator on the way to disk, so
   * `vim-engine` was written against a buffer that only ever has one kind of line ending. VS Code
   * does not: [getText] on a CRLF file hands back `\r\n`, and every offset the engine computed
   * would then have a carriage return inside the line - `$` would land on it, `x` would delete it,
   * and `J` would leave one behind. [DocumentBuffer] normalises on the way in and puts this back on
   * the way out.
   */
  val eol: Int
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

/**
 * A selection, which is a class in VS Code and had to be an object of that class here.
 *
 * Declared as an interface until a real window rejected the first keystroke with
 * `Illegal argument: selections`. VS Code's setter for `TextEditor.selections` is not duck-typed -
 * it does `value.some(a => !(a instanceof Selection))` and throws - so a Kotlin class implementing
 * an interface of the same shape is a plain JavaScript object and is refused. Nothing offline could
 * see it: the stub host takes whatever it is handed, and the declaration check compares *names*.
 *
 * `checkVsCodeApiDeclarations` now also compares interface against class, which is what would have
 * caught this without a window.
 */
external class Selection(anchor: Position, active: Position) {
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

  /**
   * Scrolls so that [range] is on screen.
   *
   * The scrolling API an extension is pointed at, and not the one this host uses. It says where a
   * range should end up rather than where the view should be, and a real window put every `AtTop`
   * request five lines above the line it named - see [VsCodeCommands.EDITOR_SCROLL], which is what
   * moves the view here. Kept because it is part of the API surface and one of the guards checks
   * the whole of it.
   */
  fun revealRange(range: Range, revealType: Int = definedExternally)

  /**
   * What is on screen, as line ranges, in order.
   *
   * Where the view is, which Vim needs before it can say where the view goes next: `<C-E>`, `<C-D>`
   * and `zt` are all defined relative to the window's current top line.
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

  /**
   * The shape of the caret, which in Vim is part of knowing which mode you are in.
   *
   * Vim draws a block in Normal and Visual, a vertical bar in Insert and an underline in Replace,
   * and that shape is how a user reads the mode without looking anywhere else. Writable, unlike the
   * indentation settings beside it, because this is the one editor option a Vim emulator owns.
   */
  var cursorStyle: Int

  /**
   * Whether the gutter shows line numbers, and whether they count from the caret.
   *
   * Vim's `'number'` and `'relativenumber'` in one setting, and writable per editor - which is what
   * makes them implementable here at all. VS Code's user setting is `editor.lineNumbers`, and
   * writing that would change every window the user has; this changes the one they are looking at,
   * for as long as it is open, which is what a window-local Vim option means.
   */
  var lineNumbers: Int
}

/**
 * VS Code's `TextEditorLineNumbersStyle`: `Off = 0`, `On = 1`, `Relative = 2`.
 *
 * There is no fourth. Vim's `'relativenumber'` without `'number'` puts a `0` on the caret's line
 * and VS Code's `Relative` puts the absolute number there, which is Vim with both set - so the two
 * spellings of relative land in the same place. See [applyLineNumbers].
 */
external object TextEditorLineNumbersStyle {
  val Off: Int
  val On: Int
  val Relative: Int
}

/** VS Code's `TextEditorCursorStyle`, a numeric enum on the module object. */
external object TextEditorCursorStyle {
  val Line: Int
  val Block: Int
  val Underline: Int
  val LineThin: Int
  val BlockOutline: Int
  val UnderlineThin: Int
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

  /**
   * Whether the tab shows the unsaved-changes dot, which is Vim's `+` in `:ls`.
   *
   * `:ls` was written off in this host on the grounds that "VS Code's tab model does not carry the
   * modified state Vim prints in that table". It has carried it since 1.68.
   */
  val isDirty: Boolean

  /**
   * What kind of thing the tab holds, and where its file is.
   *
   * A union in the real API - a text file, a diff, a notebook, a terminal, a webview - so this is
   * typed loosely and narrowed with [TabInputText], which is the only kind that is a Vim buffer.
   */
  val input: Any?
}

/**
 * The tab kind that is an ordinary file, and the only one with a plain `uri`.
 *
 * A class rather than an interface because narrowing to it is `instanceof` at runtime, which is the
 * check VS Code documents for reading a tab's input - the other kinds carry a different shape
 * (`TabInputTextDiff` has two URIs and no `uri` at all).
 */
external class TabInputText {
  val uri: Uri
}

/** VS Code's `EndOfLine`, which is a numeric enum on the module object. There are only these two. */
external object EndOfLine {
  val LF: Int
  val CRLF: Int
}

/**
 * Language modes, of which VS Code has one where Vim has two.
 *
 * Vim's `'filetype'` decides which plugins and indent rules apply and `'syntax'` decides only how
 * the text is coloured, and either can be set without the other. VS Code has a single language
 * mode that does both, so both options land here - `set syntax=java` gets Java's highlighting *and*
 * its language server, which is more than Vim would have done and is what a VS Code user means.
 */
external object languages {
  fun setTextDocumentLanguage(document: TextDocument, languageId: String): Thenable<TextDocument>
}

/** VS Code's `TextEditorRevealType`, which is a numeric enum on the module object. */
external object TextEditorRevealType {
  val Default: Int
  val InCenter: Int
  val InCenterIfOutsideViewport: Int
  val AtTop: Int
}

/** VS Code's promise type. Named as VS Code names it. */
/** What [workspace.openTextDocument] is given when it is asked for an untitled document. */
external interface UntitledDocumentOptions {
  var content: String
  var language: String
}

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
