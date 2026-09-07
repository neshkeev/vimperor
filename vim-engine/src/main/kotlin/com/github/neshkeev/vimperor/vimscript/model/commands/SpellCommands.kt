/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vimscript.model.commands
import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.commands.Command
import com.maddyhome.idea.vim.vimscript.model.commands.CommandModifier

/**
 * `:spellgood`, `:spellwrong` and `:spellundo` - the three spell commands a borrowed checker can
 * answer.
 *
 * Vim owns its spell checking: it reads `.spl` files, keeps a good list, a bad list and a rare
 * list, and every `:spell` command is about one of those. This engine owns none of it. What it has
 * is a [com.maddyhome.idea.vim.api.SpellcheckerService] with three methods, which is the shape of
 * the checker the *IDE* already runs - and `zg`, `zw` and `z=` have been going through it since
 * long before these commands existed.
 *
 * So these three are `zg` and `zw` with the word named rather than pointed at, which is exactly what
 * Vim says they are. The other five `:spell` commands are next door reporting `E319`, because each
 * of them is about a word list this build cannot see: a checker that answers "add", "remove" and
 * "suggest" has no list to dump, no file to describe and nothing to compile.
 *
 * One divergence, and it is inherited rather than introduced: Vim's `:spellwrong` adds a word to
 * the *bad* list and `:spellundo` takes it off either list, which are two different things. There
 * is one operation here, so both remove - which is the same approximation `zw` already makes, and
 * the same one an IDE's dictionary forces: it has words it knows and words it does not, and no
 * third state for a word it has been told is wrong.
 */
sealed class SpellCommandBase(
  range: Range,
  modifier: CommandModifier,
  argument: String,
) : Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_REQUIRED, Access.READ_ONLY)

  protected abstract fun apply(word: String, editor: VimEditor)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val words = commandArgument.trim().split(" ", "\t").filter { it.isNotEmpty() }
    if (words.isEmpty()) throw exExceptionMessage("E471")

    // Vim takes several words on one command line, which is what a `:spellgood` in a config
    // usually is - a line of project jargon rather than one word.
    for (word in words) apply(word, editor)
    return ExecutionResult.Success
  }
}

/** see "h :spellgood" */
@ExCommand(command = "spe[llgood]")
data class SpellGoodCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  SpellCommandBase(range, modifier, argument) {
  override fun apply(word: String, editor: VimEditor) {
    injector.spellcheckerService.addWordToDictionary(word, editor)
  }
}

/** see "h :spellwrong" */
@ExCommand(command = "spellw[rong]")
data class SpellWrongCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  SpellCommandBase(range, modifier, argument) {
  override fun apply(word: String, editor: VimEditor) {
    injector.spellcheckerService.removeWordFromDictionary(word, editor)
  }
}

/** see "h :spellundo" */
@ExCommand(command = "spellu[ndo]")
data class SpellUndoCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  SpellCommandBase(range, modifier, argument) {
  override fun apply(word: String, editor: VimEditor) {
    injector.spellcheckerService.removeWordFromDictionary(word, editor)
  }
}
