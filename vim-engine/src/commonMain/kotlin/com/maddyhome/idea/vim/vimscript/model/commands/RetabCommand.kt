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
 * see "h :retab"
 *
 * Rewrites the whitespace in a range for a new tab width. `:retab 4` says "this file was written
 * with eight-column tabs, lay it out for four", and `:retab` alone says "lay it out again for the
 * tabstop I already have" - which is how a file of tabs becomes a file of spaces once `'expandtab'`
 * is on, and the reverse once it is off.
 *
 * Vim's rule for *which* whitespace is rewritten is the interesting half. A run of spaces and tabs
 * is only touched if it contains a tab, so `:retab` does not silently reflow a file that someone
 * had aligned with spaces on purpose. `:retab!` drops that condition and rewrites every run, which
 * is the form you use when you actually mean to convert the file.
 *
 * The column arithmetic is what makes this more than a `replace`: a tab is not a fixed number of
 * spaces but a jump to the next multiple of the tabstop, so a run has to be measured from where it
 * starts on the line. Two runs of the same text on the same line can be different widths.
 */
@ExCommand(command = "ret[ab]")
data class RetabCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.WRITABLE)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val tabstopOption = injector.optionGroup.getOption("tabstop")
    val scope = OptionAccessScope.EFFECTIVE(editor)
    val current = tabstopOption
      ?.let { (injector.optionGroup.getOptionValue(it, scope) as? VimInt)?.value }
      ?.takeIf { it > 0 }
      ?: DEFAULT_TABSTOP

    val requested = argument.trim().takeIf { it.isNotEmpty() }?.let {
      it.toIntOrNull()?.takeIf { size -> size > 0 } ?: throw exExceptionMessage("E487", it)
    }
    val tabstop = requested ?: current

    val expandTab = injector.optionGroup.getOption("expandtab")
      ?.let { (injector.optionGroup.getOptionValue(it, scope) as? VimInt)?.value }
      ?.let { it != 0 }
      ?: false

    val everyRun = modifier == CommandModifier.BANG
    val lineRange = getLineRange(editor)
    val caret = editor.currentCaret()

    // Last line first: rewriting one changes the offsets of the lines after it.
    for (line in (lineRange.startLine..lineRange.endLine).reversed()) {
      if (line >= editor.lineCount()) continue
      val start = editor.getLineStartOffset(line)
      val end = editor.getLineEndOffset(line)
      val text = editor.getText(start, end)
      val rewritten = retabLine(text, readAt = current, writeAt = tabstop, expandTab, everyRun)
      if (rewritten != text) injector.changeGroup.replaceText(editor, caret, start, end, rewritten)
    }

    // Vim sets 'tabstop' to the new value, which is the point of passing one: the file is now laid
    // out for it, and leaving the option behind would show the result at the old width.
    if (requested != null && tabstopOption != null) {
      injector.optionGroup.setOptionValue(tabstopOption, scope, VimInt(requested))
    }
    return ExecutionResult.Success
  }

  private companion object {
    const val DEFAULT_TABSTOP = 8

    /**
     * @param readAt the tabstop the line is laid out for now, which is what its tabs measure at
     * @param writeAt the tabstop to lay it out for, which is what the new whitespace measures at
     */
    fun retabLine(text: String, readAt: Int, writeAt: Int, expandTab: Boolean, everyRun: Boolean): String {
      val out = StringBuilder()
      var column = 0
      var index = 0
      while (index < text.length) {
        val c = text[index]
        if (c != ' ' && c != '\t') {
          out.append(c)
          column++
          index++
          continue
        }

        // Measure the whole run first: its width depends on where it starts, and whether it is
        // rewritten at all depends on what is in it.
        val runStart = index
        var width = column
        var hasTab = false
        while (index < text.length && (text[index] == ' ' || text[index] == '\t')) {
          if (text[index] == '\t') {
            hasTab = true
            width += readAt - (width % readAt)
          } else {
            width++
          }
          index++
        }

        if (!hasTab && !everyRun) {
          out.append(text, runStart, index)
        } else {
          out.append(whitespaceFrom(column, width, writeAt, expandTab))
        }
        column = width
      }
      return out.toString()
    }

    /** The whitespace that gets from column [from] to column [to], in tabs or spaces as asked. */
    fun whitespaceFrom(from: Int, to: Int, tabstop: Int, expandTab: Boolean): String {
      if (expandTab) return " ".repeat(to - from)
      val out = StringBuilder()
      var column = from
      while (true) {
        val nextStop = column + tabstop - (column % tabstop)
        if (nextStop > to) break
        out.append('\t')
        column = nextStop
      }
      out.append(" ".repeat(to - column))
      return out.toString()
    }
  }
}
