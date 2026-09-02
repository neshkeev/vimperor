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
import com.maddyhome.idea.vim.api.MessageSuppression
import com.maddyhome.idea.vim.api.Options
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt

/**
 * Vim's command modifiers: a command whose argument is another command.
 *
 * Vim calls these modifiers rather than commands and lists them under `:help :command-modifiers`,
 * and it is tempting to read that as "the grammar needs a new rule". It does not. A modifier is
 * spelled exactly like any other ex command - a name, an optional bang, and the rest of the line -
 * and the rest of the line is a command, which the executor already knows how to run. The whole of
 * the difference is what each one changes about the run, which is the [runModified] hook below.
 *
 * `:silent!` is the reason these exist. It is how a portable config guards something that may not
 * be there (`silent! colorscheme solarized`), so a config that uses it against a host without it
 * does not merely lose that line - it gets an error for it, which is the noise `:silent!` was
 * written to avoid.
 */
sealed class ModifierCommand(range: Range, modifier: CommandModifier, argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  // SAVE_SELECTION, because whatever the modified command needs is what this one needs: `:silent
  // action Foo` has to reach `:action` with the selection `:action` would have had.
  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.SELF_SYNCHRONIZED, Flag.SAVE_SELECTION)

  /**
   * Runs the modified command with whatever this modifier changes in place.
   *
   * Implementations set their thing, call [runModified], and put it back in a `finally`.
   */
  abstract override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult

  /**
   * The rest of the line, executed.
   *
   * Nothing to run is not an error: `:silent` on its own is a no-op in Vim too.
   */
  protected fun runModified(
    editor: VimEditor,
    context: ExecutionContext,
    indicateErrors: Boolean = true,
  ): ExecutionResult {
    val modified = commandArgument.trim()
    if (modified.isEmpty()) return ExecutionResult.Success
    return injector.vimscriptExecutor.execute(
      modified,
      editor,
      context,
      skipHistory = true,
      indicateErrors = indicateErrors,
      vimContextOrNull,
    )
  }
}

/**
 * see "h :silent"
 *
 * `:silent` hides what the command has to say and `:silent!` hides what went wrong as well - the
 * bang is the whole difference, and it is why `silent!` and not `silent` is what a config reaches
 * for. A command that fails under `:silent!` still fails; it just does so quietly, and the script
 * carries on to the next line.
 *
 * What is hidden is what goes through [com.maddyhome.idea.vim.api.VimMessages]. A command that
 * writes to the output panel itself - `:registers` and the other tables - is not covered, which is
 * a smaller gap than it sounds: nobody silences a table, they silence a line that might fail.
 */
@ExCommand(command = "sil[ent]")
data class SilentCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  ModifierCommand(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val bang = modifier == CommandModifier.BANG
    val previous = injector.messages.suppression
    injector.messages.suppression =
      if (bang) MessageSuppression.EVERYTHING else MessageSuppression.MESSAGES
    try {
      val result = runModified(editor, context, indicateErrors = !bang)
      // `:silent!` is "try this", so the script sees a success whatever happened. `:silent` is only
      // quiet about the ordinary case and reports a failure as any other command would.
      return if (bang) ExecutionResult.Success else result
    } finally {
      injector.messages.suppression = previous
    }
  }
}

/**
 * see "h :verbose"
 *
 * Raises `'verbose'` for the length of one command, which is all Vim's `:verbose` does. Nothing in
 * the engine reads that option yet, so today this runs the command and puts the option back; when
 * something does start reading it, this is already the place that sets it.
 */
@ExCommand(command = "verb[ose]")
data class VerboseCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  ModifierCommand(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val scope = OptionAccessScope.GLOBAL(editor)
    val previous = injector.optionGroup.getOptionValue(Options.verbose, scope)
    injector.optionGroup.setOptionValue(Options.verbose, scope, VimInt(1))
    try {
      return runModified(editor, context)
    } finally {
      injector.optionGroup.setOptionValue(Options.verbose, scope, previous)
    }
  }
}

/**
 * see "h :noautocmd"
 *
 * Runs the command with autocommands turned off, which is what a config needs when the command it
 * is about to run would otherwise fire the very handlers it is in the middle of installing.
 */
@ExCommand(command = "noa[utocmd]")
data class NoAutoCmdCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  ModifierCommand(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val previous = injector.autoCmd.eventsSuppressed
    injector.autoCmd.eventsSuppressed = true
    try {
      return runModified(editor, context)
    } finally {
      injector.autoCmd.eventsSuppressed = previous
    }
  }
}

/**
 * see "h :lockmarks"
 *
 * "Executes {command} without adjusting marks" - an edit that would normally drag every mark below
 * it up or down leaves them where they were. A mark the command *sets* is still set: Vim says that
 * of `'[` and `']`, and there is nothing about the other marks that makes them different.
 */
@ExCommand(command = "lockm[arks]")
data class LockMarksCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  ModifierCommand(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val previous = injector.markService.adjustmentSuppressed
    injector.markService.adjustmentSuppressed = true
    try {
      return runModified(editor, context)
    } finally {
      injector.markService.adjustmentSuppressed = previous
    }
  }
}
