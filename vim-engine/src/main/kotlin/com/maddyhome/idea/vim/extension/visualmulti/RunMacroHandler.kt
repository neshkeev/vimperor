/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.visualmulti

import com.github.neshkeev.vimperor.extension.readCharacter
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.state.mode.inVisualMode

/**
 * Asks for a register and runs its macro at each cursor. The regions become cursors first.
 *
 * Written inside out, like every other reader in this fork: IdeaVim's `inputKeyStroke` blocks until
 * a key arrives, and a runtime with one thread cannot. [readCharacter] is the engine's modal input,
 * so this returns before it knows the register and does its work a keystroke later. `<Esc>` is the
 * cancel, and there is nothing to undo on it.
 */
internal class RunMacroHandler : ExtensionHandler {

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    readCharacter(editor, context) { register ->
      if (editor.inVisualMode) {
        collapseRegionsToCursors(editor)
      }
      injector.vimscriptExecutor.execute("normal! @$register", editor, context, skipHistory = true)
    }
  }
}
