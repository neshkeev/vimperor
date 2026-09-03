/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getAllMappingInfoWithMode
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.isDefaultValue
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.directory.WorkingDirectory
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.options.ToggleOption
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * `:mkvimrc` and `:mkexrc` - the session written back out as the commands that would recreate it.
 *
 * Vim writes the mappings and the options that are not at their default, and this writes the same
 * two. What Vim has and this does not is `let` for the variables and `:behave`'s four options as a
 * group; a variable set in a config is not distinguishable here from one a script set a moment ago,
 * and writing the second kind into a config would be worse than leaving it out.
 *
 * **The default file is `.ideavimrc`, not `.vimrc`.** Vim's is the file Vim reads, and this fork's
 * hosts read `.ideavimrc` - writing `.vimrc` would produce a file that nothing here loads, which is
 * the one outcome a user running `:mkvimrc` cannot want. `:mkexrc` keeps `.exrc`, which both are
 * the same in Vim and which nothing reads either way.
 *
 * Relative to the working directory, as Vim's is - `:mkvimrc` writes into the directory you are in,
 * and it is `:mkvimrc ~/.ideavimrc` that writes to the one that gets loaded.
 *
 * See "h :mkvimrc".
 */
sealed class MakeConfigCommand(
  range: Range,
  private val modifier: CommandModifier,
  private val argument: String,
  private val defaultName: String,
) : Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val name = argument.trim().ifEmpty { defaultName }
    val path = resolve(name, editor, context)
    val files = injector.fileSystem

    // Vim refuses rather than reports: a `:mkvimrc` without `!` leaves a file that is already there
    // completely alone, which matters because the file it would overwrite is usually the config.
    if (modifier != CommandModifier.BANG && files.exists(path)) {
      throw exExceptionMessage("E189", path)
    }

    val failure = files.writeText(path, buildConfig(editor))
    if (failure != null) throw exExceptionMessage("E212")
    return ExecutionResult.Success
  }

  private fun resolve(name: String, editor: VimEditor, context: ExecutionContext): String {
    if (isAbsolute(name)) return name
    val directory = WorkingDirectory.current(editor, context) ?: return name
    return directory.trimEnd('/', '\\') + "/" + name
  }

  private fun isAbsolute(path: String): Boolean =
    path.startsWith("/") || path.startsWith("~") || path.startsWith("\\\\") ||
      (path.length > 2 && path[1] == ':')

  private fun buildConfig(editor: VimEditor): String = buildString {
    appendLine("\" Written by :$commandName. Everything in it was in effect when it ran.")
    appendLine()
    mappingLines().forEach { appendLine(it) }
    if (isNotEmpty()) appendLine()
    optionLines(editor).forEach { appendLine(it) }
  }

  /** The name this was invoked as, for the header - the two write the same file differently named. */
  protected abstract val commandName: String

  /**
   * Every mapping, as the command that would make it.
   *
   * `getAllMappingInfoWithMode` is what `:map` with no arguments lists, so what gets written is
   * exactly what gets shown - a mapping that appears in `:map` and not in this file would be a bug
   * in one of the two, and there is only one place to look.
   */
  private fun mappingLines(): List<String> =
    injector.keyGroup.getAllMappingInfoWithMode(emptyList(), MappingMode.entries.toSet())
      .map { entry ->
        val info = entry.mappingInfo
        val command = commandFor(entry.modes, info.isRecursive)
        "$command ${injector.parser.toKeyNotation(info.fromKeys)} ${info.getPresentableString()}"
      }
      .distinct()
      .sorted()

  /**
   * Vim's map command for a set of modes, and its `nore` form when the mapping does not recurse.
   *
   * A set with no command of its own - `{Normal, Insert}`, which nothing but an extension can make
   * - falls back to `map`, with the modes named in a comment rather than silently written as
   * something wider than they are.
   */
  private fun commandFor(modes: Set<MappingMode>, isRecursive: Boolean): String {
    val prefix = when (modes) {
      MappingMode.NVO -> ""
      MappingMode.N -> "n"
      MappingMode.V -> "v"
      MappingMode.X -> "x"
      MappingMode.S -> "s"
      MappingMode.O -> "o"
      MappingMode.I -> "i"
      MappingMode.C -> "c"
      MappingMode.L -> "l"
      else -> ""
    }
    val bang = if (modes == MappingMode.IC) "!" else ""
    return prefix + (if (isRecursive) "map" else "noremap") + bang
  }

  /**
   * Every option that is not at its default, as `:set`.
   *
   * The same rule `:set` with no arguments prints by, and for the same reason: an option at its
   * default is not something the user chose, and a config full of them says nothing while making
   * the next Vim upgrade's changed defaults invisible.
   */
  private fun optionLines(editor: VimEditor): List<String> {
    val optionGroup = injector.optionGroup
    // The effective scope, which is the one a bare `:set` reads and the one a bare `set` in a
    // config writes - so the file says what the session would say back.
    val scope = OptionAccessScope.EFFECTIVE(editor)
    return optionGroup.getAllOptions()
      .filter { !it.isHidden && !optionGroup.isDefaultValue(it, scope) }
      .sortedBy { it.name }
      .map { option ->
        val value = optionGroup.getOptionValue(option, scope)
        if (option is ToggleOption) {
          if (value.toVimNumber().booleanValue) "set ${option.name}" else "set no${option.name}"
        } else {
          "set ${option.name}=${value.toVimString().value}"
        }
      }
  }
}

/** see "h :mkvimrc" */
@ExCommand(command = "mkv[imrc]")
data class MkVimrcCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  MakeConfigCommand(range, modifier, argument, ".ideavimrc") {
  override val commandName: String get() = "mkvimrc"
}

/** see "h :mkexrc" */
@ExCommand(command = "mk[exrc]")
data class MkExrcCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  MakeConfigCommand(range, modifier, argument, ".exrc") {
  override val commandName: String get() = "mkexrc"
}
