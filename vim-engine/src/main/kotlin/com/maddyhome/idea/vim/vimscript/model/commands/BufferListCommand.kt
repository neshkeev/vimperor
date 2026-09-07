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
import com.maddyhome.idea.vim.api.VimBuffer
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * `:ls`, `:files`, `:buffers` - the buffer list, with the `+ = a % #` filters.
 *
 * This was written down as impossible in the VS Code host, on the grounds that "VS Code has tabs
 * rather than buffers, and its tab model does not carry the modified state Vim prints in that
 * table". The first half is true and does not matter; the second half is simply wrong. `Tab` has
 * had `isDirty` since VS Code 1.68, and `isActive` alongside it - which is `+` and `%`, the two
 * flags a user actually reads this table for. The habit this keeps proving is that a "the host
 * cannot do this" note is usually a note about which API was looked at.
 *
 * What was genuinely IntelliJ about it was the walk over `FileEditorManager` and the reach into a
 * `Document` to ask whether it had been edited. Both are questions a host answers, so they are
 * asked through [com.maddyhome.idea.vim.api.VimFile.getBuffers] now and the formatting - which is
 * the whole of what Vim specifies here - is the engine's.
 *
 * @author John Weigel
 */
@ExCommand(command = "ls,files,buffers")
data class BufferListCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier) {

  override val argFlags = flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    // Vim rejects a filter it does not know; IdeaVim has always dropped the unknown characters and
    // shown the rest, so `:buffers x` lists everything rather than complaining.
    val filter = argument.trim().filter { it in SUPPORTED_FILTERS }
    val buffers = injector.file.getBuffers(context)
    // Right-aligned in the width of the largest number, so the names line up in a long list.
    val numberWidth = buffers.size.toString().length

    val rows = buffers.mapIndexedNotNull { index, buffer ->
      val status = statusOf(buffer)
      if (!filter.all { it in status }) return@mapIndexedNotNull null
      val name = "\"" + buffer.displayPath + "\""
      val padding = if (name.length < FILE_NAME_PAD) " ".repeat(FILE_NAME_PAD - name.length) else ""
      "   " + (index + 1).toString().padStart(numberWidth) +
        " " + status + " " + name + padding + " line: " + buffer.line
    }

    injector.outputPanel.output(editor, context, rows.joinToString(separator = "\n"))
    return ExecutionResult.Success
  }

  /**
   * The four flag columns, in the order and the places Vim puts them.
   *
   * `%a` for the current buffer and `#` for the alternate go in the first two; the third is the
   * read-only marker and the fourth is the modified one. A buffer that is neither current nor
   * alternate gets no `h` here, which is not what Vim does - it marks every loaded-but-hidden
   * buffer - and is what IdeaVim has always printed.
   */
  private fun statusOf(buffer: VimBuffer): String {
    val status = StringBuilder(
      when {
        buffer.isCurrent -> "%a  "
        buffer.isAlternate -> "#   "
        else -> "    "
      },
    )
    if (buffer.isReadOnly) status[2] = '='
    if (buffer.isModified) status[3] = '+'
    return status.toString()
  }

  companion object {
    /** The column the line number starts at, counted from the opening quote of the name. */
    const val FILE_NAME_PAD: Int = 30
    val SUPPORTED_FILTERS: Set<Char> = setOf('+', '=', 'a', '%', '#')
  }
}
