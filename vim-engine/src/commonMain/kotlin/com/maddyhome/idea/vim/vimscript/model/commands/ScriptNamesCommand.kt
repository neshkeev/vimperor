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
import com.maddyhome.idea.vim.script.SourcedScripts
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * `:scriptnames` - which files this session actually loaded, in order.
 *
 * The question it answers is the one a config that half works produces: a `.ideavimrc` that sources
 * three files and quietly fails to find one of them looks exactly like a `.ideavimrc` that never
 * mentioned it. Both hosts report a failed `:source`, but only to the output channel and only at
 * the moment it happened - which is usually before anyone was looking.
 *
 * The number is Vim's script id, and it is assigned once: a file sourced twice keeps the number it
 * had, because the number identifies the script rather than the loading of it.
 *
 * see "h :scriptnames"
 */
@ExCommand(command = "scr[iptnames]")
data class ScriptNamesCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_FORBIDDEN, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val scripts = SourcedScripts.all()
    val text = buildString {
      scripts.forEachIndexed { position, path ->
        append((position + 1).toString().padStart(3))
        append(": ")
        appendLine(path)
      }
    }
    injector.outputPanel.output(editor, context, text)
    return ExecutionResult.Success
  }
}
