/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vimscript.model.functions.handlers.variousFunctions
import com.intellij.vim.annotations.VimscriptFunction
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.github.neshkeev.vimperor.redirect.Redirection
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimBlob
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDictionary
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimFloat
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimFuncref
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimList
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.datatypes.asVimInt
import com.maddyhome.idea.vim.vimscript.model.functions.BuiltinFunctionHandler

/**
 * `type({expr})` - which of Vimscript's types this value is, as a number.
 *
 * The numbers are Vim's and they are not in any order that means anything - they are the order the
 * types were added, over twenty years. A config compares against `v:t_string` rather than against
 * `1`, and the numbers here are what makes those comparisons come out right.
 *
 * see "h type()"
 */
@VimscriptFunction(name = "type")
internal class TypeFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = typeNumberOf(arguments[0]).asVimInt()
}

/**
 * `typename({expr})` - the same question, answered in words.
 *
 * Vim spells a container's type with its contents in it - `list<string>` - which is what makes this
 * useful over `type()` for a message. A mixed list is `list<any>`, and an empty one is
 * `list<unknown>`, because there is nothing in it to look at.
 *
 * see "h typename()"
 */
@VimscriptFunction(name = "typename")
internal class TypeNameFunctionHandler : BuiltinFunctionHandler<VimString>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString = VimString(typeNameOf(arguments[0]))
}

private fun typeNumberOf(value: VimDataType): Int = when (value) {
  is VimInt -> 0
  is VimString -> 1
  is VimFuncref -> 2
  is VimList -> 3
  is VimDictionary -> 4
  is VimFloat -> 5
  is VimBlob -> 10
  else -> 1
}

private fun typeNameOf(value: VimDataType): String = when (value) {
  is VimInt -> "number"
  is VimString -> "string"
  is VimFuncref -> "func"
  is VimFloat -> "float"
  is VimBlob -> "blob"
  is VimList -> "list<" + elementTypeOf(value.values.map { typeNameOf(it) }) + ">"
  is VimDictionary -> "dict<" + elementTypeOf(value.dictionary.values.map { typeNameOf(it) }) + ">"
  else -> "string"
}

private fun elementTypeOf(names: List<String>): String = when {
  names.isEmpty() -> "unknown"
  names.distinct().size == 1 -> names.first()
  else -> "any"
}

/**
 * `eval({string})` - the inverse of `string()`.
 *
 * The pair is what makes a Vimscript value survive a round trip through a register, a file or a
 * variable that can only hold text: `string()` writes it out and this reads it back. Which is also
 * the whole of the danger, and Vim's documentation says so - it evaluates whatever it is given.
 *
 * `E15` for text that is not an expression, which is Vim's error for exactly that.
 *
 * see "h eval()"
 */
@VimscriptFunction(name = "eval")
internal class EvalFunctionHandler : BuiltinFunctionHandler<VimDataType>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimDataType {
    val text = arguments.getString(0).value
    val parsed = injector.vimscriptParser.parseExpression(text) ?: throw exExceptionMessage("E15", text)
    return parsed.evaluate(editor, context, vimContext)
  }
}

/**
 * `execute({command} [, {silent}])` - run an ex command and get back what it printed.
 *
 * The function form of `:redir`, and it is built on it: a redirection is opened onto a buffer, the
 * command runs, and the redirection is closed. Vim documents the two as alternatives to each other
 * and this makes them the same mechanism, which is why the awkward part - catching output that
 * `:silent` is hiding - is already solved.
 *
 * Silent by default, which is Vim's choice and the right one: the whole point is to *have* the
 * output rather than to see it, and a `:map` printed to the screen on the way past would be noise.
 * Pass `''` to let it through.
 *
 * A list of commands is accepted and run in order, as Vim's is, so
 * `execute(['set ff?', 'set fenc?'])` returns both answers.
 *
 * Any redirection already running is put back afterwards, because a config may well be inside one -
 * `:redir => x | echo execute('map') | redir END` is not a strange thing to write.
 *
 * see "h execute()"
 */
@VimscriptFunction(name = "execute")
internal class ExecuteFunctionHandler : BuiltinFunctionHandler<VimString>(minArity = 1, maxArity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString {
    val commands = when (val first = arguments[0]) {
      is VimList -> first.values.map { it.toVimString().value }
      else -> listOf(first.toVimString().value)
    }
    val silence = arguments.getStringOrNull(1)?.value ?: "silent"

    val captured = StringBuilder()
    val sink = Redirection.Sink("execute()", "") { _, _, content ->
      captured.clear()
      captured.append(content)
    }

    val outer = Redirection.take()
    Redirection.start(editor, context, sink)
    try {
      for (command in commands) {
        val line = if (silence.isEmpty()) command else "$silence $command"
        injector.vimscriptExecutor.execute(line, editor, context, true, true, vimContext)
      }
    } finally {
      Redirection.end(editor, context)
      Redirection.restore(outer)
    }
    return VimString(captured.toString())
  }
}
