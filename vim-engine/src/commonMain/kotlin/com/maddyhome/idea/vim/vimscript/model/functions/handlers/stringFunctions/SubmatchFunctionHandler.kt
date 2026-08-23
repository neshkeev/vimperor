/*
 * Copyright 2003-2024 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.functions.handlers.stringFunctions

import com.intellij.vim.annotations.VimscriptFunction
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.ex.ExException
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.functions.BuiltinFunctionHandler

@VimscriptFunction(name = "submatch")
internal class SubmatchFunctionHandler : BuiltinFunctionHandler<VimString>(minArity = 1, maxArity = 2) {
  var latestMatch: String = ""

  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString {
    val firstArgValue = arguments.getNumber(0)
    if (firstArgValue.value != 0 || arguments.size > 1) {
      throw ExException("Sorry, only `submatch(0)` is supported :(")
    }
    return VimString(latestMatch)
  }

  companion object {
    /**
     * The registered `submatch()` handler, which the search group publishes every match through.
     *
     * A host must call `injector.functionService.registerHandlers()` before any substitute runs. If
     * it has not, the lookup returns null and the cast that used to be here failed with a
     * NullPointerException from inside a plain `:s` - nowhere near the actual cause, and mentioning
     * neither `submatch` nor registration. This says what went wrong instead.
     */
    fun getInstance(): SubmatchFunctionHandler {
      val handler = injector.functionService.getBuiltInFunction("submatch")
        ?: error(
          "submatch() is not registered, so the substitute has nowhere to publish its match. " +
            "A host must call injector.functionService.registerHandlers() during start-up."
        )
      return handler as SubmatchFunctionHandler
    }
  }
}
