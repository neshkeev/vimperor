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
import com.maddyhome.idea.vim.directory.WorkingDirectory
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * Vim's argument list, over the one list this fork has.
 *
 * Vim keeps the files named on the command line apart from the buffers it has loaded, and has a
 * command for each end of that: `:args` against `:ls`, `:argadd` against `:badd`, `:argdelete`
 * against `:bdelete`. Neither host here keeps them apart - IntelliJ has the files open in a project
 * and VS Code the tabs in a window - so both halves name the same list, and both are registered so
 * that a config or a mapping written for either spelling works.
 *
 * That is the same decision `:first`/`:bfirst` and `:argdo`/`:bufdo` already made, and it is worth
 * making once out loud: the alternative is one of each pair working and the other reporting E492,
 * which reads as an oversight rather than as a difference between Vim and an IDE.
 */
@ExCommand(command = "ar[gs]")
data class ArgsCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    // Vim's format exactly: the names on one line, with the current one in brackets.
    val names = injector.file.getBuffers(context)
      .joinToString(" ") { if (it.isCurrent) "[${it.displayPath}]" else it.displayPath }
    injector.outputPanel.output(editor, context, names)
    return ExecutionResult.Success
  }
}

/**
 * see "h :argadd" / "h :badd"
 *
 * Vim adds the file to a list without opening a window on it. The list here *is* the open files, so
 * adding to it is opening the file - without taking the focus, which is the part of Vim's meaning
 * that survives.
 */
@ExCommand(command = "arga[dd],bad[d]")
data class ArgAddCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_REQUIRED, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    for (name in argument.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
      val failure = injector.file.openFile(injector.pathExpansion.expandPath(name), context, focusEditor = false)
      if (failure != null) {
        injector.messages.showErrorMessage(editor, failure)
        return ExecutionResult.Error
      }
    }
    return ExecutionResult.Success
  }
}

/**
 * see "h :argdelete"
 *
 * Removes a file from the list, which here means closing it - see [ArgsCommand] for why the two are
 * the same thing. The name is matched the way `:buffer name` matches, on any part of it.
 */
@ExCommand(command = "argd[elete]")
data class ArgDeleteCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_REQUIRED, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val pattern = argument.trim()
    val buffers = injector.file.getBuffers(context)
    val matches = buffers.withIndex().filter { (_, buffer) -> buffer.name.contains(pattern) }
    if (matches.isEmpty()) {
      injector.messages.showErrorMessage(editor, injector.messages.message("E480"))
      return ExecutionResult.Error
    }
    // Highest index first: closing one renumbers the list below it.
    matches.map { it.index }.sortedDescending().forEach { injector.file.closeFile(it, context) }
    return ExecutionResult.Success
  }
}

/**
 * see "h :bmodified"
 *
 * The next buffer with changes that are not on disk, wrapping round the end of the list as Vim's
 * does. `E84` when there are none, which is Vim's error for running off the buffer list.
 */
@ExCommand(command = "bm[odified]")
data class BufferModifiedCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val buffers = injector.file.getBuffers(context)
    if (buffers.isEmpty()) throw exExceptionMessage("E84")

    val start = buffers.indexOfFirst { it.isCurrent }.let { if (it < 0) 0 else it }
    val next = (1..buffers.size).map { buffers[(start + it) % buffers.size] }.firstOrNull { it.isModified }
      ?: throw exExceptionMessage("E84")

    val failure = injector.file.openFile(next.name, context)
    if (failure != null) {
      injector.messages.showErrorMessage(editor, failure)
      return ExecutionResult.Error
    }
    return ExecutionResult.Success
  }
}

/**
 * see "h :tabs"
 *
 * Vim prints a heading per tab page and the windows inside it, with `>` on the current window and
 * `+` on a modified one. A tab page here holds exactly one file - that is what a tab is in both
 * hosts - so every heading has one line under it, and the shape of the table is the only thing left
 * of Vim's nesting.
 */
@ExCommand(command = "tabs")
data class TabsCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_FORBIDDEN, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val rows = injector.file.getBuffers(context).flatMapIndexed { index, buffer ->
      val marker = if (buffer.isCurrent) ">" else " "
      val modified = if (buffer.isModified) "+" else " "
      listOf("Tab page ${index + 1}", "$marker$modified  ${buffer.displayPath}")
    }
    injector.outputPanel.output(editor, context, rows.joinToString("\n"))
    return ExecutionResult.Success
  }
}

/**
 * see "h :argedit"
 *
 * Vim adds the file to the argument list *and* opens it, which is the difference from `:argadd`.
 * Here the list is the open files, so adding and opening are the same act and this is `:edit` with
 * one extra thing: Vim accepts several names and edits the first, so several are accepted.
 */
@ExCommand(command = "arge[dit]")
data class ArgEditCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_REQUIRED, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val names = argument.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (names.isEmpty()) throw exExceptionMessage("E471")

    // The first one takes the focus and the rest are merely added, which is Vim's rule: "edit file
    // {name} ... the argument list is set to {name}" and the others join the list behind it.
    names.forEachIndexed { position, name ->
      val failure = injector.file.openFile(
        WorkingDirectory.resolve(injector.pathExpansion.expandPath(name), editor),
        context,
        focusEditor = position == 0,
      )
      if (failure != null) {
        injector.messages.showErrorMessage(editor, failure)
        return ExecutionResult.Error
      }
    }
    return ExecutionResult.Success
  }
}

/**
 * `:argglobal` and `:arglocal` - which argument list this window uses, of the one that exists.
 *
 * Vim keeps a global argument list and lets a window take a private copy of it; the two commands
 * are how a window says which it wants. Neither host here has a second list to switch to - the
 * files are open or they are not, and every window sees the same ones - so with no argument these
 * are true by construction and do nothing.
 *
 * With names after them Vim *defines* the list, and that half does mean something: the files
 * become open ones. Which is `:argadd`, so that is what this does rather than inventing a second
 * meaning for it. What it deliberately does not do is close the files that were already open -
 * Vim's version replaces a list, and replacing this one would close the reader's editors.
 *
 * see "h :argglobal", "h :arglocal"
 */
internal sealed class ArgListScopeCommand(
  range: Range,
  modifier: CommandModifier,
  argument: String,
) : Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val names = commandArgument.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (names.isEmpty()) return ExecutionResult.Success

    for (name in names) {
      val failure = injector.file.openFile(
        WorkingDirectory.resolve(injector.pathExpansion.expandPath(name), editor),
        context,
        focusEditor = false,
      )
      if (failure != null) {
        injector.messages.showErrorMessage(editor, failure)
        return ExecutionResult.Error
      }
    }
    return ExecutionResult.Success
  }
}

/** see "h :argglobal" */
@ExCommand(command = "argg[lobal]")
internal data class ArgGlobalCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  ArgListScopeCommand(range, modifier, argument)

/** see "h :arglocal" */
@ExCommand(command = "argl[ocal]")
internal data class ArgLocalCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  ArgListScopeCommand(range, modifier, argument)
