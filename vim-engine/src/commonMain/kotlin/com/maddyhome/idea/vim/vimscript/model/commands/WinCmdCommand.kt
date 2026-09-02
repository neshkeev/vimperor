/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.key.KeySource
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * see "h :wincmd"
 *
 * Vim's own definition is that `:wincmd {arg}` does what `CTRL-W {arg}` does, and that is how it is
 * implemented: the argument is appended to `<C-W>` and the result handed to the key handler. So it
 * answers for exactly the `<C-W>` commands the host has - `h`, `j`, `k`, `l`, `w`, `W`, `s`, `v`,
 * `c`, `o` and the arrow keys today - and it will answer for any that are added later without being
 * touched. Writing out a table of arguments here would have been a second list to keep in step with
 * the first, and would have gone out of date silently.
 *
 * The count is the ex range, which is how Vim spells it too: `:2wincmd l` is `2<C-W>l`.
 */
@ExCommand(command = "winc[md]")
data class WinCmdCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_IS_COUNT, ArgumentFlag.ARGUMENT_REQUIRED, Access.SELF_SYNCHRONIZED)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val arg = argument.trim()
    if (arg.isEmpty()) throw exExceptionMessage("E471")

    val count = getCountFromRange(editor, editor.currentCaret())
    val prefix = if (count > 1) count.toString() else ""
    val keys = injector.parser.parseKeys("$prefix<C-W>$arg")

    val keyHandler = KeyHandler.getInstance()
    keyHandler.reset(editor)
    for (key in keys) {
      keyHandler.handleKey(editor, key, KeySource.NORMAL_COMMAND, context, keyHandler.keyHandlerState)
    }
    return ExecutionResult.Success
  }
}
