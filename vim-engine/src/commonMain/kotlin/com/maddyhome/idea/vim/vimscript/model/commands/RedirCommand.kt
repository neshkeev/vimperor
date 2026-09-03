/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.directory.WorkingDirectory
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.redirect.Redirection
import com.maddyhome.idea.vim.register.RegisterConstants
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.expressions.LValueExpression
import com.maddyhome.idea.vim.vimscript.model.expressions.RegisterExpression
import com.maddyhome.idea.vim.vimscript.model.expressions.Scope
import com.maddyhome.idea.vim.vimscript.model.expressions.VariableExpression

/**
 * `:redir` - sending what a command printed somewhere it can be read back.
 *
 * The idiom this exists for is one line long and very old:
 *
 * ```
 * :redir => output | silent map | redir END
 * ```
 *
 * Every part of that matters. `:map` prints a table and returns nothing, so the only way to get at
 * it is to catch what it printed; `:silent` keeps it off the screen while it is caught, which means
 * the capture has to happen *before* the silence is applied, and it does -
 * [com.maddyhome.idea.vim.api.VimOutputPanelServiceBase.output] redirects first and consults
 * `:silent` second.
 *
 * Four destinations, and two of them are the same thing underneath. A register and a variable are
 * both `LValueExpression` and both already know how to be assigned to, so `:redir @a` and
 * `:redir => var` differ by one line here and by nothing at all further down.
 *
 * ```
 * :redir[!] > {file}        write, refusing to clobber unless ! is given
 * :redir >> {file}          append
 * :redir @{a}  @{a}>        into a register; an uppercase name appends, as everywhere in Vim
 * :redir @{a}>>             append, said the other way
 * :redir => {var}           into a variable
 * :redir =>> {var}          append to one that already exists
 * :redir END                stop
 * ```
 *
 * What is captured is what reaches the output panel: `:echo`, and every command that prints a table
 * - `:map`, `:set all`, `:registers`, `:scriptnames`, `:highlight`. What is not captured is a
 * one-line status message, because those do not pass through any single place in this engine on
 * their way to a host; `VimMessages` says as much about `:silent`, and the same gap is the same gap.
 *
 * see "h :redir"
 */
@ExCommand(command = "redi[r]")
data class RedirCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val argument = commandArgument.trim()

    if (argument.equals("END", ignoreCase = true)) {
      if (!Redirection.end(editor, context)) throw exExceptionMessage("E1185")
      return ExecutionResult.Success
    }

    // `:redir` on its own says where the output is going, which Vim does not answer and a reader
    // in the middle of debugging a script very much wants to know.
    if (argument.isEmpty()) {
      val where = Redirection.describe ?: "not redirecting"
      injector.messages.showStatusBarMessage(editor, where)
      return ExecutionResult.Success
    }

    Redirection.start(editor, context, sinkFor(argument, editor, context))
    return ExecutionResult.Success
  }

  private fun sinkFor(argument: String, editor: VimEditor, context: ExecutionContext): Redirection.Sink = when {
    argument.startsWith("=>>") -> lvalueSink(variableIn(argument.removePrefix("=>>")), append = true, editor, context)
    argument.startsWith("=>") -> lvalueSink(variableIn(argument.removePrefix("=>")), append = false, editor, context)
    argument.startsWith("@") -> registerSink(argument, editor, context)
    argument.startsWith(">>") -> fileSink(argument.removePrefix(">>").trim(), append = true, editor)
    argument.startsWith(">") -> fileSink(argument.removePrefix(">").trim(), append = false, editor)
    else -> throw exExceptionMessage("E475", argument)
  }

  private fun variableIn(text: String): VariableExpression {
    val name = text.trim()
    if (name.isEmpty()) throw exExceptionMessage("E471")
    val (scope, bare) = Scope.split(name)
    if (bare.isEmpty()) throw exExceptionMessage("E475", name)
    return VariableExpression(scope, bare)
  }

  /**
   * `:redir @a`, `:redir @a>`, `:redir @a>>`, `:redir @A`.
   *
   * The `>` is optional after a register name and always has been - Vim keeps it for compatibility
   * with configs written before it meant anything. An uppercase name appends, which is the rule
   * everywhere else registers are written and so is not spelled out again here.
   */
  private fun registerSink(argument: String, editor: VimEditor, context: ExecutionContext): Redirection.Sink {
    val body = argument.removePrefix("@")
    val name = body.firstOrNull() ?: throw exExceptionMessage("E471")
    val arrows = body.drop(1).trim()
    if (arrows.isNotEmpty() && arrows != ">" && arrows != ">>") throw exExceptionMessage("E475", argument)

    val register = name.lowercaseChar()
    if (!RegisterConstants.WRITABLE_REGISTERS.contains(register)) throw exExceptionMessage("E354", name)
    val append = name.isUpperCase() || arrows == ">>"
    return lvalueSink(RegisterExpression(register), append, editor, context, describe = "@$name")
  }

  /**
   * A register and a variable, which are one sink because both are `LValueExpression`.
   *
   * The old value is read once, here, and never again: re-reading on each write would pick up the
   * redirection's own output and double it every time a message arrived.
   */
  private fun lvalueSink(
    lvalue: LValueExpression,
    append: Boolean,
    editor: VimEditor,
    context: ExecutionContext,
    describe: String = lvalue.toString(),
  ): Redirection.Sink {
    val prefix = if (append) lvalue.evaluate(editor, context, vimContext).toVimString().value else ""
    return Redirection.Sink(describe, prefix) { anyEditor, anyContext, content ->
      lvalue.assign(VimString(content), anyEditor, anyContext, vimContext, describe)
    }
  }

  /**
   * `:redir > file`, which refuses to overwrite unless `:redir!` says to - Vim's `E189`.
   *
   * The whole file is rewritten on every message rather than appended to, because a host's file
   * service has no append and this way an unterminated redirection still leaves a complete file.
   * Redirected output is a table or a few lines; nothing here is a log.
   */
  private fun fileSink(path: String, append: Boolean, editor: VimEditor): Redirection.Sink {
    if (path.isEmpty()) throw exExceptionMessage("E471")
    val resolved = WorkingDirectory.resolve(injector.pathExpansion.expandPath(path), editor)
    val exists = injector.fileSystem.exists(resolved)
    if (!append && exists && modifier != CommandModifier.BANG) throw exExceptionMessage("E189", path)

    val prefix = if (append && exists) injector.fileSystem.readText(resolved) else ""
    return Redirection.Sink(path, prefix) { _, _, content ->
      injector.fileSystem.writeText(resolved, content)?.let { failure ->
        throw exExceptionMessage("E212.reason", resolved, failure)
      }
    }
  }
}
