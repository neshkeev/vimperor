/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vimscript.model.commands
import com.github.neshkeev.vimperor.directory.WorkingDirectory
import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.commands.Command
import com.maddyhome.idea.vim.vimscript.model.commands.CommandModifier

/**
 * `:cd`, `:lcd` and their spellings - the current directory this fork did not have.
 *
 * A project or workspace is not a working directory, which is why `:pwd` reported the project root
 * and there was nothing for `:cd` to change. See [WorkingDirectory] for what changed and, more
 * importantly, for what deliberately did not: a relative path is left alone until somebody runs
 * one of these, so no existing config moves.
 *
 * `:cd` with no argument goes to the home directory, as it does on Unix. `:cd -` goes back to where
 * you were. Both are the forms people type without thinking about them.
 *
 * see "h :cd"
 */
sealed class ChangeDirectoryCommandBase(
  range: Range,
  modifier: CommandModifier,
  argument: String,
  private val isLocal: Boolean,
) : Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val argument = commandArgument.trim()

    val wanted = when {
      argument.isEmpty() -> home() ?: throw exExceptionMessage("E472")
      argument == "-" -> WorkingDirectory.previous(editor) ?: throw exExceptionMessage("E472")
      else -> WorkingDirectory.resolve(argument, editor).ifEmpty { throw exExceptionMessage("E472") }
    }

    // Checked before it is set, because `:cd` is a refusal and not a report: a session whose
    // current directory is a place that does not exist would resolve every later path into it.
    if (!injector.fileSystem.isDirectory(wanted)) throw exExceptionMessage("E344", wanted)

    if (isLocal) WorkingDirectory.setLocal(editor, wanted) else WorkingDirectory.setGlobal(wanted)

    // Vim prints the new directory, which is the only feedback the command gives.
    injector.messages.showMessage(editor, wanted)
    return ExecutionResult.Success
  }

  /**
   * The home directory, which `:cd` with no argument goes to on Unix.
   *
   * Through the path expansion rather than the environment, because `~` is the engine's own
   * spelling of it and every other path here goes the same way. The environment is the fallback for
   * a host whose expansion leaves `~` alone.
   */
  private fun home(): String? {
    val expanded = injector.pathExpansion.expandPath("~")
    if (expanded.isNotEmpty() && expanded != "~") return expanded
    return injector.systemInfoService.getenv("HOME") ?: injector.systemInfoService.getenv("USERPROFILE")
  }
}

/** see "h :cd" */
@ExCommand(command = "cd,chd[ir]")
data class ChangeDirectoryCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  ChangeDirectoryCommandBase(range, modifier, argument, isLocal = false)

/**
 * `:lcd` - this window's own directory.
 *
 * `:tcd` is Vim's tab-local form, and it is the same command here: a tab in both hosts holds one
 * editor, so a tab-local directory and a window-local one cannot be told apart. The same collapse
 * `:tabnext` and `:tabclose` already live with.
 *
 * see "h :lcd"
 */
@ExCommand(command = "lc[d],lch[dir],tcd,tch[dir]")
data class ChangeLocalDirectoryCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  ChangeDirectoryCommandBase(range, modifier, argument, isLocal = true)
