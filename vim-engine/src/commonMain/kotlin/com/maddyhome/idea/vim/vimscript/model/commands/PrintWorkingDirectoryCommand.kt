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
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * see "h :pwd"
 *
 * Prints the directory a relative path is resolved against. Vim's is a real current directory that
 * `:cd` moves; neither host has one, so each answers with the thing it actually resolves against -
 * IntelliJ the project's base directory, VS Code the folder open in the window. That is why `:pwd`
 * works in both and `:cd` cannot.
 *
 * `E187` when there is no folder at all, which is Vim's own error for a directory it cannot name.
 */
@ExCommand(command = "pw[d]")
data class PrintWorkingDirectoryCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val directory = injector.file.getWorkingDirectory(context) ?: throw exExceptionMessage("E187")
    injector.messages.showMessage(editor, directory)
    return ExecutionResult.Success
  }
}
