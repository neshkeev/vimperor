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
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.ex.ranges.Range

/**
 * `:smagic` and `:snomagic` - `:s` with `'magic'` decided rather than inherited.
 *
 * Vim's `'magic'` decides how much of a pattern is special without a backslash in front of it, and
 * a script that does not know how the user has set it writes `:smagic` or `:snomagic` so that its
 * own pattern means what it wrote. That is exactly what `\m` and `\M` do from inside a pattern, and
 * they apply from where they appear - so putting one at the front of the pattern is Vim's rule, and
 * a later `\v` in the same pattern still wins, as it does in Vim.
 *
 * So both are `:s` with two characters inserted, and everything else about them - the delimiters,
 * the flags, the count, the `&` and `~` forms - is `:s`, because it *is* `:s`.
 */
sealed class MagicSubstituteCommand(
  private val range: Range,
  modifier: CommandModifier,
  private val argument: String,
  private val atom: String,
) : Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.WRITABLE)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val delegate = SubstituteCommand(range, withMagicAtom(argument), "s")
    vimContextOrNull?.let { delegate.vimContext = it }
    return delegate.execute(editor, context)
  }

  /**
   * The atom, inserted right after the delimiter that opens the pattern.
   *
   * `:smagic/foo/bar/` is `:s/\mfoo/bar/`. An argument with nothing in it is left alone - `:smagic`
   * on its own repeats the last substitution, and there is no pattern to put an atom in front of.
   */
  private fun withMagicAtom(text: String): String {
    val trimmed = text.trim()
    if (trimmed.length < 2) return trimmed
    return trimmed.first() + atom + trimmed.substring(1)
  }
}

/** see "h :smagic" */
@ExCommand(command = "sm[agic]")
data class SubstituteMagicCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  MagicSubstituteCommand(range, modifier, argument, "\\m")

/** see "h :snomagic" */
@ExCommand(command = "sno[magic]")
data class SubstituteNoMagicCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  MagicSubstituteCommand(range, modifier, argument, "\\M")
