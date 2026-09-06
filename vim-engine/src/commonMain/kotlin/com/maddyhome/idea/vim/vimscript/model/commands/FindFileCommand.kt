/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.intellij.vim.annotations.ExCommand
import com.github.neshkeev.vimperor.directory.WorkingDirectory
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * see "h :file"
 */
@ExCommand(command = "fin[d]")
data class FindFileCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val arg = argument
    if (arg.isNotEmpty()) {
      val errorMessage = injector.file.openFile(WorkingDirectory.resolve(arg, editor), context)
      if (errorMessage != null) {
        injector.messages.showMessage(editor, errorMessage)
        return ExecutionResult.Error
      }
      injector.jumpService.saveJumpLocation(editor)
      return ExecutionResult.Success
    }

    injector.application.invokeLater {
      injector.actionExecutor.executeAction(
        editor,
        name = "GotoFile",
        context = context
      )
    }

    return ExecutionResult.Success
  }
}

/**
 * `:tabfind {file}` - `:find`, and in these hosts that is all it is.
 *
 * Vim opens the file in a new tab page, which is a window holding windows. Neither host has that
 * shape: IntelliJ and VS Code both open a file in its own tab already, and a `:find` that landed in
 * the current one would be the odd behaviour rather than this. So the two commands are the same
 * journey, and `:tabfind` is registered rather than left as `E492` so that a config written for
 * Vim reads the same here.
 *
 * see "h :tabfind"
 */
@ExCommand(command = "tabf[ind]")
data class TabFindFileCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = FindFileCommand(range, modifier, argument)
    .also { it.vimContext = vimContext }
    .processCommand(editor, context, operatorArguments)
}
