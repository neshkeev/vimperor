/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vimscript.model.commands
import com.github.neshkeev.vimperor.match.Matches
import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.commands.Command
import com.maddyhome.idea.vim.vimscript.model.commands.CommandModifier

/**
 * `:match {group} /{pattern}/`, and its two numbered twins.
 *
 * A standing highlight: unlike a search, the pattern stays lit as you edit, and unlike `'hlsearch'`
 * it does not move when you press `n`. Vim gives three channels so that a plugin can light
 * something up without taking the one the user is using; all three are here for the same reason.
 *
 * `:match none` puts it out, and so does `:match` with nothing after it - Vim accepts both.
 *
 * The group is Vim's highlight-group name, and it is resolved rather than passed on: `:highlight`
 * defines what a group looks like, and a group it has defined arrives at the host as colours. A
 * group nobody defined arrives as a name, and the host finds its own nearest colour for `Search`
 * or `ErrorMsg` and falls back to its find-match colour for a name it has not heard of - see
 * [com.github.neshkeev.vimperor.api.VimMatchHighlighter]. So naming a group nobody knows lights the text
 * up in the find colour rather than failing, which is the useful failure: the reader can see that
 * the pattern worked and only the colour was wrong.
 */
@ExCommand(command = "mat[ch]")
data class MatchCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  /**
   * The range is where the channel comes from, which is not a trick - it is the only place it can.
   *
   * Vim spells the other two `:2match` and `:3match`, one token each. This parser reads a leading
   * digit as a range before it looks the command name up, so `:2match` arrives here as "the command
   * `match`, with the address 2" - and the number means the same thing either way. Registering
   * `2mat[ch]` as its own command would put a name in the table that nothing can ever reach.
   *
   * A range that is not a single number between 1 and 3 is `E481`, which is what Vim answers for a
   * range on `:match`.
   */
  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val channel = channelFrom(editor)
    val argument = commandArgument.trim()
    if (argument.isEmpty() || argument == "none" || argument == "NONE") {
      Matches.clear(editor, channel)
      return ExecutionResult.Success
    }

    val group = argument.substringBefore(' ').trim()
    val rest = argument.substringAfter(' ', "").trim()
    if (rest.isEmpty()) throw exExceptionMessage("E475", argument)

    // The delimiter is whatever the pattern starts with, which is Vim's rule everywhere it takes
    // one - `:match Todo #foo#` is the form you reach for when the pattern has a slash in it.
    val delimiter = rest.first()
    if (delimiter.isLetterOrDigit() || delimiter == '\\') throw exExceptionMessage("E475", rest)

    val closing = rest.lastIndexOf(delimiter)
    if (closing <= 0) throw exExceptionMessage("E475", rest)

    val pattern = rest.substring(1, closing)
    if (pattern.isEmpty()) throw exExceptionMessage("E475", rest)

    Matches.set(editor, channel, group, pattern)
    return ExecutionResult.Success
  }

  private fun channelFrom(editor: VimEditor): Int {
    if (range.size() == 0) return 1
    if (range.size() > 1) throw exExceptionMessage("E481")
    val channel = range.addresses.single().getLine1(editor, editor.currentCaret())
    if (channel !in 1..3) throw exExceptionMessage("E481")
    return channel
  }
}
