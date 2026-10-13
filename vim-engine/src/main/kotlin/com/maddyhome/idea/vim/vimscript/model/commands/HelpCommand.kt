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
import com.maddyhome.idea.vim.api.VirtualBufferKind
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.help.HelpText
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * `:help [subject]` - the list of commands Vimperor implements, opened in a read-only buffer.
 *
 * It used to open Vim's own documentation in a browser, on the reasoning that "there is no help file to open in an
 * IDE". That stopped being true when the commands learnt to describe themselves: [HelpText] renders a help file in
 * Vim's own format from the descriptions in `@CommandOrMotion` and `@ExCommand`, and a `*tag*` in it is what `:help
 * dd` jumps to. Ported from IdeaVim, which made the same trade.
 *
 * What is gained is the question a user of *this* extension actually asks - "does Vimperor have this?" - answered
 * from the source rather than from a website, offline and instantly, and with no way for the answer to drift from the
 * code. What is lost is Vim's prose, which is far richer than one line per command. `:help` is the command Vim gives
 * for its own documentation, so this is a real trade and not a free win.
 *
 * An unknown subject is `E149`, as in Vim, rather than a search of the website.
 */
@ExCommand(
  command = "h[elp]",
  barSeparates = false,
  description = "Open Vimperor's help and jump to the subject, e.g. dd, v_d, i_CTRL-W or :s. Without a subject, show the start of the help.",
)
data class HelpCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags = flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val subject = argument.trim()
    val line = if (subject.isEmpty()) 0 else HelpText.findTag(subject) ?: throw exExceptionMessage("E149", subject)
    injector.virtualBufferGroup.open(context, editor, VirtualBufferKind.Help, HelpText.text, caretLine = line)
    return ExecutionResult.Success
  }
}
