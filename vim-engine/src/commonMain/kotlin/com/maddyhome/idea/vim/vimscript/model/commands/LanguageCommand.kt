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
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * `:language` - accepted, and it says so when asked to actually change something.
 *
 * This reported `E319` for a day, on the argument that a locale is an absent subject: Vim's
 * `:language` sets the one its own messages, its `strftime` and its character classes come from,
 * and neither host will let an extension change the process locale.
 *
 * That was the wrong call, and this fork had already made the right one for the commands next to
 * it. `:syntax`, `:filetype` and `:colorscheme` all name something *the editor decides for itself*,
 * and all three accept quietly and speak up only when the argument asks for something the editor
 * will not do. A `~/.vimrc` sourced from an `.ideavimrc` is full of such lines, and every one of
 * them turning red is a worse answer than silence - the reader cannot act on any of it.
 *
 * `:language` is that shape exactly. So it accepts, and it explains once, when it is given
 * something to do rather than merely mentioned.
 *
 * see "h :language"
 */
@ExCommand(command = "lan[guage]")
data class LanguageCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val wanted = commandArgument.trim()
      // `messages`, `ctype` and `time` name which of Vim's three locales to set; the name after
      // them is the one being asked for, and it is the part that decides whether this is a
      // question or an instruction.
      .removePrefix("messages").removePrefix("ctype").removePrefix("time")
      .trim()

    if (wanted.isEmpty()) {
      // Vim prints the current locale. There is one and it is the editor's, so this says that
      // rather than inventing a name for it.
      injector.messages.showMessage(
        editor,
        "The language is the IDE's own and is not something a buffer can change.",
      )
      return ExecutionResult.Success
    }

    injector.messages.showMessage(
      editor,
      "The IDE decides its own language, so `:language $wanted` did nothing. " +
        "IntelliJ's language pack and VS Code's display language are settings it reads at startup.",
    )
    return ExecutionResult.Success
  }
}
