/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

@file:OptIn(ExperimentalJsExport::class)

// No `package` declaration, deliberately - see the note below.

import com.maddyhome.idea.vim.regexp.VimRegex
import com.maddyhome.idea.vim.vscode.ExtensionContext
import com.maddyhome.idea.vim.vscode.OutputChannel
import com.maddyhome.idea.vim.vscode.commands
import com.maddyhome.idea.vim.vscode.window

/**
 * The extension's entry points, and the only things here that JavaScript needs to see.
 *
 * VS Code requires a CommonJS module exporting `activate` and `deactivate`. Everything else - the
 * engine and this extension both - reaches JavaScript by being compiled into the same bundle, with
 * these two functions as the reachable roots. That is the whole reason this is Kotlin rather than
 * TypeScript: no facade to maintain, and no way for the engine to be silently eliminated.
 *
 * **This file is in the root package because JavaScript has no packages.** Kotlin/JS mirrors the
 * package path into the exports object, so declaring a package here would put these functions at
 * `module.exports.com.maddyhome.idea.vim.vscode.activate`, where VS Code does not look - it reads
 * `activate` off the top level and reports an extension that exports nothing. Everything else in
 * this module keeps its package; only the two names the host calls by name live where the host
 * looks for them.
 */

private var channel: OutputChannel? = null

@JsExport
fun activate(context: ExtensionContext) {
  val output = window.createOutputChannel("IdeaVim")
  channel = output
  context.subscriptions.push(output)

  // Proof the engine is live inside the extension host rather than merely bundled with it:
  // compiling a Vim pattern runs the ANTLR-generated parser and builds an NFA.
  //
  // Note this goes through `VimRegex`, the engine's *public* API. The parser behind it is
  // `internal`, and a separate Gradle module cannot see internals - so choosing Kotlin over
  // TypeScript removes the `@JsExport` facade, but the engine's public surface still bounds what
  // an extension can reach.
  output.appendLine("IdeaVim engine loaded. Vim pattern compiled: ${describe("\\(foo\\)\\+")}")

  val command = commands.registerCommand("ideavim.checkPattern") { argument ->
    val pattern = argument as? String ?: "\\(foo\\)\\+"
    output.appendLine("$pattern is ${describe(pattern)}")
    output.show(preserveFocus = true)
  }
  context.subscriptions.push(command)
}

@JsExport
fun deactivate() {
  channel?.appendLine("IdeaVim deactivated.")
  channel = null
}

/** Whether the engine can compile [pattern] as a Vim regex. */
private fun describe(pattern: String): String =
  try {
    VimRegex(pattern)
    "valid"
  } catch (e: Throwable) {
    "not valid (${e.message})"
  }
