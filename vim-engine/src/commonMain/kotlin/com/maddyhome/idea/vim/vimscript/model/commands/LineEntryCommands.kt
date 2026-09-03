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
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.put.PutData
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.parser.LineEntryBlocks

/**
 * `:append`, `:insert` and `:change` - the three ex commands whose argument is the next few lines.
 *
 * ```
 * :3append
 * a new line
 * and another
 * .
 * ```
 *
 * They are the oldest thing in the ex command set and the only place a command's text arrives after
 * the command rather than on it. The grammar cannot say that - every rule in it ends a command at a
 * newline - so the lines are folded into the argument before parsing, by [LineEntryBlocks], and
 * taken apart again here. That is the whole of the trick, and it is why these three share a file:
 * once the text has arrived, the difference between them is one line each.
 *
 * | command   | the range names           | the text goes    |
 * |-----------|---------------------------|------------------|
 * | `:append` | the line to append after  | after it         |
 * | `:insert` | the line to insert before | before it        |
 * | `:change` | the lines to replace      | in place of them |
 *
 * The bang is Vim's `'autoindent'` toggle, and it is accepted and does nothing here for a reason
 * worth stating rather than hiding: Vim's autoindent continues the indent of the line you are
 * *typing* under, and there is no typing. The text comes from a script and carries its own
 * indentation already, so adding more would be wrong more often than right.
 *
 * see "h :append", "h :insert", "h :change"
 */
internal sealed class LineEntryCommand(
  /** Kept because `Command` holds its own copy privately and these three ask what the range *is*. */
  protected val addressRange: Range,
  modifier: CommandModifier,
  argument: String,
) : Command.SingleExecution(addressRange, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.WRITABLE)

  /**
   * The lines the block held.
   *
   * The argument always begins with a separator, because the header's own newline became one - so
   * the first element of the split is empty and is dropped. `:append` with a `.` on the very next
   * line is therefore an empty list rather than a list holding one empty line.
   */
  private val lines: List<String>
    get() = commandArgument.split(LineEntryBlocks.SEPARATOR).drop(1)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    if (editor.isOneLineMode()) return ExecutionResult.Error
    // These three take no argument of their own - the text is the block, and the block always
    // arrives with a separator in front of it. Anything else after the command name is Vim's
    // `E488`, and saying so matters more here than elsewhere: `:imap a b |c " note` puts a bare
    // `c` after the bar, and a `:change` that quietly took that for its argument would delete a
    // line of the user's file instead of complaining.
    if (commandArgument.isNotEmpty() && commandArgument.first() != LineEntryBlocks.SEPARATOR) {
      throw exExceptionMessage("E488", commandArgument)
    }
    return if (perform(editor, context, lines)) ExecutionResult.Success else ExecutionResult.Error
  }

  protected abstract fun perform(editor: VimEditor, context: ExecutionContext, text: List<String>): Boolean

  /**
   * Puts [text] in as whole lines, at [line] and either before it or after it.
   *
   * Through the put machinery rather than straight into the document, because that is where this
   * fork already handles the two cases that are easy to get wrong: a file whose last line has no
   * newline, and line zero meaning "before the first line" rather than a line that does not exist.
   * `:read` inserts the same way for the same reasons. No register is touched - the text is this
   * command's own and putting it through one would clobber whatever the user had there.
   */
  protected fun put(editor: VimEditor, context: ExecutionContext, text: List<String>, line: Int, before: Boolean):
    Boolean {
    if (text.isEmpty()) return true
    val content = text.joinToString(separator = "\n", postfix = "\n")
    val data = PutData(
      PutData.TextData(null, injector.clipboardManager.dumbCopiedText(content), SelectionType.LINE_WISE),
      null,
      1,
      insertTextBeforeCaret = false,
      rawIndent = false,
      caretAfterInsertedText = false,
      putToLine = line,
      putBeforeLine = before,
    )
    return injector.put.putText(editor, context, data)
  }

  /** The line the range names, or -1 for "wherever the caret is", which is what put reads it as. */
  protected fun addressedLine(editor: VimEditor): Int = if (addressRange.size() == 0) -1 else getLine(editor)

  /** True for a range of exactly `0`, which Vim means as "before the first line". */
  protected fun isLineZero(editor: VimEditor): Boolean =
    addressRange.addresses.isNotEmpty() &&
      addressRange.addresses.last().getLine1(editor, editor.currentCaret()) == 0
}

/**
 * `:append` - the text goes *after* the range's last line.
 *
 * `:0append` puts it at the very top, which is Vim's rule for address zero everywhere it takes a
 * range: "before the first line" rather than a line that does not exist.
 */
@ExCommand(command = "a[ppend]")
internal data class AppendLinesCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  LineEntryCommand(range, modifier, argument) {

  override fun perform(editor: VimEditor, context: ExecutionContext, text: List<String>): Boolean =
    put(editor, context, text, addressedLine(editor), before = isLineZero(editor))
}

/** `:insert` - the text goes *before* the range's line, which is the only difference from `:append`. */
@ExCommand(command = "i[nsert]")
internal data class InsertLinesCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  LineEntryCommand(range, modifier, argument) {

  override fun perform(editor: VimEditor, context: ExecutionContext, text: List<String>): Boolean =
    put(editor, context, text, addressedLine(editor), before = true)
}

/**
 * `:change` - the range's lines go and the text takes their place.
 *
 * The deletion happens first and the text goes where those lines were, which is what makes
 * `:%change` with an empty block a way of emptying a file. That is the one case where a block with
 * no lines still has work to do, and it is why this one does not return early on an empty block.
 */
@ExCommand(command = "c[hange]")
internal data class ChangeLinesCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  LineEntryCommand(range, modifier, argument) {

  override fun perform(editor: VimEditor, context: ExecutionContext, text: List<String>): Boolean {
    val caretLine = editor.currentCaret().getBufferPosition().line
    val bounds = if (addressRange.size() == 0) {
      caretLine..caretLine
    } else {
      getLineRange(editor).let { it.startLine..it.endLine }
    }
    val first = bounds.first.coerceIn(0, (editor.lineCount() - 1).coerceAtLeast(0))
    val last = bounds.last.coerceIn(first, (editor.lineCount() - 1).coerceAtLeast(0))

    val start = editor.getLineStartOffset(first)
    val end = (editor.getLineEndOffset(last) + 1).coerceAtMost(editor.fileSize().toInt())
    editor.deleteString(TextRange(start, end))

    if (text.isEmpty()) return true
    // After the deletion the file may be shorter than the line that was named, and the text then
    // belongs at the end rather than at a line that no longer exists.
    return if (first >= editor.lineCount()) {
      put(editor, context, text, (editor.lineCount() - 1).coerceAtLeast(0), before = false)
    } else {
      put(editor, context, text, first, before = true)
    }
  }
}
