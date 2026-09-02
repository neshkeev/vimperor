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
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * see "h :startinsert"
 *
 * The twin of `:stopinsert`, which the engine has had all along. `:startinsert` works like typing
 * `i` and `:startinsert!` like typing `A`, and both are what a mapping reaches for when it wants to
 * run an ex command and leave the user typing.
 */
@ExCommand(command = "star[tinsert]")
data class StartInsertCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_FORBIDDEN, Access.SELF_SYNCHRONIZED)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    if (editor.mode is Mode.INSERT || editor.mode is Mode.REPLACE) {
      return ExecutionResult.Success
    }
    if (modifier == CommandModifier.BANG) {
      injector.changeGroup.insertAfterLineEnd(editor, context)
    } else {
      injector.changeGroup.insertBeforeCaret(editor, context)
    }
    return ExecutionResult.Success
  }
}
