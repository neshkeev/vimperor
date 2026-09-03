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
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.message.MessageHistory
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * `:messages` - what was said, for anyone who was not looking at the time.
 *
 * A status-line message lasts until the next keystroke replaces it, and a `.ideavimrc` says
 * everything it has to say before there is an editor to say it into. So the common way to meet a
 * problem in this plugin has always been to see that something did not work and have no way to find
 * out what it said about it. That is what this answers, and it is why the history is recorded even
 * under `:silent` - `:silent` is about the screen.
 *
 * `:messages clear` empties it, which Vim added for the same reason anybody clears a log.
 *
 * see "h :messages"
 */
@ExCommand(command = "mes[sages]")
data class MessagesCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val argument = commandArgument.trim()
    if (argument.equals("clear", ignoreCase = true)) {
      MessageHistory.clear()
      return ExecutionResult.Success
    }
    if (argument.isNotEmpty()) throw exExceptionMessage("E474.arg", argument)

    val history = MessageHistory.all()
    // Vim prints nothing for an empty history. A blank panel reads like a broken command, and this
    // one is reached by someone who is already unsure what happened, so it says so instead.
    val text = if (history.isEmpty()) NOTHING_SAID else history.joinToString("\n") { it.text }
    injector.outputPanel.output(editor, context, text + "\n")
    return ExecutionResult.Success
  }

  private companion object {
    const val NOTHING_SAID = "No messages."
  }
}
