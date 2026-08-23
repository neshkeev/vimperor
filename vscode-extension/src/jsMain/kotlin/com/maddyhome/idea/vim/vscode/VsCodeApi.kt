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
  fun createOutputChannel(name: String): OutputChannel
  fun showInformationMessage(message: String): dynamic
}

external object commands {
  fun registerCommand(command: String, callback: (dynamic) -> Unit): Disposable
}
