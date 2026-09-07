/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vimscript.model.commands
import com.github.neshkeev.vimperor.diff.Diff
import com.github.neshkeev.vimperor.directory.WorkingDirectory
import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.commands.Command
import com.maddyhome.idea.vim.vimscript.model.commands.CommandModifier

/**
 * Diff mode, as far as a host with a diff *view* rather than a diff *window mode* can take it.
 *
 * The distinction is the whole story and is written out at [Diff]. What follows from it is which
 * commands are here and which report `E319` next door in [UnavailableCommands]: opening a view over
 * two files is something both hosts do, and reaching into that view hunk by hunk is something
 * neither lets an extension do at all. So `:diffthis`, `:diffsplit` and `:diffoff` are real, and
 * `:diffget` and `:diffput` are not available - which is the same absence `:pclose` reports and is
 * named in the README as one of the three things this fork has not built.
 *
 * `:diffupdate` is accepted and does nothing, which is not a shrug: Vim needs it because its diff
 * is computed once and can go stale, and a host's diff view recomputes as the files change. The
 * command's whole purpose is already served.
 */
sealed class DiffCommandBase(
  range: Range,
  modifier: CommandModifier,
  argument: String,
) : Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  /** This file's path, or `E32` - Vim's "no file name" - because a diff needs two of them. */
  protected fun pathOf(editor: VimEditor): String =
    editor.getPath() ?: throw exExceptionMessage("E32")

  /**
   * A path the user typed, made absolute the way `:e` makes one absolute.
   *
   * Done here rather than in each host, because both resolve a relative path against the same
   * thing - the project or workspace root - and the hosts should be handed two paths they can open
   * rather than two they have to interpret.
   */
  protected fun resolve(path: String, editor: VimEditor, context: ExecutionContext): String {
    val expanded = injector.pathExpansion.expandPath(path.trim())
    if (WorkingDirectory.isAbsolute(expanded)) return expanded
    val root = WorkingDirectory.current(editor, context) ?: return expanded
    return "${root.trimEnd('/', '\\')}/$expanded"
  }

  protected fun show(editor: VimEditor, context: ExecutionContext, left: String, right: String): ExecutionResult {
    if (!injector.window.showDiff(context, left, right)) {
      injector.messages.showErrorMessage(editor, "This host could not open a diff of $left and $right.")
      return ExecutionResult.Error
    }
    return ExecutionResult.Success
  }
}

/**
 * `:diffthis` - one side of a diff.
 *
 * The first one marks this file and says so; the second opens the view over the pair. Vim's turns
 * diff mode on for the window and compares every window that has it on, which reaches the same two
 * files by a road this host has no way to walk. See [Diff].
 *
 * see "h :diffthis"
 */
@ExCommand(command = "difft[his]")
data class DiffThisCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  DiffCommandBase(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val path = pathOf(editor)
    val other = Diff.mark(editor.projectId, path)
      ?: run {
        injector.messages.showStatusBarMessage(
          editor,
          "Marked for diffing. Run :diffthis in the other file, or :diffsplit {file} here.",
        )
        return ExecutionResult.Success
      }

    return show(editor, context, other, path)
  }
}

/**
 * `:diffsplit {file}` - this file against that one, now.
 *
 * Vim splits a window, opens the file in it and turns diff mode on in both. The split is the part
 * that does not survive: a host's diff view *is* the split, and it arranges its two panes itself.
 *
 * see "h :diffsplit"
 */
@ExCommand(command = "diffs[plit]")
data class DiffSplitCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  DiffCommandBase(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val other = commandArgument.trim()
    if (other.isEmpty()) throw exExceptionMessage("E471")
    return show(editor, context, pathOf(editor), resolve(other, editor, context))
  }
}

/**
 * `:diffoff` - forget the file that was waiting for a pair.
 *
 * Vim turns diff mode off for the window, or for all of them with `!`. A view that is already open
 * is the host's from the moment it opens and closes like any other editor, so what is left for this
 * to undo is the half-finished `:diffthis` - which is a real thing to be able to undo, because
 * otherwise a stray `:diffthis` pairs with whatever file you visit next.
 *
 * see "h :diffoff"
 */
@ExCommand(command = "diffo[ff]")
data class DiffOffCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  DiffCommandBase(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    Diff.clear(editor.projectId)
    return ExecutionResult.Success
  }
}

/**
 * `:diffupdate` - accepted, and there is nothing for it to do.
 *
 * Vim computes its diff once and recomputes on this command, because an edit can leave the marks
 * stale. A host's diff view watches both files and recomputes on its own, so the state this
 * command exists to restore is the state it is always in. Accepted rather than refused for that
 * reason: a script that calls it after editing is asking for something that has already happened.
 *
 * see "h :diffupdate"
 */
@ExCommand(command = "diffu[pdate]")
data class DiffUpdateCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  DiffCommandBase(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = ExecutionResult.Success
}

/**
 * `:diffpatch {patchfile}` - this file with a patch applied, beside the original.
 *
 * Vim shells out to `patch` and opens the result in a new window in diff mode, and both halves of
 * that are things this host can do: `:!` already runs a process, and the result is written to a
 * file next to the original rather than into a buffer with no name, which is the one part that
 * differs. Vim writes its copy to a temporary file too - `.new` beside the original is where it
 * puts one when `'patchmode'` is empty.
 *
 * see "h :diffpatch"
 */
@ExCommand(command = "diffp[atch]")
data class DiffPatchCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  DiffCommandBase(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val patch = commandArgument.trim()
    if (patch.isEmpty()) throw exExceptionMessage("E471")

    val original = pathOf(editor)
    val patched = "$original.new"

    val output = try {
      injector.processGroup.executeCommand(
        editor,
        "patch -o ${quote(patched)} ${quote(original)} < ${quote(resolve(patch, editor, context))}",
        null,
        null,
        injector.globalOptions(),
      )
    } catch (e: Exception) {
      injector.messages.showErrorMessage(editor, e.message)
      return ExecutionResult.Error
    }

    if (!injector.fileSystem.exists(patched)) {
      injector.messages.showErrorMessage(
        editor,
        "patch produced nothing to compare: ${output?.trim().orEmpty().ifEmpty { "no output" }}",
      )
      return ExecutionResult.Error
    }

    return show(editor, context, original, patched)
  }

  /** A path with a space in it, handed to a shell. */
  private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
}
