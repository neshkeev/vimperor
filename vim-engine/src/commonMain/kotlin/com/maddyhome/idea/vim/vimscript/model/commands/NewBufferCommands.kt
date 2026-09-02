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
 * see "h :enew"
 *
 * A new, empty, unnamed buffer, in the window that is already focused - Vim's blank page. The bang
 * abandons an unsaved buffer; neither host loses one that way, since both keep the editor it was in
 * open and would ask before closing it, so it is accepted and changes nothing.
 */
@ExCommand(command = "ene[w]")
data class EditNewFileCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_FORBIDDEN, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    injector.window.openNewBuffer(context)
    return ExecutionResult.Success
  }
}

/**
 * see "h :new"
 *
 * `:enew` with a split in front of it, which is all Vim's own description amounts to: "create a new
 * window and start editing an empty file in it". With a name it is `:split file` written the other
 * way round - split first, then open in the window that is now focused - and that is how it is done
 * here, because opening into the focused window *is* opening it in the new one.
 *
 * Two classes rather than one with the name passed in, unlike [SplitCommand]: a command reached
 * through the catch-all parser rule rather than through a token of its own is built from the
 * standard `(Range, CommandModifier, String)` constructor, and `:new` has no token.
 */
@ExCommand(command = "new")
data class NewWindowCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    splitForNewWindow(context, vertical = false)
    return openNewWindowContents(context, argument)
  }
}

/** see "h :vnew" - [NewWindowCommand] the other way up. */
@ExCommand(command = "vne[w]")
data class NewVerticalWindowCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    splitForNewWindow(context, vertical = true)
    return openNewWindowContents(context, argument)
  }
}

/** `:vertical new` is a vertical split, as it is for `:split` - the modifier wins over the name. */
private fun splitForNewWindow(context: ExecutionContext, vertical: Boolean) {
  if (injector.window.verticalModifier ?: vertical) {
    injector.window.splitWindowVertical(context, "")
  } else {
    injector.window.splitWindowHorizontal(context, "")
  }
}

private fun openNewWindowContents(context: ExecutionContext, argument: String): ExecutionResult {
  val filename = argument.trim()
  if (filename.isEmpty()) {
    injector.window.openNewBuffer(context)
  } else {
    injector.file.openFile(injector.pathExpansion.expandPath(filename), context)
  }
  return ExecutionResult.Success
}

/**
 * see "h :tabnew" / "h :tabedit"
 *
 * The same two commands in Vim, and the same one here.
 */
@ExCommand(command = "tabnew,tabe[dit]")
data class NewTabCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val filename = argument.trim()
    injector.window.openNewTab(context, if (filename.isEmpty()) "" else injector.pathExpansion.expandPath(filename))
    return ExecutionResult.Success
  }
}
