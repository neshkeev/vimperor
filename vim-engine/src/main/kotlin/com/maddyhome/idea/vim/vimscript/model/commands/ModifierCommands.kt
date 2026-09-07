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
import com.maddyhome.idea.vim.api.OutputFilter
import com.maddyhome.idea.vim.api.Options
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
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
   * Nothing to run is not an error: `:silent` on its own is a no-op in Vim too. [argument] is here
   * for `:filter`, which is the one modifier that takes something of its own before the command.
   */
  protected fun runModified(
    editor: VimEditor,
    context: ExecutionContext,
    indicateErrors: Boolean = true,
    argument: String = commandArgument,
  ): ExecutionResult {
    val modified = argument.trim()
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

/**
 * see "h :keepmarks"
 *
 * The same flag as [LockMarksCommand]. Vim draws a line between the two - `:keepmarks` keeps the
 * marks *in the lines a `:move` or `:copy` touched*, `:lockmarks` keeps all of them - and this
 * engine adjusts marks in one place with no notion of which lines a command claimed, so the
 * narrower one is answered with the wider. It keeps what `:keepmarks` asks to keep and some more
 * besides, which is the failure worth having of the two.
 */
@ExCommand(command = "kee[pmarks]")
data class KeepMarksCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
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

/**
 * see "h :keepjumps"
 *
 * "The jumplist, the alternate file mark and the changelist are not changed" - which is what a
 * mapping wants when it moves the caret about to do its work and does not want `''` to lead back
 * into the middle of that.
 */
@ExCommand(command = "keepj[umps]")
data class KeepJumpsCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  ModifierCommand(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val previous = injector.jumpService.recordingSuppressed
    injector.jumpService.recordingSuppressed = true
    try {
      return runModified(editor, context)
    } finally {
      injector.jumpService.recordingSuppressed = previous
    }
  }
}

/**
 * see "h :keeppatterns"
 *
 * The last search pattern, the `/` register and the search history all stay as they were, so a
 * mapping that searches to find something does not leave that search behind for `n` to repeat.
 */
@ExCommand(command = "keepp[atterns]")
data class KeepPatternsCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  ModifierCommand(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val previous = injector.searchGroup.patternRecordingSuppressed
    injector.searchGroup.patternRecordingSuppressed = true
    try {
      return runModified(editor, context)
    } finally {
      injector.searchGroup.patternRecordingSuppressed = previous
    }
  }
}

/**
 * see "h :keepalt"
 *
 * The one of the five this fork cannot honour yet. Vim's `:keepalt` keeps the alternate file - the
 * `#` that `:e #` and `<C-^>` go back to - and neither host tracks that through the engine: it is
 * IntelliJ's last tab and VS Code's previous editor, decided outside anything this could suppress.
 *
 * So this runs the command and the alternate file changes anyway. That is worth having over the
 * alternative: without it, `:keepalt {cmd}` is parsed as `:k eepalt`, sets a mark named `e`, and
 * never runs the command at all - silently, which is how this was found.
 */
@ExCommand(command = "keepa[lt]")
data class KeepAltCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  ModifierCommand(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = runModified(editor, context)
}

/**
 * see "h :unsilent"
 *
 * The way out of a `:silent` that is wrapped around a whole block - a function called under
 * `:silent` can still say the one thing it needs to. So it clears the suppression rather than
 * setting one, and puts back whatever was there.
 */
@ExCommand(command = "uns[ilent]")
data class UnsilentCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  ModifierCommand(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val previous = injector.messages.suppression
    injector.messages.suppression = MessageSuppression.NONE
    try {
      return runModified(editor, context)
    } finally {
      injector.messages.suppression = previous
    }
  }
}

/**
 * see "h :filter"
 *
 * `:filter {pat} {command}` shows only the lines of the command's output that match, and
 * `:filter! {pat} {command}` only the ones that do not. `:filter /pat/ {command}` is the same with
 * the pattern delimited, which is how you write one containing a space.
 *
 * The unit is the line, as it is in Vim, so `:filter /vim/ registers` prints the rows of that table
 * that mention vim and no others - the header included, since Vim filters that too. It is read
 * where the output panel is written, which is the one place every table and every `:echo` passes
 * through.
 */
@ExCommand(command = "filt[er]")
data class FilterCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  ModifierCommand(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val (pattern, command) = split(argument.trim()) ?: throw exExceptionMessage("E471")

    val previous = injector.messages.outputFilter
    injector.messages.outputFilter = OutputFilter(pattern, invert = modifier == CommandModifier.BANG)
    try {
      return runModified(editor, context, argument = command)
    } finally {
      injector.messages.outputFilter = previous
    }
  }

  /**
   * The pattern and the command it filters.
   *
   * Vim allows any non-identifier character as the delimiter, not only `/`, and a bare pattern with
   * no delimiter at all - which then runs to the first space, because that is the only thing that
   * could end it.
   */
  private fun split(text: String): Pair<String, String>? {
    if (text.isEmpty()) return null
    val delimiter = text.first()
    if (!delimiter.isLetterOrDigit() && delimiter != '_' && delimiter != '\\') {
      val end = text.indexOf(delimiter, startIndex = 1)
      if (end < 0) return null
      return text.substring(1, end) to text.substring(end + 1).trim()
    }
    val space = text.indexOfFirst { it == ' ' || it == '\t' }
    if (space < 0) return null
    return text.substring(0, space) to text.substring(space).trim()
  }
}

/**
 * The modifiers that name something this fork does not have, and run the command anyway.
 *
 * `:confirm` asks before losing a change; both hosts ask on their own account and neither lets an
 * extension put the question. `:sandbox` runs an expression with side effects forbidden, which is
 * for `'foldexpr'` and modelines out of files you do not trust, and nothing here evaluates one.
 * `:noswapfile` opens a file without a swap file, and there are no swap files.
 *
 * Each of these is a promise about *how* the command runs rather than a change to what it does, so
 * running the command and not keeping the promise is closer to right than refusing the line - and
 * in the two cases where it matters the host is already keeping its own version of the promise.
 */
sealed class AcceptedModifierCommand(range: Range, modifier: CommandModifier, argument: String) :
  ModifierCommand(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = runModified(editor, context)
}

/** see "h :confirm" - the host asks its own question; see [AcceptedModifierCommand]. */
@ExCommand(command = "conf[irm]")
data class ConfirmCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  AcceptedModifierCommand(range, modifier, argument)

/** see "h :sandbox" - nothing here evaluates an untrusted expression; see [AcceptedModifierCommand]. */
@ExCommand(command = "san[dbox]")
data class SandboxCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  AcceptedModifierCommand(range, modifier, argument)

/** see "h :noswapfile" - there are no swap files; see [AcceptedModifierCommand]. */
@ExCommand(command = "noswapf[ile]")
data class NoSwapFileCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  AcceptedModifierCommand(range, modifier, argument)
