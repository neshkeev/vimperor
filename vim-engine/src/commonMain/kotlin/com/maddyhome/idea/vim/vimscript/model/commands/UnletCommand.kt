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
import com.maddyhome.idea.vim.vimscript.model.expressions.Scope
import com.maddyhome.idea.vim.vimscript.model.expressions.VariableExpression

/**
 * see "h :unlet"
 *
 * The other half of `:let`, and the one a config reaches for when it is undoing itself - clearing a
 * plugin's settings before setting them again, or dropping a guard variable so a file can be
 * sourced twice.
 *
 * `:unlet {name} ...` takes any number of names and removes each. Without the bang, a name that was
 * never set is `E108`, and Vim stops there rather than carrying on down the list; `:unlet!` removes
 * what it finds and says nothing about the rest, which is what makes it usable at the top of a file
 * that may or may not have been sourced before.
 */
@ExCommand(command = "unl[et]")
data class UnletCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_REQUIRED, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val ignoreMissing = modifier == CommandModifier.BANG
    for (name in argument.trim().split(" ").filter { it.isNotBlank() }) {
      val removed = injector.variableService.removeVariable(parseVariable(name), editor, context, vimContext)
      if (!removed && !ignoreMissing) throw exExceptionMessage("E108", name)
    }
    return ExecutionResult.Success
  }

  /**
   * `g:name` into the scope and the name, with no scope for a bare one.
   *
   * Vim also accepts a place *inside* a variable here - `unlet d['a']`, `unlet l[3]` - which needs
   * the expression parser rather than a split. Those come back as `E108` for a variable of that
   * literal name, which is a worse message than Vim's but not a wrong one: nothing was removed.
   */
  private fun parseVariable(name: String): VariableExpression {
    val parts = name.split(":")
    return when (parts.size) {
      1 -> VariableExpression(null, parts[0])
      2 -> VariableExpression(Scope.getByValue(parts[0]), parts[1])
      else -> throw exExceptionMessage("E461", name)
    }
  }
}
