/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.resize

import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.commands.Command
import com.maddyhome.idea.vim.vimscript.model.commands.CommandModifier

/**
 * Partial implementation of `:resize` (`:res`), which sets the *height* of the current window.
 * The actual resizing is delegated to [ResizeService].
 *
 * Supported argument forms:
 * - `:resize {n}`  - set the window height to `n` rows
 * - `:resize +{n}` - increase the height by `n` rows
 * - `:resize -{n}` - decrease the height by `n` rows
 * - `:resize`      - maximise the window height
 *
 * `:vertical resize` sets the width instead, and is the same command: `:vertical` is a modifier
 * in `vim-engine` that raises a flag on the window group and runs the rest of the line, so this
 * reads the flag rather than being reached through a second command of its own. That is Vim's own
 * arrangement, and it means `:vertical split` works here too - the copy of `:vertical` that used to
 * live beside this one accepted `:vertical resize` and reported `E492` for everything else.
 *
 * see "h :resize"
 */
@ExCommand(command = "res[ize]")
data class ResizeCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags = flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.SELF_SYNCHRONIZED)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val resizeArgument = ResizeArgument.parse(argument)
    if (injector.window.verticalModifier == true) {
      injector.windowResize.resizeCurrentWindowWidth(editor, resizeArgument)
    } else {
      injector.windowResize.resizeCurrentWindowHeight(editor, resizeArgument)
    }
    return ExecutionResult.Success
  }
}
