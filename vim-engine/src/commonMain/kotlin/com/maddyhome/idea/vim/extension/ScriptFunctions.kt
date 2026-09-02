/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.helper.noneOfEnum
import com.maddyhome.idea.vim.key.OperatorFunction
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.vimscript.model.Executable
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.expressions.Expression
import com.maddyhome.idea.vim.vimscript.model.expressions.Scope
import com.maddyhome.idea.vim.vimscript.model.statements.FunctionDeclaration
import com.maddyhome.idea.vim.vimscript.model.statements.FunctionFlag

/** A Vimscript function whose body is Kotlin rather than Vimscript. */
fun interface ScriptFunction {
  fun execute(editor: VimEditor, context: ExecutionContext, args: Map<String, VimDataType>): ExecutionResult
}

/**
 * Declaring a Vimscript function from code, which is how a plugin makes itself callable.
 *
 * This lived in `VimExtensionFacade`, in IdeaVim's IntelliJ module, and there is nothing IntelliJ
 * about it: a function declaration with a Kotlin body, stored in the engine's own function service.
 * It is here because [com.maddyhome.idea.vim.thinapi.VimPluginService] needs it and that interface
 * is the engine's - a host that could not reach this could not offer `exportOperatorFunction`, and
 * `g@` with a plugin's own function is most of what an operator plugin *is*.
 */
object ScriptFunctions {

  fun export(
    scope: Scope?,
    name: String,
    args: List<String>,
    defaultArgs: List<Pair<String, Expression>>,
    hasOptionalArguments: Boolean,
    flags: MutableSet<FunctionFlag>,
    function: ScriptFunction,
  ) {
    var functionDeclaration: FunctionDeclaration? = null
    val body = listOf(
      object : Executable {
        // Set to the declaration while it is being built and to the execution context when it runs.
        override lateinit var vimContext: VimLContext
        override var rangeInScript: TextRange = TextRange(0, 0)

        override fun execute(editor: VimEditor, context: ExecutionContext): ExecutionResult =
          function.execute(editor, context, functionDeclaration!!.functionVariables)
      },
    )
    functionDeclaration = FunctionDeclaration(
      scope,
      name,
      args,
      defaultArgs,
      body,
      replaceExisting = true,
      flags,
      hasOptionalArguments,
    )
    functionDeclaration.rangeInScript = TextRange(0, 0)
    body.forEach { it.vimContext = functionDeclaration }
    injector.functionService.storeFunction(functionDeclaration)
  }

  /**
   * `'operatorfunc'` from a plugin: a Vimscript function of one argument that `g@` will call.
   *
   * Vim passes `"line"`, `"char"` or `"block"` and the function reads the marks itself. Anything
   * else is an error rather than a guess - Vim has three motion kinds and there is no fourth to
   * fall back to.
   */
  fun exportOperatorFunction(name: String, function: OperatorFunction) {
    export(null, name, listOf("type"), emptyList(), false, noneOfEnum()) { editor, context, args ->
      val selectionType = when (args["type"]?.toVimString()?.value) {
        "line" -> SelectionType.LINE_WISE
        "block" -> SelectionType.BLOCK_WISE
        "char" -> SelectionType.CHARACTER_WISE
        else -> return@export ExecutionResult.Error
      }
      if (function.apply(editor, context, selectionType)) ExecutionResult.Success else ExecutionResult.Error
    }
  }
}
