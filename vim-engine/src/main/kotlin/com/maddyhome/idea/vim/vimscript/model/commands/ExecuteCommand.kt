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
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.expressions.Expression

/**
 * see "h :execute"
 */
@ExCommand(command = "exe[cute]")
data class ExecuteCommand(val range: Range, val expressions: List<Expression>) :
  Command.SingleExecution(range, CommandModifier.NONE) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.SELF_SYNCHRONIZED)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val command = expressions.joinToString(separator = " ") { it.evaluate(editor, context, this).toVimString().value }
    return injector.vimscriptExecutor.execute(
      asSingleCommandLine(command),
      editor,
      context,
      skipHistory = true,
      indicateErrors = true,
      this.vimContext
    )
  }

  /**
   * `:execute "normal! ..."` takes the rest of the command line, newlines included.
   *
   * The executor splits its input into lines, so a `\n` inside the argument ended the `:normal` early and ran the
   * remainder as another ex command. Vim does not: `:normal` consumes to the end of the line, and a `<NL>` typed
   * into it is a keystroke. Translating to `\r` keeps the whole thing on one line, since that is the character
   * `<CR>` produces and the one the key parser reads back.
   *
   * Only when the first command is a `:normal`, because every other command genuinely does end at a newline.
   */
  private fun asSingleCommandLine(command: String): String {
    if (!command.contains('\n')) return command
    val firstCommand = injector.vimscriptParser.parseCommand(command.substringBefore('\n'))
    if (firstCommand !is NormalCommand) return command
    return command.replace("\r\n", "\r").replace('\n', '\r')
  }
}
