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
 * Two commands in Vim over two different lists - `:first` walks the argument list and `:bfirst` the
 * buffer list - and one here, because neither host keeps an argument list separate from the files
 * it has open. `:brewind` is Vim's own second name for `:bfirst`.
 */
@ExCommand(command = "fir[st],bf[irst],br[ewind]")
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
