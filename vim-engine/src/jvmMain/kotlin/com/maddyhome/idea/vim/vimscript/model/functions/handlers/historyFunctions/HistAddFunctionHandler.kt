/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.functions.handlers.historyFunctions

import com.intellij.vim.annotations.VimscriptFunction
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.history.VimHistory
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.functions.BinaryFunctionHandler

@VimscriptFunction("histadd")
internal class HistAddFunctionHandler : BinaryFunctionHandler<VimInt>() {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val history = arguments.getString(0).value
    val item = arguments.getString(1)

    val historyType = VimHistory.Type.getTypeByString(history)
      ?: injector.commandLine.getActiveCommandLine()?.historyType
      ?: return VimInt.ZERO

    injector.historyGroup.addEntry(historyType, item.value)
    return VimInt.ONE
  }
}
