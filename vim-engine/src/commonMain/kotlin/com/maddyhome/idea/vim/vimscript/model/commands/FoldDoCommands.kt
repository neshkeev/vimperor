/*
 * Copyright 2026 Nikita Eshkeev
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
 * `:folddoopen` and `:folddoclosed` - `:global`, with a fold instead of a pattern.
 *
 * ```
 * :folddoopen   {cmd}    run {cmd} on every line that is *not* inside a closed fold
 * :folddoclosed {cmd}    run {cmd} on every line that is
 * ```
 *
 * The pair exists because a fold hides lines from the reader and not from a command: `:%s/x/y/g`
 * changes text inside a collapsed fold as happily as anywhere else, and these two are how you say
 * you meant only what you can see, or only what you cannot.
 *
 * Run over range markers rather than over line numbers, which is what `:global` does and for the
 * same reason: the command being run can insert and delete lines, and a list of numbers collected
 * before it started would be pointing somewhere else by the third one.
 *
 * A host that folds nothing is not a problem here - every line is then "not in a closed fold", so
 * `:folddoopen` runs everywhere and `:folddoclosed` runs nowhere, which is exactly what Vim does in
 * a buffer with no folds in it.
 *
 * see "h :folddoopen", "h :folddoclosed"
 */
internal sealed class FoldDoCommand(
  /** Kept because `Command` holds its own copy privately and this asks whether one was given. */
  private val addressRange: Range,
  modifier: CommandModifier,
  argument: String,
  /** True for `:folddoclosed` - the lines that *are* hidden. */
  private val wantsClosed: Boolean,
) : Command.SingleExecution(addressRange, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_REQUIRED, Access.SELF_SYNCHRONIZED)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val command = commandArgument.trim()
    if (command.isEmpty()) return ExecutionResult.Success

    // Vim's default range for both of these is the whole file, which is not the usual default of
    // "the current line" - the commands are about a sweep, and a sweep over one line is nothing.
    val lines = if (addressRange.size() == 0) {
      0 until editor.lineCount()
    } else {
      val bounds = getLineRange(editor)
      bounds.startLine..bounds.endLine.coerceAtMost(editor.lineCount() - 1)
    }

    val wanted = lines.filter { isInsideClosedFold(editor, it) == wantsClosed }
    val marks = wanted.map {
      val offset = editor.getLineStartOffset(it)
      injector.engineEditorHelper.createRangeMarker(editor, offset, offset)
    }

    for (mark in marks) {
      val offset = mark.startOffset
      val isValid = mark.isValid
      mark.dispose()
      // A line the command has already deleted is skipped rather than run on, which is the same
      // check `:global` makes and for the same reason.
      if (!isValid) continue
      editor.currentCaret().moveToOffset(offset)
      injector.vimscriptExecutor.execute(command, editor, context, true, true, vimContextOrNull)
    }
    return ExecutionResult.Success
  }

  private fun isInsideClosedFold(editor: VimEditor, line: Int): Boolean {
    if (line !in 0 until editor.lineCount()) return false
    return editor.getCollapsedFoldRegionAtOffset(editor.getLineStartOffset(line)) != null
  }
}

/** see "h :folddoopen" */
@ExCommand(command = "foldd[oopen]")
internal data class FoldDoOpenCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  FoldDoCommand(range, modifier, argument, wantsClosed = false)

/** see "h :folddoclosed" */
@ExCommand(command = "folddoc[losed]")
internal data class FoldDoClosedCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  FoldDoCommand(range, modifier, argument, wantsClosed = true)
