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
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.VimLContext

/**
 * `:bufdo`, `:windo`, `:tabdo` and `:argdo` - one command run against everything that is open.
 *
 * Vim has four because it has four lists: buffers, the windows of a tab page, the tab pages, and
 * the argument list. Neither host here keeps those apart. IntelliJ has the files open in a project
 * and VS Code has the editors open in a window, each file appears once, and that one list is what
 * all four of these walk - so the four commands do the same thing, and say so rather than three of
 * them quietly doing nothing.
 *
 * Two differences from Vim worth knowing. Vim moves you to each buffer in turn and leaves you in
 * the last one; nothing here changes focus, so the caret ends where it started - which is what you
 * want from `:bufdo %s/old/new/ge` and is also the only thing a host whose focus changes
 * asynchronously can do without reordering the edits. And Vim's rule that an error stops the walk
 * is kept: a `:bufdo` that fails on the second of ten files does not run on the other eight.
 */
private fun runInEveryEditor(command: String, vimContext: VimLContext?): ExecutionResult {
  val trimmed = command.trim()
  if (trimmed.isEmpty()) throw exExceptionMessage("E471")

  // Copied first: the command can open or close an editor, and walking the live list while it does
  // is how a loop over open files ends up skipping one or visiting it twice.
  val editors = injector.editorGroup.getEditors().toList()
  for (target in editors) {
    val targetContext = injector.executionContextManager.getEditorExecutionContext(target)
    val result = injector.vimscriptExecutor.execute(
      trimmed,
      target,
      targetContext,
      skipHistory = true,
      indicateErrors = true,
      vimContext,
    )
    if (result is ExecutionResult.Error) return ExecutionResult.Error
  }
  return ExecutionResult.Success
}

/** see "h :bufdo" */
@ExCommand(command = "bufd[o]")
data class BufDoCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_REQUIRED, Access.SELF_SYNCHRONIZED)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = runInEveryEditor(argument, vimContextOrNull)
}

/** see "h :windo" */
@ExCommand(command = "windo")
data class WinDoCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_REQUIRED, Access.SELF_SYNCHRONIZED)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = runInEveryEditor(argument, vimContextOrNull)
}

/** see "h :tabdo" */
@ExCommand(command = "tabd[o]")
data class TabDoCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_REQUIRED, Access.SELF_SYNCHRONIZED)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = runInEveryEditor(argument, vimContextOrNull)
}

/** see "h :argdo" */
@ExCommand(command = "argdo")
data class ArgDoCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_REQUIRED, Access.SELF_SYNCHRONIZED)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = runInEveryEditor(argument, vimContextOrNull)
}
