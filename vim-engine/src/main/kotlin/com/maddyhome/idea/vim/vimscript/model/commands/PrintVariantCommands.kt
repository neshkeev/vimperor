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
import com.maddyhome.idea.vim.api.Options
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * `:number` and `:list` - `:print` with the two things it can be asked to show.
 *
 * Vim has three commands here rather than one with flags, and each is `:print` plus one decision:
 * `:number` numbers the lines whatever `'number'` is set to, and `:list` shows where each line ends
 * and what is in it that would otherwise be invisible. Both share [PrintCommand]'s rendering, so a
 * range, a count and the caret landing on the last line all behave the same way across the three.
 */
private fun printRange(
  command: Command,
  editor: VimEditor,
  context: ExecutionContext,
  lineRange: com.maddyhome.idea.vim.ex.ranges.LineRange,
  forceNumbers: Boolean,
  listMode: Boolean,
): ExecutionResult {
  editor.removeSecondaryCarets()
  val caret = editor.currentCaret()
  caret.moveToOffset(injector.motion.moveCaretToLineStartSkipLeading(editor, lineRange.endLine))
  val lines = (lineRange.startLine..lineRange.endLine).toList()
  injector.outputPanel.output(editor, context, PrintCommand.getText(editor, lines, forceNumbers, listMode))
  return ExecutionResult.Success
}

/** see "h :number" */
@ExCommand(command = "nu[mber]")
data class NumberCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult =
    printRange(this, editor, context, getLineRangeWithCount(editor, editor.currentCaret()), true, false)
}

/** see "h :list" */
@ExCommand(command = "l[ist]")
data class ListCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult =
    printRange(this, editor, context, getLineRangeWithCount(editor, editor.currentCaret()), false, true)
}

/**
 * see "h :z"
 *
 * Prints a screenful of lines starting at the addressed one - `ed`'s command, which Vim keeps.
 * `:z` alone uses twice `'scroll'`, and `:z {count}` that many lines.
 *
 * Vim also takes a mark between the name and the count - `:z+`, `:z-`, `:z^`, `:z.`, `:z=` - which
 * says where the addressed line sits in what is printed. Those are `E488` rather than quietly
 * treated as the plain form, because a `:z-` that printed the lines *after* the address would be
 * showing the wrong half of the file.
 */
@ExCommand(command = "z")
data class ZCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val arg = argument.trim()
    if (arg.isNotEmpty() && !arg.all { it.isDigit() }) throw exExceptionMessage("E488", arg)

    val scroll = injector.optionGroup.getOptionValue(Options.scroll, OptionAccessScope.EFFECTIVE(editor)).value
    val count = arg.toIntOrNull() ?: (if (scroll > 0) scroll * 2 else DEFAULT_WINDOW)
    val start = getLine(editor)
    val end = (start + count - 1).coerceAtMost(editor.lineCount() - 1)

    editor.removeSecondaryCarets()
    editor.currentCaret().moveToOffset(injector.motion.moveCaretToLineStartSkipLeading(editor, end))
    injector.outputPanel.output(editor, context, PrintCommand.getText(editor, (start..end).toList()))
    return ExecutionResult.Success
  }

  private companion object {
    /** `'scroll'` is zero until a window has a height to halve, and Vim falls back to a screenful. */
    const val DEFAULT_WINDOW = 24
  }
}
