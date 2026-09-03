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
import com.maddyhome.idea.vim.api.MessageType
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.message.MessageHistory
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ExException
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * `:echomsg`, `:echoerr`, `:echon` and `:eval` - the rest of the family `:echo` belongs to.
 *
 * `:echo` has a rule of its own in the grammar, which is what gives it a list of parsed expressions
 * rather than a string. These four do not, so they get the string - and rather than parse it a
 * second way, they hand it back to the parser as an `:echo` and take the expressions off that. The
 * spacing, the string literals, the concatenation and the function calls are then all handled by
 * the one piece of grammar that already handles them, and cannot drift from it.
 */
private fun Command.expressionsOf(argument: String): List<com.maddyhome.idea.vim.vimscript.model.expressions.Expression> {
  val parsed = injector.vimscriptParser.parseCommand("echo ${argument.trim()}")
  return (parsed as? EchoCommand)?.args ?: throw exExceptionMessage("E15", argument.trim())
}

private fun Command.render(argument: String, editor: VimEditor, context: ExecutionContext): String =
  expressionsOf(argument).joinToString(separator = " ") { it.evaluate(editor, context, this).toOutputString() }

/**
 * see "h :echomsg"
 *
 * Vim's difference from `:echo` is that the text is kept in the message history, which `:messages`
 * prints - and that is the difference here too, now that there is a history to keep it in. The
 * recording is explicit rather than inherited: `:echo` and `:echomsg` both print through the output
 * panel, so a history built from the panel would remember both and Vim remembers only this one.
 */
@ExCommand(command = "echom[sg]")
data class EchoMessageCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val text = render(argument, editor, context)
    MessageHistory.record(text, MessageType.STANDARD)
    injector.outputPanel.output(editor, context, text + "\n")
    return ExecutionResult.Success
  }
}

/**
 * see "h :echoerr"
 *
 * An error rather than a message, and in a script an error that aborts it - which is why this
 * throws rather than printing in red and carrying on. `:try` catches it, as it does in Vim, and
 * `:silent!` swallows it, which is what makes `silent! echoerr` a way of testing something.
 */
@ExCommand(command = "echoe[rr]")
data class EchoErrorCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = throw ExException(render(argument, editor, context))
}

/**
 * see "h :echon"
 *
 * `:echo` without the line break after it, so that two of them run together. They do not quite,
 * here: the output panel takes a block of text at a time and starts each one on its own line, so
 * what `:echon` actually buys is the missing break at the *end*. Registered because a config that
 * uses it should print something rather than report `E492`.
 */
@ExCommand(command = "echon")
data class EchoNoNewlineCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    injector.outputPanel.output(editor, context, render(argument, editor, context))
    return ExecutionResult.Success
  }
}

/**
 * see "h :eval"
 *
 * Evaluates an expression and throws the answer away, which is how a script calls something for
 * what it does rather than for what it returns. `:call` only takes a function call; `:eval` takes
 * anything, which is what a method chain ending in `->add()` needs.
 */
@ExCommand(command = "ev[al]")
data class EvalCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_REQUIRED, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    expressionsOf(argument).forEach { it.evaluate(editor, context, this) }
    return ExecutionResult.Success
  }
}

/**
 * see "h :echohl"
 *
 * Names the highlight group the next `:echo` is drawn in. Neither host draws Vim's message area -
 * IntelliJ has an output panel and VS Code an output channel, and neither takes a colour per line -
 * so there is nothing to set. Registered and silent, because a script that colours a warning should
 * still print the warning.
 */
@ExCommand(command = "echoh[l]")
data class EchoHighlightCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = ExecutionResult.Success
}

/**
 * see "h :undojoin"
 *
 * "Join further changes with the previous undo block" - so that a mapping making two edits is undone
 * by one `u`. Both hosts own the undo history and neither exposes a way to merge two entries of it
 * from outside, so this is registered and does nothing: the mapping runs and takes two `u` to undo
 * rather than one, where refusing the line would have left it not running at all.
 */
@ExCommand(command = "undoj[oin]")
data class UndoJoinCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_FORBIDDEN, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = ExecutionResult.Success
}
