/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.Options
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.quickfix.Quickfix
import com.maddyhome.idea.vim.quickfix.QuickfixEntry
import com.maddyhome.idea.vim.quickfix.QuickfixList
import com.maddyhome.idea.vim.quickfix.parseQuickfixLines
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * Vim's quickfix list, and the location list beside it.
 *
 * The quickfix list is a list of places - a file, a line, a column and a message - that something
 * produced, and a set of commands for filling it and walking it. Vim fills it from a compiler
 * (`:make`), from a search (`:grep`), from an expression (`:cexpr`) or from a file (`:cfile`); it
 * shows it in a window (`:copen`) and steps through it (`:cnext`). Every one of those has a twin
 * whose name starts with `l` and which uses the window's own list instead.
 *
 * All of it is engine work. Running a process is something both hosts already answer for `:!`,
 * opening a file at a line is what `:edit` and `:buffer` already do, and the list itself is a list.
 * The two places this is not Vim are written down where they happen: `'errorformat'` is declared and
 * not read (see [com.maddyhome.idea.vim.quickfix.parseQuickfixLines] for what is read instead), and
 * `:copen` prints into the output panel rather than opening a buffer you can put the caret in -
 * neither host lets an extension make a buffer out of nothing to hold it.
 */
sealed class QuickfixCommand(
  range: Range,
  modifier: CommandModifier,
  argument: String,
  /** True for the `l`-prefixed twin, which uses the window's list rather than the session's. */
  protected val isLocation: Boolean,
) : Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  protected fun list(editor: VimEditor): QuickfixList = Quickfix.listFor(editor, isLocation)

  /**
   * Goes to [entry], which is what every stepping command does once it has found one.
   *
   * The file is opened by name and the caret moved by line and column, so a jump into a file that
   * is not open opens it - as Vim's does.
   */
  protected fun jumpTo(editor: VimEditor, context: ExecutionContext, entry: QuickfixEntry?): ExecutionResult {
    if (entry == null) throw exExceptionMessage("E42")
    val path = entry.path ?: throw exExceptionMessage("E42")

    val failure = injector.file.openFile(path, context)
    if (failure != null) {
      injector.messages.showErrorMessage(editor, failure)
      return ExecutionResult.Error
    }

    // The editor that is focused now, which is the one the file was opened into. Falling back to
    // the editor the command was typed in covers a host that opens asynchronously: the caret then
    // lands in the wrong window rather than nowhere, and the message still names the right place.
    val target = injector.editorGroup.getFocusedEditor() ?: editor
    val line = (entry.line - 1).coerceIn(0, (target.lineCount() - 1).coerceAtLeast(0))
    val column = (entry.column - 1).coerceAtLeast(0)
    target.currentCaret().moveToBufferPosition(com.maddyhome.idea.vim.api.BufferPosition(line, column))
    injector.scroll.scrollCaretIntoView(target)

    injector.messages.showStatusBarMessage(target, "(${list(editor).index + 1} of ${list(editor).size}) ${entry.text}")
    return ExecutionResult.Success
  }

  /** Replaces or extends the list, and goes to the first entry when Vim would. */
  protected fun fill(
    editor: VimEditor,
    context: ExecutionContext,
    entries: List<QuickfixEntry>,
    add: Boolean,
    jump: Boolean,
  ): ExecutionResult {
    val list = list(editor)
    if (add) list.add(entries) else list.replaceWith(entries)
    if (!jump) return ExecutionResult.Success
    val first = list.end(last = false) ?: return ExecutionResult.Success
    return jumpTo(editor, context, first)
  }
}

// ---- Filling the list

/**
 * see "h :cexpr" / "h :caddexpr" / "h :lexpr" / "h :laddexpr"
 *
 * The expression is evaluated and its lines read. A string with newlines in it and a list of
 * strings are both accepted, which is what Vim accepts.
 */
sealed class QuickfixExprCommand(
  range: Range,
  modifier: CommandModifier,
  argument: String,
  isLocation: Boolean,
  private val add: Boolean,
) : QuickfixCommand(range, modifier, argument, isLocation) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val expression = injector.vimscriptParser.parseExpression(commandArgument.trim())
      ?: throw exExceptionMessage("E15", commandArgument.trim())
    val value = expression.evaluate(editor, context, this)
    val lines = value.toOutputString().split("\n")
    return fill(editor, context, parseQuickfixLines(lines), add, jump = !add)
  }
}

/** see "h :cexpr" */
@ExCommand(command = "cex[pr]")
data class CExprCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixExprCommand(range, modifier, argument, isLocation = false, add = false)

/** see "h :caddexpr" */
@ExCommand(command = "cadde[xpr]")
data class CAddExprCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixExprCommand(range, modifier, argument, isLocation = false, add = true)

/** see "h :lexpr" */
@ExCommand(command = "lex[pr]")
data class LExprCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixExprCommand(range, modifier, argument, isLocation = true, add = false)

/** see "h :laddexpr" */
@ExCommand(command = "ladde[xpr]")
data class LAddExprCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixExprCommand(range, modifier, argument, isLocation = true, add = true)

/**
 * see "h :cfile" / "h :lfile"
 *
 * Reads the list out of a file, which is how a build that ran somewhere else gets in.
 */
sealed class QuickfixFileCommand(
  range: Range,
  modifier: CommandModifier,
  argument: String,
  isLocation: Boolean,
) : QuickfixCommand(range, modifier, argument, isLocation) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val name = commandArgument.trim().ifEmpty { "errors.err" }
    val path = injector.file.findFile(injector.pathExpansion.expandPath(name), context)
      ?: throw exExceptionMessage("E484", name)
    val text = try {
      injector.fileSystem.readText(path)
    } catch (_: Exception) {
      throw exExceptionMessage("E484", name)
    }
    return fill(editor, context, parseQuickfixLines(text.split("\n")), add = false, jump = true)
  }
}

/** see "h :cfile" */
@ExCommand(command = "cf[ile]")
data class CFileCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixFileCommand(range, modifier, argument, isLocation = false)

/** see "h :lfile" */
@ExCommand(command = "lf[ile]")
data class LFileCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixFileCommand(range, modifier, argument, isLocation = true)

/** see "h :cbuffer" / "h :lbuffer" - the same, out of the buffer you are looking at. */
sealed class QuickfixBufferCommand(
  range: Range,
  modifier: CommandModifier,
  argument: String,
  isLocation: Boolean,
) : QuickfixCommand(range, modifier, argument, isLocation) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult =
    fill(editor, context, parseQuickfixLines(editor.text().toString().split("\n")), add = false, jump = true)
}

/** see "h :cbuffer" */
@ExCommand(command = "cb[uffer]")
data class CBufferCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixBufferCommand(range, modifier, argument, isLocation = false)

/** see "h :lbuffer" */
@ExCommand(command = "lb[uffer]")
data class LBufferCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixBufferCommand(range, modifier, argument, isLocation = true)

/**
 * see "h :make" / "h :grep" / "h :lmake" / "h :lgrep"
 *
 * Runs a program and reads what it printed. `'makeprg'` and `'grepprg'` say which program, `$*` in
 * them is replaced by the argument as Vim does, and the process is the same one `:!` runs through -
 * so the shell, the working directory and the output are whatever `:!` would have given.
 */
sealed class QuickfixProgramCommand(
  range: Range,
  modifier: CommandModifier,
  argument: String,
  isLocation: Boolean,
  private val isGrep: Boolean,
) : QuickfixCommand(range, modifier, argument, isLocation) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val option = if (isGrep) Options.grepprg else Options.makeprg
    val program = injector.optionGroup.getOptionValue(option, OptionAccessScope.EFFECTIVE(editor)).asString()
    val arguments = commandArgument.trim()
    // Vim's rule: `$*` is where the argument goes, and it is appended when the program has no `$*`.
    val command = if (program.contains("$*")) program.replace("$*", arguments) else "$program $arguments".trim()

    val output = try {
      injector.processGroup.executeCommand(editor, command, null, null, injector.globalOptions())
    } catch (e: Exception) {
      injector.messages.showErrorMessage(editor, e.message)
      return ExecutionResult.Error
    }

    val entries = parseQuickfixLines(output.orEmpty().split("\n"))
    if (entries.none { it.isValid }) {
      list(editor).replaceWith(entries)
      injector.messages.showMessage(editor, injector.messages.message("E42"))
      return ExecutionResult.Success
    }
    return fill(editor, context, entries, add = false, jump = true)
  }
}

/** see "h :make" */
@ExCommand(command = "mak[e]")
data class MakeCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixProgramCommand(range, modifier, argument, isLocation = false, isGrep = false)

/** see "h :lmake" */
@ExCommand(command = "lmak[e]")
data class LMakeCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixProgramCommand(range, modifier, argument, isLocation = true, isGrep = false)

/** see "h :grep" */
@ExCommand(command = "gr[ep]")
data class GrepCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixProgramCommand(range, modifier, argument, isLocation = false, isGrep = true)

/** see "h :lgrep" */
@ExCommand(command = "lgr[ep]")
data class LGrepCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixProgramCommand(range, modifier, argument, isLocation = true, isGrep = true)

// ---- Reading the list

/**
 * see "h :clist" / "h :llist" / "h :copen" / "h :lopen"
 *
 * Vim opens a *buffer* holding the list, and moving the caret in it and pressing Enter jumps.
 * Neither host lets an extension make a buffer out of nothing, so the list is printed to the output
 * panel instead - the same place `:ls` and `:registers` print - and `:cnext` and `:cc` are how you
 * move through it. `:cclose` clears that panel, which is the nearest thing to closing the window.
 */
sealed class QuickfixListCommand(
  range: Range,
  modifier: CommandModifier,
  argument: String,
  isLocation: Boolean,
) : QuickfixCommand(range, modifier, argument, isLocation) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val list = list(editor)
    if (list.isEmpty) throw exExceptionMessage("E42")

    val width = list.size.toString().length
    val rows = list.all().mapIndexed { index, entry ->
      val number = (index + 1).toString().padStart(width)
      val marker = if (index == list.index) ">" else " "
      if (entry.isValid) {
        "$marker$number ${entry.path}:${entry.line} col ${entry.column}: ${entry.text}"
      } else {
        "$marker$number ${entry.text}"
      }
    }
    injector.outputPanel.output(editor, context, rows.joinToString("\n"))
    return ExecutionResult.Success
  }
}

/** see "h :clist" - `:copen` and `:cwindow` print the same table; see [QuickfixListCommand]. */
@ExCommand(command = "cl[ist],cope[n],cw[indow]")
data class CListCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixListCommand(range, modifier, argument, isLocation = false)

/** see "h :llist" */
@ExCommand(command = "lli[st],lop[en],lw[indow]")
data class LListCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixListCommand(range, modifier, argument, isLocation = true)

/** see "h :cclose" - clears the panel the list was printed to. */
sealed class QuickfixCloseCommand(
  range: Range,
  modifier: CommandModifier,
  argument: String,
  isLocation: Boolean,
) : QuickfixCommand(range, modifier, argument, isLocation) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    injector.outputPanel.getCurrentOutputPanel()?.close()
    return ExecutionResult.Success
  }
}

/** see "h :cclose" */
@ExCommand(command = "ccl[ose]")
data class CCloseCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixCloseCommand(range, modifier, argument, isLocation = false)

/** see "h :lclose" */
@ExCommand(command = "lcl[ose]")
data class LCloseCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixCloseCommand(range, modifier, argument, isLocation = true)

// ---- Walking the list

/** `:cnext`, `:cprevious`, `:cfirst`, `:clast` and `:cc`, and their `l` twins. */
sealed class QuickfixStepCommand(
  range: Range,
  modifier: CommandModifier,
  argument: String,
  isLocation: Boolean,
  private val step: Step,
) : QuickfixCommand(range, modifier, argument, isLocation) {

  protected enum class Step { NEXT, PREVIOUS, FIRST, LAST, AT }

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val list = list(editor)
    if (list.isEmpty) throw exExceptionMessage("E42")

    val count = commandArgument.trim().toIntOrNull()
    val entry = when (step) {
      Step.NEXT -> list.step(count ?: 1)
      Step.PREVIOUS -> list.step(-(count ?: 1))
      Step.FIRST -> list.end(last = false)
      Step.LAST -> list.end(last = true)
      // Vim numbers the list from one; `:cc` with no number goes to the entry it is already on.
      Step.AT -> if (count == null) list.at(list.index) else list.moveTo(count - 1)
    }
    return jumpTo(editor, context, entry)
  }
}

/** see "h :cnext" */
@ExCommand(command = "cn[ext]")
data class CNextCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = false, step = Step.NEXT)

/** see "h :cprevious" */
@ExCommand(command = "cp[revious],cN[ext]")
data class CPreviousCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = false, step = Step.PREVIOUS)

/** see "h :cfirst" */
@ExCommand(command = "cfir[st],crewind")
data class CFirstCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = false, step = Step.FIRST)

/** see "h :clast" */
@ExCommand(command = "cla[st]")
data class CLastCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = false, step = Step.LAST)

/** see "h :cc" */
@ExCommand(command = "cc")
data class CCCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = false, step = Step.AT)

/** see "h :lnext" */
@ExCommand(command = "lne[xt]")
data class LNextCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = true, step = Step.NEXT)

/** see "h :lprevious" */
@ExCommand(command = "lp[revious],lN[ext]")
data class LPreviousCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = true, step = Step.PREVIOUS)

/** see "h :lfirst" */
@ExCommand(command = "lfir[st],lrewind")
data class LFirstCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = true, step = Step.FIRST)

/** see "h :llast" */
@ExCommand(command = "lla[st]")
data class LLastCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = true, step = Step.LAST)

/** see "h :ll" */
@ExCommand(command = "ll")
data class LLCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = true, step = Step.AT)
