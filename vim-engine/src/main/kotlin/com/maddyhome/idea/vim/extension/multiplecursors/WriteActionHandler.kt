/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.multiplecursors

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.extension.ExtensionHandler

/**
 * A handler whose work is one undoable change, repeated by a count when it takes one.
 *
 * [acceptsCount] is off for `multiple-cursors`, whose mappings have never taken a count, and on for
 * `visual-multi`'s, where `3<C-n>` adds three cursors.
 */
internal abstract class WriteActionHandler : ExtensionHandler {

  /** Whether a count repeats the command. */
  open val acceptsCount: Boolean = false

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    val count = if (acceptsCount) operatorArguments.count1 else 1
    injector.application.runWriteAction {
      for (i in 1..count) {
        if (!executeInWriteAction(editor, context)) break
      }
    }
  }

  /** Whether the command did anything. A repeat stops at the first one that did not. */
  abstract fun executeInWriteAction(editor: VimEditor, context: ExecutionContext): Boolean
}
