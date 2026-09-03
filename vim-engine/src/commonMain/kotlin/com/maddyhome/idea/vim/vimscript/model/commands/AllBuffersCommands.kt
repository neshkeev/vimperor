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
 * `:ball`, `:unhide` and `:sunhide` - a window for every buffer in the list.
 *
 * Vim's three are one command: `:unhide` is documented as "same as `:ball`", and `:sunhide` is the
 * split form of it, which is the same thing again in a host where every buffer opens in its own
 * tab. The count limits how many windows are opened, and Vim's rule for a count of zero is "as many
 * as there are".
 *
 * `:ball` is the useful one after `:argadd` or a `:badd` loop: the buffers exist and none of them
 * are on screen, and this is what puts them there. A buffer that is already showing is not opened
 * twice - the host is asked to open the file, and both hosts focus a file that is already open
 * rather than duplicating it, which is Vim's behaviour as well.
 *
 * see "h :ball"
 */
sealed class AllBuffersCommandBase(
  range: Range,
  modifier: CommandModifier,
  argument: String,
) : Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    // Vim takes the count *before* the command name - `:2ball` - which the parser leaves in the
    // argument for a command with no range, so both spellings are read here.
    val limit = commandArgument.trim().toIntOrNull()
      ?: operatorArguments.count0.takeIf { it > 0 }
      ?: Int.MAX_VALUE

    val buffers = injector.file.getBuffers(context)
    var opened = 0
    for (buffer in buffers) {
      if (opened >= limit) break
      // `displayPath` is what `:ls` prints and what a reader would type into `:e`, and both hosts
      // resolve it the same way `:e` does. There is no absolute path on a buffer to use instead.
      injector.file.openFile(buffer.displayPath, context, focusEditor = false)
      opened++
    }

    // The buffer that was current stays current, which is Vim's: `:ball` opens windows, it does not
    // move you into one. Opening without focus does most of it, and this puts the cursor back where
    // the last host to open something asynchronously may have taken it.
    editor.getPath()?.let { injector.file.openFile(it, context) }
    return ExecutionResult.Success
  }
}

/** see "h :ball" */
@ExCommand(command = "ba[ll]")
data class AllBuffersCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  AllBuffersCommandBase(range, modifier, argument)

/** see "h :unhide" */
@ExCommand(command = "unh[ide]")
data class UnhideCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  AllBuffersCommandBase(range, modifier, argument)

/** see "h :sunhide" */
@ExCommand(command = "sun[hide]")
data class SplitUnhideCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  AllBuffersCommandBase(range, modifier, argument)

/** see "h :sball" */
@ExCommand(command = "sba[ll]")
data class SplitAllBuffersCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  AllBuffersCommandBase(range, modifier, argument)
