/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.cleverf

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.state.mode.Mode

/**
 * Handles `<Plug>(clever-f-repeat-forward)` and `<Plug>(clever-f-repeat-back)`.
 *
 * A plain [ExtensionHandler] rather than an [ExtensionHandler.WithCallback], because a repeat already knows its
 * character and so finishes inside the keystroke - which is what clever-f does for every handler and what this host
 * can only do when nothing has to be read. The motion it adds then suppresses `ToHandlerMappingInfo`'s caret-delta
 * route on its own, by filling the argument the operator was waiting on.
 */
internal class RepeatHandler(
  private val back: Boolean,
  private val session: CleverFSession,
  private val executor: SearchExecutor,
) : ExtensionHandler {
  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    val state = session.stateFor(editor.mode) ?: return
    val underOperator = editor.mode is Mode.OP_PENDING
    executor.execute(editor, state.repeatSearch(back), operatorArguments.count1, underOperator)
  }
}
