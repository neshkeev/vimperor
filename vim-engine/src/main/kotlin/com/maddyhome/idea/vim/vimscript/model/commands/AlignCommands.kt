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
import com.maddyhome.idea.vim.api.getText
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt

/**
 * `:left`, `:right` and `:center` - the three commands that rewrite a line's indent.
 *
 * All three do the same work with a different sum: strip what is in front of the text, work out how
 * much should be there instead, and put that back. `:left` takes the answer straight from its
 * argument, `:right` subtracts the text's width from the margin, `:center` halves what is left.
 *
 * The indent is built with [VimEditor.createIndentBySize], so it is tabs or spaces according to
 * `'expandtab'` and `'tabstop'` - the same call every other indenting command in the engine makes,
 * rather than a string of spaces that would look right until someone opened the file in Vim.
 *
 * Lines are rewritten last-first. Replacing an indent changes the offsets of everything after it,
 * and the line table is rebuilt as it goes, so walking forwards would compute each line's start
 * from a buffer that had already moved.
 */
private fun realign(
  editor: VimEditor,
  lines: IntRange,
  indentOf: (text: String) -> Int,
): ExecutionResult {
  val caret = editor.currentCaret()
  for (line in lines.reversed()) {
    if (line >= editor.lineCount()) continue
    val start = editor.getLineStartOffset(line)
    val end = editor.getLineEndOffset(line)
    val text = editor.getText(start, end).trimStart(' ', '\t')
    // A blank line is left blank rather than padded out to the margin, which is what Vim does and
    // what anyone centring a block of prose expects of the gaps between its paragraphs.
    val indent = if (text.isEmpty()) "" else editor.createIndentBySize(indentOf(text).coerceAtLeast(0))
    injector.changeGroup.replaceText(editor, caret, start, end, indent + text)
  }
  return ExecutionResult.Success
}

/**
 * The margin `:right` and `:center` measure against.
 *
 * Vim's is `'textwidth'`, and 80 when that is zero. `'textwidth'` is not one of the engine's own
 * options - both hosts declare it, because it is a thing an editor does rather than a thing Vim
 * does to a buffer - so it is looked up by name, and the fallback covers a host that has neither.
 */
private fun marginFor(editor: VimEditor, argument: String): Int {
  argument.trim().takeIf { it.isNotEmpty() }?.let { text ->
    return text.toIntOrNull() ?: throw exExceptionMessage("E488", text)
  }
  val option = injector.optionGroup.getOption("textwidth") ?: return DEFAULT_MARGIN
  val width = (injector.optionGroup.getOptionValue(option, OptionAccessScope.EFFECTIVE(editor)) as? VimInt)?.value
  return width?.takeIf { it > 0 } ?: DEFAULT_MARGIN
}

private const val DEFAULT_MARGIN = 80

/**
 * see "h :left"
 *
 * `:left` with no argument moves the text to column zero; `:left 4` indents it by four.
 */
@ExCommand(command = "le[ft]")
data class LeftAlignCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.WRITABLE)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val indent = argument.trim().takeIf { it.isNotEmpty() }?.let {
      it.toIntOrNull() ?: throw exExceptionMessage("E488", it)
    } ?: 0
    val lineRange = getLineRange(editor)
    return realign(editor, lineRange.startLine..lineRange.endLine) { indent }
  }
}

/**
 * see "h :right"
 *
 * Pushes each line so that it ends at the margin - `'textwidth'`, or the argument, or 80.
 */
@ExCommand(command = "ri[ght]")
data class RightAlignCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.WRITABLE)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val margin = marginFor(editor, argument)
    val lineRange = getLineRange(editor)
    return realign(editor, lineRange.startLine..lineRange.endLine) { margin - it.length }
  }
}

/**
 * see "h :center"
 *
 * Puts half the leftover width in front of each line, rounding down, as Vim does.
 */
@ExCommand(command = "ce[nter]")
data class CenterAlignCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.WRITABLE)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val margin = marginFor(editor, argument)
    val lineRange = getLineRange(editor)
    return realign(editor, lineRange.startLine..lineRange.endLine) { (margin - it.length) / 2 }
  }
}
