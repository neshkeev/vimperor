/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

@file:OptIn(ExperimentalJsExport::class)

// No `package` declaration, deliberately - see the note below.

import com.maddyhome.idea.vim.vscode.CommandLineDisplay
import com.maddyhome.idea.vim.vscode.Disposable
import com.maddyhome.idea.vim.vscode.ExtensionContext
import com.maddyhome.idea.vim.vscode.MessageSink
import com.maddyhome.idea.vim.vscode.OutputChannel
import com.maddyhome.idea.vim.vscode.StatusBarAlignment
import com.maddyhome.idea.vim.vscode.StatusBarItem
import com.maddyhome.idea.vim.vscode.TextEditor
import com.maddyhome.idea.vim.vscode.VimHost
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
private var statusBar: StatusBarItem? = null
private var commandLineBar: StatusBarItem? = null
private var host: VimHost? = null

@JsExport
fun activate(context: ExtensionContext) {
  val output = window.createOutputChannel("IdeaVim")
  channel = output

  val status = window.createStatusBarItem(StatusBarAlignment.Left, 100)
  statusBar = status
  status.show()

  // Vim puts the mode and the command line on the same last row, with the command line taking it
  // over while one is open. Two items side by side is the closest a status bar gets, and it lets
  // the mode stay visible while a command is being typed.
  val commandLine = window.createStatusBarItem(StatusBarAlignment.Left, 99)
  commandLineBar = commandLine

  val vim = VimHost(
    sink = OutputAndStatusBar(output, status),
    commandLineDisplay = StatusBarPrompt(commandLine),
  )
  vim.start()
  host = vim

  fun refreshMode() {
    status.text = "-- ${vim.modeName()} --"
  }
  refreshMode()

  /**
   * Taking over typing.
   *
   * `type` is one of VS Code's own commands - the one the editor runs for every printable
   * character - and registering a handler for it intercepts each one before the editor inserts it.
   * There is no other way for an extension to see ordinary typing: keybindings cover named keys,
   * not letters. Nothing else may claim it, so this is also why two Vim extensions cannot both be
   * enabled.
   */
  val typing = commands.registerCommand("type") { arguments ->
    val editor = window.activeTextEditor
    val text = arguments?.text as? String
    if (editor == null || text == null) {
      // No editor to type into, or an argument shaped differently than expected. Handing the key
      // back to VS Code is better than swallowing it.
      commands.executeCommand("default:type", arguments)
    } else {
      vim.type(editor, text)
      refreshMode()
    }
  }

  // Named keys, which arrive as commands because a keybinding cannot produce a character. Each one
  // carries the Vim notation for the key it stands for, so the manifest and the engine agree
  // without a table in between.
  val namedKey = commands.registerCommand("ideavim.key") { arguments ->
    val editor = window.activeTextEditor
    val notation = arguments as? String ?: arguments?.key as? String
    if (editor != null && notation != null) {
      vim.key(editor, notation)
      refreshMode()
    }
  }

  val activeEditorChanged = window.onDidChangeActiveTextEditor { editor ->
    if (editor != null) vim.editorFor(editor)
    refreshMode()
  }

  val selectionChanged = window.onDidChangeTextEditorSelection { event ->
    vim.selectionChanged(event.textEditor)
  }

  window.activeTextEditor?.let { vim.editorFor(it) }

  output.appendLine("IdeaVim is running. ${window.visibleTextEditors.size} editor(s) open.")

  val subscriptions = context.subscriptions
  for (registration in listOf<Disposable>(output, status, commandLine, typing, namedKey, activeEditorChanged, selectionChanged)) {
    subscriptions.push(registration)
  }
}

@JsExport
fun deactivate() {
  channel?.appendLine("IdeaVim deactivated.")
  channel = null
  statusBar = null
  commandLineBar = null
  host = null
}

/** The `:` and `/` prompts, on the status bar - the closest thing VS Code has to Vim's last line. */
private class StatusBarPrompt(private val item: StatusBarItem) : CommandLineDisplay {
  override fun show(text: String) {
    item.text = text
    item.show()
  }

  override fun hide() {
    item.text = ""
    item.hide()
  }
}

/** Vim's messages, to the output channel and the status bar - which is where Vim puts them. */
private class OutputAndStatusBar(
  private val output: OutputChannel,
  private val status: StatusBarItem,
) : MessageSink {
  override fun message(text: String?) {
    text?.let { output.appendLine(it) }
  }

  override fun error(text: String?) {
    text?.let {
      output.appendLine(it)
      output.show(preserveFocus = true)
    }
  }

  override fun status(text: String?) {
    status.tooltip = text ?: ""
  }
}
