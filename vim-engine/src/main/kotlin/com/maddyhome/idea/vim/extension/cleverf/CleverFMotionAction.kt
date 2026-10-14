/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.cleverf

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.ImmutableVimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.command.Argument
import com.maddyhome.idea.vim.command.MotionType
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.handler.Motion
import com.maddyhome.idea.vim.handler.MotionActionHandler
import com.maddyhome.idea.vim.handler.toMotionOrError

/**
 * The motion of a [CharSearch] in operator-pending mode, e.g. `dfa`.
 *
 * Nothing here is decided at runtime, which is worth saying because it looks as though it ought to be. [motionType]
 * is a `val` fixed when the search was built, the character the search is for is closed over inside [search], and
 * `argumentType` is left at its `null` default - so the engine never asks this motion for an argument and the
 * command builder never has to work out whether one is needed.
 */
internal class CleverFMotionAction(
  private val search: CharSearch,
  private val session: CleverFSession,
) : MotionActionHandler.ForEachCaret() {
  override val motionType: MotionType = if (search.isInclusive) MotionType.INCLUSIVE else MotionType.EXCLUSIVE

  override fun getOffset(
    editor: VimEditor,
    caret: ImmutableVimCaret,
    context: ExecutionContext,
    argument: Argument?,
    operatorArguments: OperatorArguments,
  ): Motion {
    val target = search.findTarget(editor, caret.offset, operatorArguments.count1) ?: return Motion.Error
    if (caret.isPrimary) session.recordJump(search.state, editor, target)
    return target.toMotionOrError()
  }
}
