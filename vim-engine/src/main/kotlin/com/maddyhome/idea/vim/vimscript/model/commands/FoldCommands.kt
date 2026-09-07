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
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * `:fold`, `:foldopen` and `:foldclose` - `zf`, `zo` and `zc` addressed by line instead of by caret.
 *
 * Each does exactly what its key does, through the same call, which is the point: `zf` makes a fold
 * region and `zo`/`zc` ask the host to open or close the one the caret is in, and a host that gains
 * either later gains it for the command at the same moment. Writing these against a second
 * mechanism would have made three more places to fix.
 *
 * What is lost against Vim is the *span*. Vim opens every fold in the range; both hosts fold by
 * "the region the caret is in" rather than by a pair of offsets, so the caret goes to the first
 * line of the range and the fold there is the one that opens. For `:foldopen` and `:foldclose`
 * without a range - which is how they are almost always written, and is `zo` and `zc` - there is no
 * difference at all.
 */
private fun foldAt(
  editor: VimEditor,
  context: ExecutionContext,
  line: Int,
  action: String,
): ExecutionResult {
  editor.removeSecondaryCarets()
  editor.currentCaret().moveToOffset(injector.motion.moveCaretToLineStartSkipLeading(editor, line))
  injector.actionExecutor.executeAction(editor, action, context)
  return ExecutionResult.Success
}

/**
 * see "h :fold"
 *
 * Makes a fold over the range and closes it, which is `zf` with the range written out rather than
 * moved over.
 */
@ExCommand(command = "fo[ld]")
data class FoldCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_FORBIDDEN, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val lineRange = getLineRange(editor)
    val lastLine = lineRange.endLine.coerceAtMost(editor.lineCount() - 1)
    val start = editor.getLineStartOffset(lineRange.startLine)
    val end = editor.getLineEndOffset(lastLine)
    editor.createFoldRegion(start, end, collapse = true)
    return ExecutionResult.Success
  }
}

/** see "h :foldopen" - `zo`, and `:foldopen!` is `zO`. */
@ExCommand(command = "foldo[pen]")
data class FoldOpenCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_FORBIDDEN, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val action = if (modifier == CommandModifier.BANG) {
      injector.actionExecutor.ACTION_EXPAND_REGION_RECURSIVELY
    } else {
      injector.actionExecutor.ACTION_EXPAND_REGION
    }
    return foldAt(editor, context, getLineRange(editor).startLine, action)
  }
}

/** see "h :foldclose" - `zc`, and `:foldclose!` is `zC`. */
@ExCommand(command = "foldc[lose]")
data class FoldCloseCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_FORBIDDEN, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val action = if (modifier == CommandModifier.BANG) {
      injector.actionExecutor.ACTION_COLLAPSE_REGION_RECURSIVELY
    } else {
      injector.actionExecutor.ACTION_COLLAPSE_REGION
    }
    return foldAt(editor, context, getLineRange(editor).startLine, action)
  }
}
