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
 * `:earlier` and `:later` - Vim's undo tree, over a history that has no branches.
 *
 * Vim's undo is a tree and these two walk it by state count, by number of file writes (`10f`) or by
 * elapsed time (`5m`), none of which a linear history can answer. Both hosts have a linear one, so
 * `:earlier {N}` is N undos and `:later {N}` is N redos - which is exactly right for the history
 * these hosts keep, and is what `:earlier` means whenever nothing has branched, which is the case
 * for anyone who has not used `u` and then typed something new.
 *
 * The counted forms are `E475`, because guessing at them would be worse than saying so: `:earlier
 * 5m` asking for five minutes ago and getting five undos is a silently wrong answer to a question
 * about time.
 */
private fun travel(
  editor: VimEditor,
  context: ExecutionContext,
  argument: String,
  step: (VimEditor, ExecutionContext) -> Boolean,
): ExecutionResult {
  val arg = argument.trim()
  val count = when {
    arg.isEmpty() -> 1
    else -> arg.toIntOrNull() ?: throw exExceptionMessage("E475", arg)
  }
  if (count < 0) throw exExceptionMessage("E475", arg)

  var moved = false
  repeat(count) {
    if (step(editor, context)) moved = true
  }
  if (moved) injector.scroll.scrollCaretIntoView(editor)
  // Running out of history is not an error in Vim - it reports "Already at oldest change" - and
  // running out part way through is not either, so only a step that never moved at all fails.
  return if (moved || count == 0) ExecutionResult.Success else ExecutionResult.Error
}

/** see "h :earlier" */
@ExCommand(command = "ea[rlier]")
data class EarlierCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.WRITABLE)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = travel(editor, context, argument) { e, c -> injector.undo.undo(e, c) }
}

/** see "h :later" */
@ExCommand(command = "lat[er]")
data class LaterCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.WRITABLE)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = travel(editor, context, argument) { e, c -> injector.undo.redo(e, c) }
}
