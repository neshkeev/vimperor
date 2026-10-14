/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.visualmulti

import com.github.neshkeev.vimperor.extension.readLine
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.multiplecursors.leaveVisualMode
import com.maddyhome.idea.vim.state.mode.inVisualMode

/**
 * Asks for a command, or repeats the last one, and runs it with `:normal` - which uses the mappings,
 * as vim-visual-multi does. The command runs at each cursor, and a cursor is at the end of a region.
 *
 * [readLine] rather than IdeaVim's blocking `inputString`, for the reason recorded on `readKeys`:
 * nothing here can block. Not `readInputAndProcess` either - a command line opened from inside a
 * mapping's handler is gone before the next key arrives, which is measured on [readLine].
 */
internal class RunNormalHandler(private val repeatLast: Boolean) : ExtensionHandler {

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    if (repeatLast) {
      run(editor, context, lastCommand ?: return)
    } else {
      readLine(editor, context, ":normal ") { command ->
        run(editor, context, command)
      }
    }
  }

  private fun run(editor: VimEditor, context: ExecutionContext, command: String) {
    if (command.isEmpty()) return
    lastCommand = command

    if (editor.inVisualMode) {
      leaveVisualMode(editor)
      // And drop what each caret had selected, which IdeaVim gets for nothing: IntelliJ clears a
      // caret's selection when the editor leaves Visual mode, where here the selection is the
      // caret's own value and outlives the mode. `:normal x` then ran against the regions and
      // deleted them - `foo bar foo` came back as ` bar ` instead of `oo bar oo`.
      editor.nativeCarets().forEach { it.removeSelection() }
    }
    injector.vimscriptExecutor.execute("normal $command", editor, context, skipHistory = true)
  }

  private companion object {
    var lastCommand: String? = null
  }
}
