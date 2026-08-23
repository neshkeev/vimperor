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
  fun createOutputChannel(name: String): OutputChannel
  fun showInformationMessage(message: String): dynamic
}

external object commands {
  fun registerCommand(command: String, callback: (dynamic) -> Unit): Disposable
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
}

/** VS Code's promise type. Named as VS Code names it. */
external interface Thenable<T> {
  fun then(onFulfilled: (T) -> Unit): Thenable<T>
}
