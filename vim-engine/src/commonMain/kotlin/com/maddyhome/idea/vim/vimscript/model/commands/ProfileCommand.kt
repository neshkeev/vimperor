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
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.profile.Profile
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * `:profile` - how long the Vimscript functions in this session took.
 *
 * Vim profiles functions and scripts. This profiles functions, which is where the time in a
 * `.ideavimrc` actually goes: a script's own lines run once at startup and show up in the load
 * time, and a function called from a mapping runs on every keystroke that uses it. The second is
 * the one nobody can time by watching, and it is the one this measures.
 *
 * Vim's subcommands, in Vim's spelling: `start {file}` begins and says where a `dump` goes,
 * `func {pattern}` says what to watch, `pause` and `continue` do what they say, and `dump` writes
 * the report. `dump` with no file named prints to the output panel instead of writing, which is
 * the sensible reading of "dump" in an editor where the panel is right there.
 *
 * Wall-clock milliseconds rather than Vim's microseconds. The engine's clock is
 * `currentTimeMillis` on both targets, and reporting a microsecond figure derived from a
 * millisecond clock would be three digits of invented precision.
 *
 * see "h :profile"
 */
@ExCommand(command = "prof[ile]")
data class ProfileCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_REQUIRED, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val words = commandArgument.trim().split(" ", "\t").filter { it.isNotEmpty() }
    val subcommand = words.firstOrNull() ?: throw exExceptionMessage("E471")
    val rest = words.drop(1).joinToString(" ")

    when (subcommand) {
      "start" -> {
        if (rest.isEmpty()) throw exExceptionMessage("E471")
        Profile.start(rest)
      }

      "stop" -> {
        Profile.pause()
        writeOrPrint(editor, context, Profile.destination)
      }

      "pause" -> Profile.pause()
      "continue" -> Profile.resume()

      // `func` and `file` both take a pattern. Only functions are timed, and `file` is accepted
      // rather than refused because a config that asks for both should not stop at the second.
      "func", "file" -> {
        if (rest.isEmpty()) throw exExceptionMessage("E471")
        Profile.watch(rest)
      }

      "dump" -> writeOrPrint(editor, context, rest.ifEmpty { null })

      else -> throw exExceptionMessage("E475", subcommand)
    }
    return ExecutionResult.Success
  }

  /**
   * The report, to a file when one is named and to the panel when one is not.
   *
   * Vim only writes, because Vim's `:profile` was made for a `vim -c` run that exits. Here the
   * panel is on screen and the reader is in the session that was profiled, so a `:profile dump`
   * with nowhere to write puts the report where they are looking.
   */
  private fun writeOrPrint(editor: VimEditor, context: ExecutionContext, file: String?) {
    val report = Profile.dump()
    if (file == null) {
      injector.outputPanel.output(editor, context, report)
      return
    }
    val failure = injector.fileSystem.writeText(file, report)
    if (failure != null) throw exExceptionMessage("E212")
  }
}
