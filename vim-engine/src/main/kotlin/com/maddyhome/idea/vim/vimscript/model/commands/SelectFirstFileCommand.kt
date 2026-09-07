/*
 * Copyright 2003-2023 The IdeaVim authors
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
 * see "h :first" / "h :bfirst"
 *
 * Several commands in Vim over three different lists - `:first` walks the argument list, `:bfirst`
 * the buffer list and `:tabfirst` the tab pages - and one here, because neither host keeps those
 * apart: a file appears once, in one list, whichever of the three you ask about. `:rewind`, `:brewind` and
 * `:tabrewind` are Vim's own second names for the three.
 */
@ExCommand(command = "fir[st],rew[ind],bf[irst],br[ewind],tabfir[st],tabr[ewind]")
data class SelectFirstFileCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val res = injector.file.selectFile(0, context)
    if (res) {
      injector.jumpService.saveJumpLocation(editor)
    }
    return if (res) ExecutionResult.Success else ExecutionResult.Error
  }
}
