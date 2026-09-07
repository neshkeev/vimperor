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
import com.maddyhome.idea.vim.api.getText
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.options
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * see "h :print"
 */
@ExCommand(command = "p[rint],P[rint]")
data class PrintCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    editor.removeSecondaryCarets()
    val caret = editor.currentCaret()
    val lineRange = getLineRangeWithCount(editor, caret)

    // Move the caret to the start of the last line of the range
    val offset = injector.motion.moveCaretToLineStartSkipLeading(editor, lineRange.endLine)
    caret.moveToOffset(offset)

    injector.outputPanel.output(editor, context, getText(editor, (lineRange.startLine..lineRange.endLine).toList()))
    return ExecutionResult.Success
  }

  companion object {
    /**
     * @param lines 0-based list of line numbers
     * @param forceNumbers number the lines whatever `'number'` says, which is what `:number` is
     * @param listMode show the end of each line and its unprintable characters, which is `:list`
     */
    fun getText(
      editor: VimEditor,
      lines: List<Int>,
      forceNumbers: Boolean = false,
      listMode: Boolean = false,
    ): String {
      val showNumbers = forceNumbers || injector.options(editor).number
      val biggestNumberLength = lines.max().toString().length
      return lines.joinToString("\n") {
        val number = if (showNumbers) (it + 1).toString().padStart(biggestNumberLength, ' ') + " " else ""
        val text = getLineText(editor, it)
        "$number${if (listMode) asList(text) else text}"
      }
    }

    /**
     * A line as `:list` shows it: `$` for where it ends, `^I` for a tab, `^X` for a control character.
     *
     * Vim's `'listchars'` decides the first two and defaults to exactly this. The option is
     * accepted rather than implemented here, so this is the default rendering and nothing reads it.
     */
    private fun asList(text: String): String = buildString {
      for (c in text) {
        when {
          c == '\t' -> append("^I")
          c.code < 32 -> append('^').append((c.code + 64).toChar())
          c.code == 127 -> append("^?")
          else -> append(c)
        }
      }
      append('$')
    }

    private fun getLineText(editor: VimEditor, line: Int): String {
      val startOffset = editor.getLineStartOffset(line)
      val endOffset = editor.getLineEndOffset(line)
      return editor.getText(startOffset, endOffset)
    }
  }
}
