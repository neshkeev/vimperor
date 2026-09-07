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
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * Vim's `s`-prefixed commands: split the window, then do the thing.
 *
 * `:sbuffer` is `:split` and then `:buffer`, `:snext` is `:split` and then `:next`, and so on down
 * the family. Vim spells them as separate commands because it has to; here they are written as what
 * they mean, which keeps each one honest about the command it is really running - `:sbuffer 3`
 * reports `E86` from the same place `:buffer 3` does, and gains whatever `:buffer` gains later.
 *
 * The split honours `:vertical`, as Vim's do, because [SplitCommand] is what makes it.
 */
sealed class SplitAndDoCommand(
  range: Range,
  modifier: CommandModifier,
  argument: String,
  private val then: String,
) : Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val vertical = injector.window.verticalModifier == true
    if (vertical) {
      injector.window.splitWindowVertical(context, "")
    } else {
      injector.window.splitWindowHorizontal(context, "")
    }
    // Cleared for the nested command: `:vertical sbuffer` means the *split* is vertical, and
    // leaving it raised would hand the modifier on to whatever `:buffer` does next.
    val previous = injector.window.verticalModifier
    injector.window.verticalModifier = null
    try {
      return injector.vimscriptExecutor.execute(
        "$then ${commandArgument.trim()}".trim(),
        editor,
        context,
        skipHistory = true,
        indicateErrors = true,
        vimContextOrNull,
      )
    } finally {
      injector.window.verticalModifier = previous
    }
  }
}

/** see "h :sbuffer" */
@ExCommand(command = "sb[uffer]")
data class SplitBufferCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  SplitAndDoCommand(range, modifier, argument, "buffer")

/** see "h :snext" */
@ExCommand(command = "sn[ext]")
data class SplitNextCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  SplitAndDoCommand(range, modifier, argument, "next")

/** see "h :sprevious" */
@ExCommand(command = "spr[evious]")
data class SplitPreviousCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  SplitAndDoCommand(range, modifier, argument, "previous")

/** see "h :sfind" */
@ExCommand(command = "sf[ind]")
data class SplitFindCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  SplitAndDoCommand(range, modifier, argument, "find")

/** see "h :sview" - `:view` in a split, which is `:edit` in a split here; see [EditFileCommand]. */
@ExCommand(command = "sv[iew]")
data class SplitViewCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  SplitAndDoCommand(range, modifier, argument, "view")

/** see "h :stag" - `:tag` in a split, which is how a definition is read without leaving the caller. */
@ExCommand(command = "sta[g]")
data class SplitTagCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  SplitAndDoCommand(range, modifier, argument, "tag")
