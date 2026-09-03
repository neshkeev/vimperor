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
import com.maddyhome.idea.vim.directory.WorkingDirectory
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * `:saveas[!] {file}` - write this buffer somewhere else and carry on editing *there*.
 *
 * The difference from `:write {file}`, which is easy to miss and is the whole reason both exist:
 * `:w other.txt` writes a copy and leaves you editing the file you were editing, while `:saveas
 * other.txt` writes it and the buffer becomes the new file. "Save a copy" against "save as".
 *
 * Vim does that by renaming the buffer. Neither host lets an extension rename what an editor is
 * showing, so this writes the file and opens it, which leaves the reader in the same place Vim
 * would leave them - looking at the new file, with the old one still around. The one visible
 * difference is that the old editor stays open rather than being renamed out of existence, and
 * that is the honest half of the trade: nothing is lost, one more tab exists.
 *
 * `E13` without the bang when the file is already there, which is Vim's answer and the reason the
 * bang exists.
 *
 * see "h :saveas"
 */
@ExCommand(command = "sav[eas]")
data class SaveAsCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_REQUIRED, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val name = commandArgument.trim()
    if (name.isEmpty()) throw exExceptionMessage("E471")

    val path = WorkingDirectory.resolve(injector.pathExpansion.expandPath(name), editor)
    if (modifier != CommandModifier.BANG && injector.fileSystem.exists(path)) {
      throw exExceptionMessage("E13", path)
    }

    injector.fileSystem.writeText(path, editor.text().toString())?.let { failure ->
      throw exExceptionMessage("E212.reason", path, failure)
    }

    val opening = injector.file.openFile(path, context)
    if (opening != null) {
      injector.messages.showErrorMessage(editor, opening)
      return ExecutionResult.Error
    }
    return ExecutionResult.Success
  }
}

/**
 * `:oldfiles` - the files this session has been in.
 *
 * Vim reads this from its viminfo file, which is how the list survives a restart. There is no
 * viminfo here and nothing that plays its part: neither host offers an extension a list of files
 * the user edited last week, and inventing a file to keep one in would be a bigger decision than
 * this command is worth.
 *
 * So the answer is the jump list's files, newest first. That is not a substitute chosen for
 * convenience - Vim's own index describes `:oldfiles` as listing "files that have marks in the
 * viminfo file", and a jump *is* a mark; the difference is that this list starts empty at each
 * launch instead of being read from disk. The command says so rather than pretending, because a
 * user who expects last week's files and gets today's should be able to tell which they are
 * looking at.
 *
 * see "h :oldfiles"
 */
@ExCommand(command = "ol[dfiles]")
data class OldFilesCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val files = injector.jumpService.getJumps(editor.projectId)
      .asReversed()
      .map { it.filepath }
      .filter { it.isNotEmpty() }
      .distinct()

    val text = if (files.isEmpty()) {
      NOTHING_YET
    } else {
      files.mapIndexed { position, path -> "${(position + 1).toString().padStart(3)}: $path" }
        .joinToString("\n")
    }
    injector.outputPanel.output(editor, context, text + "\n")
    return ExecutionResult.Success
  }

  private companion object {
    const val NOTHING_YET =
      "No files yet. This list is the jump list's, so it fills as you move between files, and " +
        "starts empty at each launch - there is no viminfo file here for it to be read from."
  }
}
