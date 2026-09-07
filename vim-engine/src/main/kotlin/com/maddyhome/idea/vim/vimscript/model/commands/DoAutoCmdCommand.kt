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
import com.maddyhome.idea.vim.autocmd.AutoCmdEvent
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * see "h :doautocmd"
 *
 * Fires an event by hand, which is how a config runs the handlers it has just installed against the
 * file that is already open - `autocmd FileType java ...` does nothing for the Java file you were
 * looking at when the config was read, and `:doautocmd FileType` is the line that fixes that.
 *
 * `:doautocmd [group] {event} [fname]`. The group is accepted and ignored: this engine's groups say
 * which handlers `:autocmd! {group}` clears, not which ones an event reaches, and Vim's own
 * `:doautocmd` without a group runs every handler anyway - so ignoring it fires more than Vim would
 * only for the config that asked for a group by name.
 *
 * `E216` for an event nothing here has, which is Vim's error for exactly that.
 *
 * `:doautoall` is deliberately *not* a second name for this - see [DoAutoAllCommand], which fires
 * the same event for every buffer rather than for one.
 */
@ExCommand(command = "do[autocmd]")
data class DoAutoCmdCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_REQUIRED, Access.SELF_SYNCHRONIZED)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val words = argument.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) throw exExceptionMessage("E471")

    // The event is the first word that names one; anything before it is the group. Vim decides the
    // same way - it has no other way to tell a group name from an event name either.
    val eventIndex = words.indexOfFirst { eventNamed(it) != null }
    if (eventIndex < 0) throw exExceptionMessage("E216", words.first())

    val event = eventNamed(words[eventIndex])!!
    val filePath = words.getOrNull(eventIndex + 1) ?: editor.getPath()
    injector.autoCmd.handleEvent(event, filePath, editor)
    return ExecutionResult.Success
  }

  /** Vim's event names are case-insensitive; the enum's are not. */
  private fun eventNamed(name: String): AutoCmdEvent? =
    AutoCmdEvent.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/**
 * `:doautoall [group] {event} [fname]` - the same event, for every buffer that is open.
 *
 * The command a config reaches for after installing handlers, when the answer to "and what about
 * the files that were already open?" has to be "all of them" rather than "this one". Vim's own
 * warning applies and is worth repeating: it fires for every buffer, so an event whose handlers
 * change text will change text in all of them.
 *
 * Vim visits each buffer in turn without showing it; there is no such thing here, so each open
 * editor is passed to the handlers as itself. That is closer to what the handlers expect anyway -
 * they are given a real editor rather than one that is only notionally current.
 *
 * see "h :doautoall"
 */
@ExCommand(command = "doautoa[ll]")
data class DoAutoAllCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_REQUIRED, Access.SELF_SYNCHRONIZED)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val words = argument.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) throw exExceptionMessage("E471")

    val eventIndex = words.indexOfFirst { eventNamed(it) != null }
    if (eventIndex < 0) throw exExceptionMessage("E216", words.first())
    val event = eventNamed(words[eventIndex])!!
    val overridePath = words.getOrNull(eventIndex + 1)

    // A copy of the list, because a handler is allowed to open or close a file and iterating the
    // host's live collection while it does would be a different bug on each host.
    for (target in injector.editorGroup.getEditors().toList()) {
      injector.autoCmd.handleEvent(event, overridePath ?: target.getPath(), target)
    }
    return ExecutionResult.Success
  }

  private fun eventNamed(name: String): AutoCmdEvent? =
    AutoCmdEvent.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
