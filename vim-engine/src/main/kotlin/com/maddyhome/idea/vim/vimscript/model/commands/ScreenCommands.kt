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
import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * see "h :redraw"
 *
 * Vim draws the screen when it is finished with a command; `:redraw` is how a script that is not
 * finished makes what it has done so far appear. Both hosts repaint on their own account and the
 * engine already has a service for asking - the status line uses it - so this is that service, and
 * the bang, which in Vim clears the screen first, has nothing extra to do.
 */
@ExCommand(command = "redr[aw]")
data class RedrawCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_FORBIDDEN, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    injector.redrawService.redraw()
    return ExecutionResult.Success
  }
}

/** see "h :redrawstatus" - the status line alone, which is the half a script usually wants. */
@ExCommand(command = "redraws[tatus]")
data class RedrawStatusCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_FORBIDDEN, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    injector.redrawService.redrawStatusLine()
    return ExecutionResult.Success
  }
}

/**
 * see "h :sleep"
 *
 * Vim stops for the given time, and a script uses it to let the user see something before moving
 * on. Nothing here stops: the engine runs on the thread that draws the editor in one host and on
 * the only thread there is in the other, so a real sleep would freeze the window rather than pause
 * a script. The command is registered and returns at once, and the argument is accepted so that
 * `:sleep 100m` in a mapping does not take the mapping down with it.
 */
@ExCommand(command = "sl[eep]")
data class SleepCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = ExecutionResult.Success
}

/**
 * see "h :checktime"
 *
 * Asks Vim to notice that a file has changed on disk. Both hosts watch the filesystem themselves
 * and reload or warn without being asked, which is the whole of what this command is for - so it is
 * registered, does nothing, and the doing-nothing is correct rather than a gap.
 */
@ExCommand(command = "checkt[ime]")
data class CheckTimeCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = ExecutionResult.Success
}

/**
 * see "h :startreplace"
 *
 * `:startinsert`'s twin: it leaves the user typing in Replace mode rather than Insert.
 * `:startreplace!` starts at the end of the line, as `:startinsert!` does.
 */
@ExCommand(command = "startr[eplace]")
data class StartReplaceCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_FORBIDDEN, Access.SELF_SYNCHRONIZED)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    if (editor.mode is Mode.INSERT || editor.mode is Mode.REPLACE) return ExecutionResult.Success
    if (modifier == CommandModifier.BANG) {
      editor.currentCaret().moveToOffset(injector.motion.moveCaretToCurrentLineEnd(editor, editor.currentCaret()))
    }
    injector.changeGroup.initInsert(editor, context, Mode.REPLACE)
    return ExecutionResult.Success
  }
}

/**
 * see "h :startgreplace"
 *
 * Vim's virtual Replace mode, which types over the *columns* a tab occupies rather than over the
 * tab. The engine has Replace and not that, so this is `:startreplace` - which differs only in a
 * buffer that uses tabs, and differs the way `gR` would if it were implemented.
 */
@ExCommand(command = "startg[replace]")
data class StartVirtualReplaceCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_FORBIDDEN, Access.SELF_SYNCHRONIZED)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    if (editor.mode is Mode.INSERT || editor.mode is Mode.REPLACE) return ExecutionResult.Success
    injector.changeGroup.initInsert(editor, context, Mode.REPLACE)
    return ExecutionResult.Success
  }
}
