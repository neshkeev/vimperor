/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.action.change

import com.intellij.vim.annotations.CommandOrMotion
import com.intellij.vim.annotations.Mode
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.command.Command
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.group.withVimUndoGroup
import com.maddyhome.idea.vim.handler.VimActionHandler

/**
 * `.` - do the last change again. The body is [repeatLastChange], in `vim-engine`.
 *
 * What is left here is the one thing that was not shared: the split-mode undo marker. Grouping the
 * replay into a single undo step matters because a `c`-style change is a delete and an insert, and
 * the JBC backend records each atomic edit separately - speculative undo disables the platform's
 * own command grouping. `injector.actionExecutor.executeCommand` is IntelliJ's `CommandProcessor`
 * and does not cover that; `withVimUndoGroup` is an RPC to the backend and does.
 */
@CommandOrMotion(keys = ["."], modes = [Mode.NORMAL])
internal class RepeatChangeAction : VimActionHandler.SingleExecution() {
  override val type: Command.Type = Command.Type.OTHER_WRITABLE

  override fun execute(
    editor: VimEditor,
    context: ExecutionContext,
    cmd: Command,
    operatorArguments: OperatorArguments,
  ): Boolean = repeatLastChange(editor, context, cmd, operatorArguments) { replay ->
    withVimUndoGroup(editor, "Vim Dot Repeat") { replay() }
  }
}
