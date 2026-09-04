/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * The commands that name something the editor has already decided for itself.
 *
 * Every one of these is in the first twenty lines of a great many `~/.vimrc` files, and every one
 * of them asks for something an IDE settles without being asked: which colours the text is in, which
 * language a file is, where a plugin's files live, whether help is indexed. An unknown ex command
 * is `E492`, so a `~/.vimrc` sourced from an `.ideavimrc` used to produce one line of red per line
 * of configuration - which is a config that stops being read rather than a config with a problem.
 *
 * So they accept, quietly. [contradiction] is the other half and the reason this is not simply a
 * list of no-ops: `:syntax off` and `:filetype off` ask for something the editor will *not* do, and
 * those are worth a word precisely because they are rare. A message on every `syntax on` would be
 * the wall of red again in a different colour.
 *
 * This lived in the VS Code host until now, which meant the IntelliJ plugin reported `E492` for all
 * of it - `:syntax on` included. The messages say "the editor" rather than naming one, because
 * there are two of them and the sentence is true of both.
 *
 * `:language` was the first of these to move into the engine and its documentation carries the
 * longer version of this argument; see [LanguageCommand].
 */
internal abstract class EditorDecidesCommand(
  range: Range,
  modifier: CommandModifier,
  private val argumentText: String,
) : Command.SingleExecution(range, modifier, argumentText) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  /** What to say when the argument asks for something this editor will not do. Null to stay quiet. */
  protected open fun contradiction(argument: String): String? = null

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    contradiction(argumentText.trim())?.let { injector.messages.showMessage(editor, it) }
    return ExecutionResult.Success
  }
}

/**
 * `:syntax`, which is on and cannot be turned off.
 *
 * `syntax on` is the line this was written for: it is in most `~/.vimrc` files and it was
 * `E492: Not an editor command`. Both hosts highlight with machinery of their own - IntelliJ with a
 * lexer, VS Code with a grammar - and neither offers an extension a way to stop it for one buffer.
 *
 * see "h :syntax"
 */
@ExCommand(command = "sy[ntax]")
internal data class SyntaxCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  EditorDecidesCommand(range, modifier, argument) {
  override fun contradiction(argument: String): String? =
    if (argument == "off" || argument == "clear") {
      "The editor highlights with its own lexer or grammar and cannot be told to stop, " +
        "so `:syntax $argument` did nothing."
    } else {
      null
    }
}

/**
 * `:filetype`, which the editor works out from the file.
 *
 * `:filetype plugin indent on` is the usual line and is the one that must not produce an error.
 * `off` is the one worth a word, because that is a config asking for detection to *stop*.
 *
 * see "h :filetype"
 */
@ExCommand(command = "filet[ype]")
internal data class FiletypeCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  EditorDecidesCommand(range, modifier, argument) {
  override fun contradiction(argument: String): String? =
    if (argument.split(" ").any { it == "off" }) {
      "The editor works out a file's language itself, so `:filetype $argument` did nothing."
    } else {
      null
    }
}

/**
 * `:colorscheme`, which is a setting of the editor's rather than a property of a buffer.
 *
 * With no argument Vim prints the current scheme, so the no-argument form stays quiet; with one it
 * is being asked to change something, and that is the form that gets an answer.
 *
 * see "h :colorscheme"
 */
@ExCommand(command = "colo[rscheme]")
internal data class ColorschemeCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  EditorDecidesCommand(range, modifier, argument) {
  override fun contradiction(argument: String): String? =
    if (argument.isEmpty()) {
      null
    } else {
      "The colour theme is the editor's own setting rather than a buffer's, so " +
        "`:colorscheme $argument` did nothing. IntelliJ changes it in Settings | Appearance, and " +
        "VS Code in the Command Palette under Preferences: Color Theme."
    }
}

/**
 * `:runtime`, which sources Vim script files found on `'runtimepath'`.
 *
 * Quiet rather than explanatory, because a banged `:runtime!` over a glob of plugin files is a
 * *probe* - it is written to find files that may not be there, and Vim itself says nothing when it
 * finds none. An error here would be reporting the normal case.
 *
 * see "h :runtime"
 */
@ExCommand(command = "ru[ntime]")
internal data class RuntimeCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  EditorDecidesCommand(range, modifier, argument)

/**
 * `:packloadall`, which loads the plugins under `pack/`.
 *
 * There is no `pack/` directory to load from: neither host runs Vim plugins, and the extensions
 * this fork does have are its own. Quiet for the same reason `:runtime` is - a config that calls
 * this is asking for whatever happens to be installed.
 *
 * see "h :packloadall"
 */
@ExCommand(command = "packl[oadall]")
internal data class PackLoadAllCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  EditorDecidesCommand(range, modifier, argument)

/**
 * `:helptags`, which builds the index Vim's `:help` searches.
 *
 * `:help` here opens the editor's own documentation, so there is no tags file to build and nothing
 * that would read one. A config runs this after installing a plugin, which is a thing that has
 * already not happened by the time this is reached.
 *
 * see "h :helptags"
 */
@ExCommand(command = "helpt[ags]")
internal data class HelpTagsCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  EditorDecidesCommand(range, modifier, argument)

/**
 * `:mkview` and `:loadview` - the folds, the caret and the options of a window, written to a file.
 *
 * Both hosts restore where you were in a file themselves, and both do it across restarts, which is
 * the thing `:mkview` is usually in a config to get. What they do not do is Vim's *numbered* views,
 * and that is what is missing rather than the idea. Quiet, because a config that writes a view on
 * `BufWinLeave` and reads it on `BufWinEnter` is asking for something it is already getting.
 *
 * see "h :mkview", "h :loadview"
 */
@ExCommand(command = "mkvie[w]")
internal data class MakeViewCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  EditorDecidesCommand(range, modifier, argument)

/** see "h :loadview" - the other half of [MakeViewCommand]. */
@ExCommand(command = "loadv[iew]")
internal data class LoadViewCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  EditorDecidesCommand(range, modifier, argument)

/**
 * `:version` - which Vim this is, which here is which fork of which plugin.
 *
 * Not one of the accepted-and-quiet ones: it asks a question that has an answer, and the answer is
 * worth printing. A bug report that says "Vimperor" and a version is worth more than one that says
 * the command did nothing.
 *
 * Vim also accepts `:version {nr}`, which it has ignored for twenty years - it used to check the
 * version of a `.vimrc`. Ignored here too, and for the same reason it is ignored there.
 *
 * see "h :version"
 */
@ExCommand(command = "ve[rsion]")
internal data class VersionCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    injector.outputPanel.output(editor, context, text())
    return ExecutionResult.Success
  }

  private fun text(): String = buildString {
    appendLine("Vimperor - a hard fork of IdeaVim, sharing one Vim engine between two editors.")
    appendLine()
    appendLine("The engine is Kotlin Multiplatform: the same source compiles to the JVM for the")
    appendLine("IntelliJ plugin and to JavaScript for the VS Code extension, so a fix to a motion,")
    appendLine("an ex command or the regex engine reaches both hosts at once.")
    appendLine()
    appendLine("Configuration is read from ~/.ideavimrc, and XDG's config directory is honoured.")
    appendLine("In VS Code a ~/.vimperorrc is preferred over it, and ~/.vimrc is read after it.")
    appendLine("`:help` opens the editor's own documentation rather than Vim's.")
  }
}
