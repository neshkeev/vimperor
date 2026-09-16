/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.action.window

import com.intellij.vim.annotations.CommandOrMotion
import com.intellij.vim.annotations.Mode
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.Command
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.handler.VimActionHandler

/**
 * `<C-W>c`, and `<C-W>q` beside it.
 *
 * Vim spells the second as `:quit` and the first as `:close`, and the two differ only over the last
 * window, which `:close` refuses to close. Both close the current window here, as they do in IdeaVim
 * since VIM-4324 - where `<C-W>q` had been missing, so the `q` fell through and did nothing.
 *
 * @author rasendubi
 */
@CommandOrMotion(keys = ["<C-W>c", "<C-W>q"], modes = [Mode.NORMAL])
class CloseWindowAction : VimActionHandler.SingleExecution() {
  override val type: Command.Type = Command.Type.OTHER_READONLY

  override fun execute(
    editor: VimEditor,
    context: ExecutionContext,
    cmd: Command,
    operatorArguments: OperatorArguments,
  ): Boolean {
    injector.window.closeCurrentWindow(context)
    return true
  }
}
