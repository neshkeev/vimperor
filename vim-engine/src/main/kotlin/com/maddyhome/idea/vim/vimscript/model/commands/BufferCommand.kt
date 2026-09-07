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
 * `:buffer`, `:buf`, `:bu`, `:b` - go to a buffer, by number, by name, or `#` for the alternate.
 *
 * Three of the four things this does were already host questions the engine asks through an
 * interface: [com.maddyhome.idea.vim.api.VimFile.selectFile], `selectPreviousTab` and `openFile`.
 * The fourth was the search by name, which walked IntelliJ's `FileEditorManager` directly - and
 * that is [com.maddyhome.idea.vim.api.VimFile.getBuffers] now, the same list `:ls` prints.
 *
 * @author John Weigel
 */
@ExCommand(command = "b[uffer]")
data class BufferCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier) {

  override val argFlags = flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val overrideModified = modifier == CommandModifier.BANG
    val buffer = argument.trim()
    if (buffer.isEmpty()) return ExecutionResult.Success

    if (NUMBER.matches(buffer)) {
      // Vim numbers buffers from one and the host indexes its own list from zero.
      val number = buffer.toInt() - 1
      if (injector.file.selectFile(number, context)) return ExecutionResult.Success
      injector.messages.showErrorMessage(editor, injector.messages.message("E86", number))
      return ExecutionResult.Error
    }

    if (buffer == "#") {
      // Not an error either way: a window with one buffer in it has no alternate, and Vim beeps
      // rather than printing anything.
      if (!injector.file.selectPreviousTab(context)) injector.messages.indicateError()
      return ExecutionResult.Success
    }

    val matches = injector.file.getBuffers(context).filter { it.name.contains(buffer) }
    if (matches.isEmpty()) {
      injector.messages.showErrorMessage(editor, injector.messages.message("E94", buffer))
      return ExecutionResult.Error
    }
    if (matches.size > 1) {
      injector.messages.showErrorMessage(editor, injector.messages.message("E93", buffer))
      return ExecutionResult.Error
    }

    // Vim refuses to leave a modified buffer behind unless `'hidden'` is set or the command was
    // banged. This asks the editor the command was typed in, which is the one being left.
    if (editor.hasUnsavedChanges() && !overrideModified) {
      injector.messages.showErrorMessage(editor, injector.messages.message("E37"))
      return ExecutionResult.Error
    }

    val errorMessage = injector.file.openFile(matches[0].name, context)
    if (errorMessage != null) {
      injector.messages.showErrorMessage(editor, errorMessage)
      return ExecutionResult.Error
    }
    return ExecutionResult.Success
  }

  private companion object {
    val NUMBER = Regex("^\\d+$")
  }
}
