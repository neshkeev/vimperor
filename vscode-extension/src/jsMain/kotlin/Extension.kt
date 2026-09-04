/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

@file:OptIn(ExperimentalJsExport::class)

// No `package` declaration, deliberately - see the note below.

import com.maddyhome.idea.vim.vscode.DecorationHighlighter
import com.maddyhome.idea.vim.vscode.Disposable
import com.maddyhome.idea.vim.vscode.ExtensionContext
import com.maddyhome.idea.vim.vscode.IdeaActionAliases
import com.maddyhome.idea.vim.vscode.isVimsOwnConfig
import com.maddyhome.idea.vim.vscode.MessageSink
import com.maddyhome.idea.vim.vscode.NodeFileSystem
import com.maddyhome.idea.vim.vscode.contributedKeybindings
import com.maddyhome.idea.vim.vscode.hostPlatform
import com.maddyhome.idea.vim.vscode.readDefaultKeybindings
import com.maddyhome.idea.vim.vscode.userKeybindings
import com.maddyhome.idea.vim.vscode.userKeybindingsPath
import com.maddyhome.idea.vim.vscode.openTutor
import com.maddyhome.idea.vim.vscode.OutputChannel
import com.maddyhome.idea.vim.vscode.OutputChannelPanelService
import com.maddyhome.idea.vim.vscode.StatusBarAlignment
import com.maddyhome.idea.vim.vscode.StatusBarItem
import com.maddyhome.idea.vim.vscode.StatusBarPrompt
import com.maddyhome.idea.vim.vscode.TextEditor
import com.maddyhome.idea.vim.vscode.VimHost
import com.maddyhome.idea.vim.vscode.VsCodeClipboard
import com.maddyhome.idea.vim.vscode.VsCodeCommands
import com.maddyhome.idea.vim.vscode.commands
import com.maddyhome.idea.vim.vscode.window
import com.maddyhome.idea.vim.vscode.workspace

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
  val output = window.createOutputChannel("Vimperor")
  channel = output

  val status = window.createStatusBarItem(StatusBarAlignment.Left, 100)
  statusBar = status
  status.show()

  // Vim puts the mode and the command line on the same last row, with the command line taking it
  // over while one is open. Two items side by side is the closest a status bar gets, and it lets
  // the mode stay visible while a command is being typed.
  val commandLine = window.createStatusBarItem(StatusBarAlignment.Left, 99)
  commandLineBar = commandLine

  // Vim's wildmenu, which it draws on the line above the command line. There is one row here, so it
  // sits to the right of the prompt instead and is hidden whenever Tab is not walking a list.
  val matches = window.createStatusBarItem(StatusBarAlignment.Left, 98)

  val vim = VimHost(
    sink = OutputAndStatusBar(output, status),
    commandLineDisplay = StatusBarPrompt(commandLine, matches),
    highlighter = DecorationHighlighter(),
    clipboard = VsCodeClipboard(),
    outputPanel = OutputChannelPanelService(output),
  )
  vim.start()
  host = vim

  /**
   * The mode, on the status bar and in VS Code's `when` context.
   *
   * The context is not decoration. Half the control chords in `package.json` are bound only outside
   * Insert mode - `ctrl+v` is block Visual to Vim and paste to everyone else - and a `when` clause
   * is the only place that distinction can be made, because a keybinding either claims a key or
   * does not. Without `vimperor.mode` those bindings would either never fire or would steal paste.
   */
  var lastMode: String? = null
  var styledEditor: TextEditor? = null
  fun refreshMode() {
    val mode = vim.modeName()
    status.text = "-- " + mode + " --"

    // The caret's shape, which is the mode indicator a user actually reads - a block in Normal and
    // Visual, a bar in Insert, an underline in Replace, which is Vim's own default `guicursor`.
    //
    // Tracked against the editor as well as the mode, because a newly focused editor needs the
    // shape applied even when the mode has not changed: VS Code gives each editor its own options,
    // and one opened while in Normal mode would otherwise keep the user's default bar.
    val editor = window.activeTextEditor
    if (editor != null && (mode != lastMode || editor !== styledEditor)) {
      styledEditor = editor
      editor.options.cursorStyle = vim.cursorStyleFor(mode)
    }

    // Only on a change. This runs after every keystroke, and `setContext` is a round trip to VS
    // Code that re-evaluates every `when` clause in the window - sending it for each character of
    // an insert would be fifty of them for a typed word.
    if (mode != lastMode) {
      lastMode = mode
      commands.executeCommand("setContext", "vimperor.mode", mode)
    }
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
      reporting(output, "typing '" + text + "'") {
        vim.type(editor, text)
        refreshMode()
        trace(output, vim, editor, "'" + text + "'")
      }
    }
  }

  // Named keys, which arrive as commands because a keybinding cannot produce a character. Each one
  // carries the Vim notation for the key it stands for, so the manifest and the engine agree
  // without a table in between.
  val namedKey = commands.registerCommand("vimperor.key") { arguments ->
    val editor = window.activeTextEditor
    val notation = arguments as? String ?: arguments?.key as? String
    if (editor != null && notation != null) {
      reporting(output, "the key " + notation) {
        vim.key(editor, notation)
        refreshMode()
        trace(output, vim, editor, notation)
      }
    }
  }

  // `BufLeave` before `BufEnter`, in that order, which is the order Vim fires them and the order
  // IdeaVim's own editor listener uses. The one being left has to be named before it stops being
  // the active editor, so the host remembers it rather than asking VS Code afterwards.
  val activeEditorChanged = window.onDidChangeActiveTextEditor { editor ->
    vim.activeEditorChanged(editor)
    refreshMode()
  }

  val selectionChanged = window.onDidChangeTextEditorSelection { event ->
    vim.selectionChanged(event.textEditor, event.kind)
  }

  // A buffer unloaded. Nothing was listening for this, so the host kept an editor - and its whole
  // text, and its markers - for every file opened in the session.
  val documentClosed = workspace.onDidCloseTextDocument { document ->
    vim.forgetDocument(document)
  }

  // `BufWritePost`. `BufWritePre` is not fired: VS Code's `onWillSaveTextDocument` wants the edits
  // handed back as a promise of `TextEdit`s, and this host applies its own asynchronously - so an
  // autocommand that stripped trailing whitespace could land after the file was written. Firing
  // nothing is better than firing it too late.
  val documentSaved = workspace.onDidSaveTextDocument { document ->
    vim.documentSaved(document)
  }

  // The clipboard can change while VS Code is not looking, and this is when it finds out: a user
  // copying in a browser and switching back is exactly the case `"+p` has to get right.
  val windowStateChanged = window.onDidChangeWindowState { state ->
    if (state.focused) vim.refreshClipboard()
    vim.windowFocusChanged(state.focused)
  }
  vim.refreshClipboard()

  // Before any key reaches the engine: the config is where mappings and options come from, and a
  // key handled ahead of it would use the defaults.
  //
  // Not conditional on there being an editor, which it used to be - and that was a real hole rather
  // than a tidy guard. `onStartupFinished` fires before VS Code has focused a restored editor, so
  // `activeTextEditor` is routinely null at this moment even when one is open, and a window opened
  // on a folder rather than a file has none at all. The config was then never read for the whole
  // session, silently, because the message saying so was inside the same `let`. It runs against the
  // fallback window when there is nothing on screen.
  val loaded = vim.loadVimRc(vim.startupEditor(window.activeTextEditor))
  if (loaded == null) {
    output.appendLine("No .vimperorrc, .ideavimrc or .vimrc found.")
  } else {
    output.appendLine("Loaded " + loaded)
    // Only for Vim's own file, because it is the only one written for a different program. A
    // config runs with errors suppressed, so without this line a `.vimrc` whose plugin manager and
    // autocommands did nothing looks exactly like one that worked.
    if (isVimsOwnConfig(loaded)) {
      output.appendLine("That is a Vim config. Lines this extension does not implement - plugin")
      output.appendLine("managers, autocommands, syntax - are skipped without complaint.")
    }
  }

  output.appendLine("Vimperor is running. ${window.visibleTextEditors.size} editor(s) open.")

  // The one check that cannot be made anywhere but here. Every VS Code command this extension sends
  // is a string written from the documentation, and nothing offline can say whether VS Code has it:
  // the tests assert the string, and the stub answers to whatever the string says. This asks the
  // real VS Code, and it is deliberately not fatal - a missing id costs one Vim command, and
  // refusing to start over it would cost all of them.
  //
  // The answer is also the whole of what `:action` and `:actionlist` can offer, so it is kept -
  // `filterInternal = false` because a user typing `:action` is entitled to everything this editor
  // can do, and VS Code's idea of "internal" is a leading underscore rather than a promise that the
  // command is unusable.
  commands.getCommands(filterInternal = false).then({ available ->
    vim.rememberActions(available.toList())
    val missing = VsCodeCommands.missingFrom(available.toList())
    if (missing.isEmpty()) {
      output.appendLine("Checked ${VsCodeCommands.all.size} VS Code commands; this VS Code has all of them.")
    } else {
      output.appendLine(
        "This VS Code does not have ${missing.size} of the ${VsCodeCommands.all.size} commands Vimperor " +
          "uses, so the Vim commands that need them will do nothing: " + missing.joinToString(", "),
      )
    }

    // The same check for the table that translates IntelliJ's action names, which is a page of
    // strings typed from documentation and the least checkable thing in the module now. Only the
    // missing ones are printed: a window with the Git extension turned off is a real window and
    // does not need a paragraph about it every time it starts.
    val missingAliases = IdeaActionAliases.missingFrom(available.toList())
    if (missingAliases.isNotEmpty()) {
      output.appendLine(
        "${missingAliases.size} of the ${IdeaActionAliases.targets.size} IntelliJ action aliases point at " +
          "commands this VS Code does not have: " + missingAliases.joinToString(", "),
      )
    }
  })

  /*
   * What each command is bound to, for `:actionlist`.
   *
   * VS Code will not say - there is no API for it - so the three files it builds its own keymap out
   * of are read instead, and they arrive by three different routes. Two of them are here and now:
   * every extension's manifest is already parsed, and the user's `keybindings.json` is a file. The
   * third is VS Code's own defaults, which is where `shift+cmd+f` and the rest of the core chords
   * live, and it comes over a promise. See [KeybindingTable] for why arriving last does not make it
   * apply last.
   */
  val platform = hostPlatform()

  // Inside a `try`, all of it, because none of it is load-bearing. A column in `:actionlist` is
  // worth reading three files for and is not worth an activation for: a throw here would take the
  // tutor, the paste chord and every registration below it down with it.
  try {
    vim.keybindings.setContributed(contributedKeybindings(platform))

    val keybindingsFile = userKeybindingsPath(context.globalStorageUri.fsPath)
    vim.keybindings.setUser(userKeybindings(keybindingsFile, NodeFileSystem(), platform))

    readDefaultKeybindings(
      platform,
      onFailure = { reason ->
        output.appendLine(
          "This VS Code would not hand over its default keybindings ($reason), so `:actionlist` " +
            "will only show the chords that extensions and your own keybindings.json bind.",
        )
      },
      onLoaded = { defaults ->
        vim.keybindings.setDefaults(defaults)
        output.appendLine("Read the keybindings of ${vim.keybindings.commandCount} commands, for `:actionlist`.")
      },
    )
  } catch (e: Throwable) {
    output.appendLine("Vimperor could not read this window's keybindings, so `:actionlist` will not show them - ${e.message}")
  }

  // Vim's `vimtutor`, as a Command Palette entry. It opens an untitled document the reader is
  // meant to edit to pieces, which is what `vimtutor` does with a copy of Vim's own tutor file.
  val tutor = commands.registerCommand("vimperor.tutor") {
    reporting(output, "the tutor") {
      openTutor { failure -> output.appendLine("Vimperor: the tutor could not be opened - $failure") }
    }
  }

  // The system paste chord at the `:` and `/` prompts. A command rather than a `vimperor.key`
  // binding because it waits for the clipboard to be re-read before it types anything - see
  // [VimHost.pasteIntoCommandLine]. The manifest claims the chord only while the prompt is open, so
  // in every other mode VS Code's own paste is what runs.
  val pasteInCommandLine = commands.registerCommand("vimperor.pasteInCommandLine") {
    val editor = window.activeTextEditor
    if (editor != null) {
      reporting(output, "pasting into the command line") {
        vim.pasteIntoCommandLine(editor) {
          refreshMode()
          trace(output, vim, editor, "paste")
        }
      }
    }
  }

  val subscriptions = context.subscriptions
  for (registration in listOf<Disposable>(
    output, status, commandLine, matches, typing, namedKey, tutor, pasteInCommandLine,
    activeEditorChanged, selectionChanged, windowStateChanged, documentClosed, documentSaved,
  )) {
    subscriptions.push(registration)
  }
}

@JsExport
fun deactivate() {
  channel?.appendLine("Vimperor deactivated.")
  channel = null
  statusBar = null
  commandLineBar = null
  host = null
}

/**
 * Runs a keystroke and says so in the output channel if it throws.
 *
 * VS Code catches an exception from a command handler, shows a generic notification and writes the
 * detail to the extension host log - which is a different window from the one this extension prints
 * to, and is not where anybody looks. A key that fails silently is the hardest kind of bug to report
 * and the easiest to fix once it has a name, so it gets named here.
 */
/**
 * Writes what a keystroke did to the output channel, when `vimperor.trace` is on.
 *
 * Read on every key rather than cached, so that turning it on takes effect without a reload - which
 * matters, because the bugs it is for are the ones that have already happened by the time anybody
 * thinks to look.
 */
private fun trace(output: OutputChannel, vim: VimHost, editor: TextEditor, key: String) {
  if (workspace.getConfiguration("vimperor").get("trace") != true) return
  output.appendLine("  $key -> " + vim.describeState(editor))
}

private inline fun reporting(output: OutputChannel, what: String, block: () -> Unit) {
  try {
    block()
  } catch (e: Throwable) {
    output.appendLine("Vimperor: " + what + " failed - " + e::class.simpleName + ": " + e.message)
    output.show(preserveFocus = true)
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
