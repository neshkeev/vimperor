/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vimscript.model.commands
import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.Options
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.commands.Command
import com.maddyhome.idea.vim.vimscript.model.commands.CommandModifier
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString

/**
 * `:behave mswin` and `:behave xterm` - four options set together.
 *
 * Vim defines it as exactly that and nothing more, which is why it is a handful of lines: it is a
 * shorthand for a pair of settings people used to argue about, not a mode. `mswin` makes selecting
 * behave the way a Windows editor does - the selection is Select mode, so typing replaces it, and
 * the shifted keys start one. `xterm` is Vim's own.
 *
 * `'mousemodel'` is the fifth option Vim sets and this does not have one: the mouse belongs to the
 * host, and neither of them will let an extension change what a right-click does. The other four
 * are engine options and are set exactly as Vim sets them.
 *
 * see "h :behave"
 */
@ExCommand(command = "be[have]")
data class BehaveCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_REQUIRED, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val values = when (commandArgument.trim()) {
      "mswin" -> Behaviour("mouse,key", "startsel,stopsel", "exclusive")
      "xterm" -> Behaviour("", "", "inclusive")
      else -> throw exExceptionMessage("E475", commandArgument.trim())
    }

    set(editor, Options.selectmode, values.selectmode)
    set(editor, Options.keymodel, values.keymodel)
    set(editor, Options.selection, values.selection)
    return ExecutionResult.Success
  }

  private fun set(
    editor: VimEditor,
    option: com.maddyhome.idea.vim.options.Option<VimString>,
    value: String,
  ) {
    injector.optionGroup.setOptionValue(option, OptionAccessScope.EFFECTIVE(editor), VimString(value))
  }

  private data class Behaviour(val selectmode: String, val keymodel: String, val selection: String)
}
