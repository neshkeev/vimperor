/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.ImmutableVimCaret
import com.maddyhome.idea.vim.api.Options
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VirtualBufferKind
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
 * The one place this is not Vim is `'errorformat'`, which is declared and not read (see
 * [com.maddyhome.idea.vim.quickfix.parseQuickfixLines] for what is read instead). `:copen` opens a
 * read-only buffer, one line per entry, and Enter on a line jumps to it. `:clist` still prints the
 * table to the output panel.
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

  /**
   * The list this command walks.
   *
   * Typed in the window that shows the list, the list is the one that window was opened from.
   * The location list lives on that window, and the window itself has none of its own, so `:lnext`
   * in the location window would otherwise be E42 on an empty list. The quickfix list is the
   * session's, and naming the original window does not change which one that is.
   */
  protected fun list(editor: VimEditor): QuickfixList {
    val source = if (editor.getVirtualBufferKind() == windowKind()) {
      editor.getCmdwinOriginalEditor() ?: editor
    } else {
      editor
    }
    return Quickfix.listFor(source, isLocation)
  }

  /**
   * Goes to [entry], which is what every stepping command does once it has found one.
   *
   * The file is opened by name and the caret moved by line and column, so a jump into a file that
   * is not open opens it - as Vim's does.
   */
  protected fun jumpTo(editor: VimEditor, context: ExecutionContext, entry: QuickfixEntry?): ExecutionResult =
    jumpToQuickfixEntry(editor, context, entry, list(editor))

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
    val result = if (!jump) {
      ExecutionResult.Success
    } else {
      val first = list.end(last = false)
      if (first == null) ExecutionResult.Success else jumpTo(editor, context, first)
    }
    // The window follows the list when it is open, and is not opened by a jump.
    syncWindow(editor, context)
    return result
  }

  /** The read-only window for this command's list. Quickfix and location are different buffers. */
  protected fun windowKind(): VirtualBufferKind = if (isLocation) LocationWindow else QuickfixWindow

  /**
   * Rewrites the open window so its caret line is the current entry.
   *
   * No window means nothing: `:cnext` with the list hidden must not open one. The text and the
   * caret are one call, so a step that does not change the lines still moves the caret.
   */
  protected fun syncWindow(editor: VimEditor, context: ExecutionContext) {
    val kind = windowKind()
    val group = injector.virtualBufferGroup
    if (!group.isOpen(context, kind)) return
    val list = list(editor)
    group.refresh(context, kind, renderQuickfixList(list), list.index)
  }
}

/**
 * Goes to [entry] in [editor], which is the file rather than the quickfix window.
 *
 * The focused editor is where a host puts a file it has just opened. It is the wrong one while the
 * quickfix window is what has focus: the caret would move in the list. A command typed in the list
 * itself means the editor the list was opened from.
 */
private fun jumpToQuickfixEntry(
  editor: VimEditor,
  context: ExecutionContext,
  entry: QuickfixEntry?,
  list: QuickfixList,
): ExecutionResult {
  if (entry == null) throw exExceptionMessage("E42")
  val path = entry.path ?: throw exExceptionMessage("E42")

  val failure = injector.file.openFile(path, context)
  if (failure != null) {
    injector.messages.showErrorMessage(editor, failure)
    return ExecutionResult.Error
  }

  val focused = injector.editorGroup.getFocusedEditor()
  val target = when {
    focused != null && !focused.isQuickfixWindow() -> focused
    !editor.isQuickfixWindow() -> editor
    else -> editor.getCmdwinOriginalEditor() ?: editor
  }
  val line = (entry.line - 1).coerceIn(0, (target.lineCount() - 1).coerceAtLeast(0))
  val column = (entry.column - 1).coerceAtLeast(0)
  target.currentCaret().moveToBufferPosition(BufferPosition(line, column))
  injector.scroll.scrollCaretIntoView(target)

  injector.messages.showStatusBarMessage(target, "(${list.index + 1} of ${list.size}) ${entry.text}")
  return ExecutionResult.Success
}

private fun VimEditor.isQuickfixWindow(): Boolean =
  getVirtualBufferKind().let { it == QuickfixWindow || it == LocationWindow }

/** One line per entry, no header, so the caret line is the entry's index. An invalid entry is its text. */
private fun renderQuickfixList(list: QuickfixList): String =
  list.all().joinToString("\n") { entry ->
    if (entry.isValid) "${entry.path}:${entry.line} col ${entry.column}: ${entry.text}" else entry.text
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
@ExCommand(
  command = "cex[pr]",
  description = "Read errors from {expr} into the quickfix list and jump to the first.",
)
data class CExprCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixExprCommand(range, modifier, argument, isLocation = false, add = false)

/** see "h :caddexpr" */
@ExCommand(
  command = "cadde[xpr]",
  description = "Read errors from {expr} and add them to the quickfix list without jumping.",
)
data class CAddExprCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixExprCommand(range, modifier, argument, isLocation = false, add = true)

/** see "h :lexpr" */
@ExCommand(
  command = "lex[pr]",
  description = "Read errors from {expr} into the location list of the current window.",
)
data class LExprCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixExprCommand(range, modifier, argument, isLocation = true, add = false)

/** see "h :laddexpr" */
@ExCommand(
  command = "ladde[xpr]",
  description = "Read errors from {expr} and add them to the location list without jumping.",
)
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
@ExCommand(
  command = "cf[ile]",
  description = "Read the error file into the quickfix list and jump to the first error.",
)
data class CFileCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixFileCommand(range, modifier, argument, isLocation = false)

/** see "h :lfile" */
@ExCommand(
  command = "lf[ile]",
  description = "Read the error file into the location list of the current window.",
)
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
@ExCommand(
  command = "cb[uffer]",
  description = "Read the lines of a buffer into the quickfix list.",
)
data class CBufferCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixBufferCommand(range, modifier, argument, isLocation = false)

/** see "h :lbuffer" */
@ExCommand(
  command = "lb[uffer]",
  description = "Read the lines of a buffer into the location list of the current window.",
)
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
    val program = injector.optionGroup.getOptionValue(option, OptionAccessScope.EFFECTIVE(editor)).toVimString().value
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
      syncWindow(editor, context)
      injector.messages.showMessage(editor, injector.messages.message("E42"))
      return ExecutionResult.Success
    }
    return fill(editor, context, entries, add = false, jump = true)
  }
}

/** see "h :make" */
@ExCommand(
  command = "mak[e]",
  description = "Run 'makeprg', read its errors into the quickfix list and jump to the first.",
)
data class MakeCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixProgramCommand(range, modifier, argument, isLocation = false, isGrep = false)

/** see "h :lmake" */
@ExCommand(
  command = "lmak[e]",
  description = "Run 'makeprg' and read its errors into the location list of the current window.",
)
data class LMakeCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixProgramCommand(range, modifier, argument, isLocation = true, isGrep = false)

/** see "h :grep" */
@ExCommand(
  command = "gr[ep]",
  description = "Run 'grepprg', read its matches into the quickfix list and jump to the first.",
)
data class GrepCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixProgramCommand(range, modifier, argument, isLocation = false, isGrep = true)

/** see "h :lgrep" */
@ExCommand(
  command = "lgr[ep]",
  description = "Run 'grepprg' and read its matches into the location list of the current window.",
)
data class LGrepCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixProgramCommand(range, modifier, argument, isLocation = true, isGrep = true)

// ---- Reading the list

/**
 * see "h :clist" / "h :llist"
 *
 * Prints the list to the output panel, the same place `:ls` and `:registers` print. The current
 * entry is marked with `>`. An empty list is E42. `:copen` is the window, not this command.
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

/** see "h :clist" */
@ExCommand(
  command = "cl[ist]",
  description = "Print the quickfix list.",
)
data class CListCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixListCommand(range, modifier, argument, isLocation = false)

/** see "h :llist" */
@ExCommand(
  command = "lli[st]",
  description = "Print the location list of the current window.",
)
data class LListCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixListCommand(range, modifier, argument, isLocation = true)

/**
 * see "h :copen" / "h :cwindow" / "h :lopen" / "h :lwindow"
 *
 * One line per entry and no header, so the line the caret is on is the entry. `:copen` opens even
 * when the list is empty. `:cwindow` opens only when some entry has a file, and closes the window
 * when one is open and none does. A height argument is accepted and ignored.
 */
sealed class QuickfixOpenCommand(
  range: Range,
  modifier: CommandModifier,
  argument: String,
  isLocation: Boolean,
  private val onlyWhenValid: Boolean,
) : QuickfixCommand(range, modifier, argument, isLocation) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val list = list(editor)
    val kind = windowKind()
    val group = injector.virtualBufferGroup
    if (onlyWhenValid && list.all().none { it.isValid }) {
      if (group.isOpen(context, kind)) group.close(context, kind)
      return ExecutionResult.Success
    }
    group.open(context, editor, kind, renderQuickfixList(list), caretLine = list.index)
    return ExecutionResult.Success
  }
}

/** see "h :copen" */
@ExCommand(
  command = "cope[n]",
  description = "Open a window showing the quickfix list.",
)
data class COpenCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixOpenCommand(range, modifier, argument, isLocation = false, onlyWhenValid = false)

/** see "h :cwindow" */
@ExCommand(
  command = "cw[indow]",
  description = "Open the quickfix window when the list has a valid entry, and close it when not.",
)
data class CWindowCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixOpenCommand(range, modifier, argument, isLocation = false, onlyWhenValid = true)

/** see "h :lopen" */
@ExCommand(
  command = "lop[en]",
  description = "Open a window showing the location list of the current window.",
)
data class LOpenCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixOpenCommand(range, modifier, argument, isLocation = true, onlyWhenValid = false)

/** see "h :lwindow" */
@ExCommand(
  command = "lw[indow]",
  description = "Open the location window when the list has a valid entry, and close it when not.",
)
data class LWindowCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixOpenCommand(range, modifier, argument, isLocation = true, onlyWhenValid = true)

/**
 * see "h :cclose" / "h :lclose"
 *
 * Closes the window and leaves the list. Nothing open is not an error. The output panel is not the
 * window: closing one must not dismiss what `:registers` printed. Typed in the window itself, the
 * editor the window was opened from is shown afterwards, when that editor is still there.
 */
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
    val kind = windowKind()
    val original = if (editor.getVirtualBufferKind() == kind) editor.getCmdwinOriginalEditor() else null
    injector.virtualBufferGroup.close(context, kind)
    if (original != null) injector.virtualBufferGroup.showOriginal(original) {}
    return ExecutionResult.Success
  }
}

/** see "h :cclose" */
@ExCommand(
  command = "ccl[ose]",
  description = "Close the quickfix window.",
)
data class CCloseCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixCloseCommand(range, modifier, argument, isLocation = false)

/** see "h :lclose" */
@ExCommand(
  command = "lcl[ose]",
  description = "Close the location list window.",
)
data class LCloseCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixCloseCommand(range, modifier, argument, isLocation = true)

// ---- Walking the list

/** `:cnext`, `:cprevious`, `:cfirst`, `:clast`, `:cc`, `:cnfile` and `:cpfile`, and their `l` twins. */
sealed class QuickfixStepCommand(
  range: Range,
  modifier: CommandModifier,
  argument: String,
  isLocation: Boolean,
  private val step: Step,
) : QuickfixCommand(range, modifier, argument, isLocation) {

  protected enum class Step { NEXT, PREVIOUS, FIRST, LAST, AT, NEXT_FILE, PREVIOUS_FILE }

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
      // `:2cnfile` arrives as a range, `:cnfile 2` as the argument. Same count either way. The other
      // steps stay on the argument, which is how they already worked.
      Step.NEXT_FILE -> list.stepFile(fileCount(editor, count))
      Step.PREVIOUS_FILE -> list.stepFile(-fileCount(editor, count))
    }
    syncWindow(editor, context)
    return jumpTo(editor, context, entry)
  }

  private fun fileCount(editor: VimEditor, fromArgument: Int?): Int {
    if (fromArgument != null) return fromArgument
    if (isRangeSpecified()) return getCountFromRange(editor, editor.currentCaret()).coerceAtLeast(1)
    return 1
  }
}

/** see "h :cnext" */
@ExCommand(
  command = "cn[ext]",
  description = "Go to the [count] next error in the quickfix list.",
)
data class CNextCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = false, step = Step.NEXT)

/** see "h :cprevious" */
@ExCommand(
  command = "cp[revious],cN[ext]",
  description = "Go to the [count] previous error in the quickfix list.",
)
data class CPreviousCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = false, step = Step.PREVIOUS)

/** see "h :cfirst" */
@ExCommand(
  command = "cfir[st],crewind",
  description = "Go to error [count] in the quickfix list, the first by default.",
)
data class CFirstCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = false, step = Step.FIRST)

/** see "h :clast" */
@ExCommand(
  command = "cla[st]",
  description = "Go to error [count] from the end of the quickfix list, the last by default.",
)
data class CLastCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = false, step = Step.LAST)

/** see "h :cc" */
@ExCommand(
  command = "cc",
  description = "Go to error [nr] in the quickfix list, or the current one again.",
)
data class CCCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = false, step = Step.AT)

/** see "h :lnext" */
@ExCommand(
  command = "lne[xt]",
  description = "Go to the [count] next error in the location list.",
)
data class LNextCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = true, step = Step.NEXT)

/** see "h :lprevious" */
@ExCommand(
  command = "lp[revious],lN[ext]",
  description = "Go to the [count] previous error in the location list.",
)
data class LPreviousCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = true, step = Step.PREVIOUS)

/** see "h :lfirst" */
@ExCommand(
  command = "lfir[st],lrewind",
  description = "Go to error [count] in the location list, the first by default.",
)
data class LFirstCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = true, step = Step.FIRST)

/** see "h :llast" */
@ExCommand(
  command = "lla[st]",
  description = "Go to error [count] from the end of the location list, the last by default.",
)
data class LLastCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = true, step = Step.LAST)

/** see "h :ll" */
@ExCommand(
  command = "ll",
  description = "Go to error [nr] in the location list, or the current one again.",
)
data class LLCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = true, step = Step.AT)

/** see "h :cnfile" */
@ExCommand(
  command = "cnf[ile]",
  description = "Go to the first error in the [count] next file in the quickfix list.",
)
data class CNextFileCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = false, step = Step.NEXT_FILE)

/** see "h :cpfile" */
@ExCommand(
  command = "cpf[ile],cNf[ile]",
  description = "Go to the last error in the [count] previous file in the quickfix list.",
)
data class CPreviousFileCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = false, step = Step.PREVIOUS_FILE)

/** see "h :lnfile" */
@ExCommand(
  command = "lnf[ile]",
  description = "Go to the first error in the [count] next file in the location list.",
)
data class LNextFileCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = true, step = Step.NEXT_FILE)

/** see "h :lpfile" */
@ExCommand(
  command = "lpf[ile],lNf[ile]",
  description = "Go to the last error in the [count] previous file in the location list.",
)
data class LPreviousFileCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  QuickfixStepCommand(range, modifier, argument, isLocation = true, step = Step.PREVIOUS_FILE)

/**
 * The read-only window `:copen` opens. An already open one is reused, and it is not exclusive:
 * `q:` still refuses another buffer, and this one is refused while `q:` is up.
 *
 * Enter jumps to the caret's line in the editor the window was opened from. The list stays open.
 * The line index is the entry: the text is not parsed back.
 */
internal data object QuickfixWindow : VirtualBufferKind(), VirtualBufferKind.EnterHandler {
  override val fileName: String = "[Quickfix List]"
  override val readOnly: Boolean = true
  override val reuseOpen: Boolean = true

  override fun onEnter(buffer: VimEditor, caret: ImmutableVimCaret, context: ExecutionContext) {
    quickfixWindowEnter(isLocation = false, buffer, caret, context)
  }
}

/** The same window for the location list. A different buffer, so both may be open. */
internal data object LocationWindow : VirtualBufferKind(), VirtualBufferKind.EnterHandler {
  override val fileName: String = "[Location List]"
  override val readOnly: Boolean = true
  override val reuseOpen: Boolean = true

  override fun onEnter(buffer: VimEditor, caret: ImmutableVimCaret, context: ExecutionContext) {
    quickfixWindowEnter(isLocation = true, buffer, caret, context)
  }
}

private fun quickfixWindowEnter(
  isLocation: Boolean,
  buffer: VimEditor,
  caret: ImmutableVimCaret,
  context: ExecutionContext,
) {
  // The line under the caret, read before focus moves. That index is the entry.
  val line = caret.getBufferPosition().line
  val from = buffer.getCmdwinOriginalEditor() ?: return
  val list = Quickfix.listFor(from, isLocation)
  val entry = list.at(line) ?: return
  if (!entry.isValid) return
  list.moveTo(line)
  // Show the file first. A host that still has the list focused would otherwise move the caret there.
  injector.virtualBufferGroup.showOriginal(from) {
    jumpToQuickfixEntry(from, context, entry, list)
  }
}
