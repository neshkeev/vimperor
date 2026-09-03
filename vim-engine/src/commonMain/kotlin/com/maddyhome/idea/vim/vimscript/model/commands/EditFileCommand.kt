/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.directory.WorkingDirectory
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * see "h :edit" / "h :drop" / "h :view" / "h :visual"
 *
 * `:drop` differs from `:edit` in Vim by reusing a window that already shows the file rather than
 * loading it into this one - which is what both hosts do anyway, since opening a file that is
 * already open moves you to its tab.
 *
 * `:view` and `:visual` are `:edit` with `'readonly'` set and cleared. Neither host has a per-buffer
 * read-only flag an extension can set, so both open the file and the flag is what is lost; the file
 * being opened is the part anyone typing `:view` was after.
 */
@ExCommand(command = "e[dit],bro[wse],dr[op],vie[w],vi[sual]")
data class EditFileCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val arg = argument.trim()
    if (arg == "#") {
      injector.jumpService.saveJumpLocation(editor)
      if (!injector.file.selectPreviousTab(context)) {
        injector.messages.indicateError()
      }
      return ExecutionResult.Success
    } else if (arg.isNotEmpty()) {
      val errorMessage = injector.file.openFile(WorkingDirectory.resolve(arg, editor), context)
      if (errorMessage != null) {
        injector.messages.showMessage(editor, errorMessage)
        return ExecutionResult.Error
      }
      injector.jumpService.saveJumpLocation(editor)
      return ExecutionResult.Success
    }

    // Don't open a choose file dialog under a write action
    injector.application.invokeLater {
      injector.actionExecutor.executeAction(editor, name = "OpenFile", context = context)
    }

    return ExecutionResult.Success
  }
}
