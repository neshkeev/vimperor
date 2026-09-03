/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.commands.Command
import com.maddyhome.idea.vim.vimscript.model.commands.CommandModifier
import com.maddyhome.idea.vim.vimscript.model.commands.ExCommandProvider
import com.maddyhome.idea.vim.vimscript.model.commands.LazyExCommandInstance

/**
 * The ex commands this host adds to the engine's.
 *
 * The names carry no `[...]` unless Vim abbreviates them, so each is registered as Vim spells it
 * and nothing shorter resolves to it by accident. `:t` is Vim's `:copy`, and an abbreviation of
 * `:tutor` would have been a poor trade for a command nobody types twice.
 */
internal object VsCodeExCommandProvider : ExCommandProvider {
  override fun getCommands(): Map<String, LazyExCommandInstance> = mapOf(
    "vimtutor,tutor,vimperortutor" to LazyExCommandInstance(
      TutorCommand::class,
      { range, modifier, argument -> TutorCommand(range, modifier, argument) },
    ),
    // `actionl[ist]`, spelled the way IdeaVim spells it, so `:actionl` is enough and `:action`
    // stays the engine's. See [ActionListCommand].
    "actionl[ist]" to LazyExCommandInstance(
      ActionListCommand::class,
      { range, modifier, argument -> ActionListCommand(range, modifier, argument) },
    ),
    "setf[iletype]" to command { range, modifier, argument -> SetFiletypeCommand(range, modifier, argument) },

    // The rest of what a `~/.vimrc` reaches for and VS Code answers for itself. Measured rather
    // than guessed at: every name the engine registers was typed at the prompt, and these are the
    // ones that came back `E492` and turn up in real configuration.
    //
    // Nothing here may name a command the engine implements. This provider is registered *after*
    // the engine's, so a name in both silently replaces the real command with an accepted no-op -
    // which is exactly what happened to eight of them, and what `ExCommandOverlapTest` now
    // prevents.
    "menu" to command { range, modifier, argument -> AcceptedHostCommand(range, modifier, argument) },
    "unme[nu]" to command { range, modifier, argument -> AcceptedHostCommand(range, modifier, argument) },
  )

  private inline fun <reified T : Command> command(
    noinline factory: (Range, CommandModifier, String) -> T,
  ) = LazyExCommandInstance(T::class, factory)
}

/**
 * A command VS Code has already answered, which exists so that a `~/.vimrc` loads.
 *
 * Most of this family moved into the engine, where both hosts get it - see `EditorDecidesCommand`
 * there, which carries the argument for why these accept quietly. What is left here is the handful
 * whose answer is genuinely VS Code's alone: a menu it does not have, and a language mode that is
 * one setting rather than Vim's two.
 */
internal abstract class AcceptedCommand(
  range: Range,
  modifier: CommandModifier,
  private val argumentText: String,
) : Command.SingleExecution(range, modifier, argumentText) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  /** What to say when the argument asks for something this editor will not do. Null to stay quiet. */
  protected open fun contradiction(argument: String): String? = null

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    contradiction(argumentText.trim())?.let { injector.messages.showMessage(editor, it) }
    return ExecutionResult.Success
  }
}





/**
 * `:setfiletype {name}`, which is `:setlocal filetype={name}` with one difference Vim cares about.
 *
 * Vim's version does nothing if the filetype has already been set, so a `FileType` autocommand that
 * guesses cannot override one the user chose. There is nothing here yet that sets it behind the
 * user's back, so this is the plain form, and the rule is written down rather than silently not
 * implemented.
 */
internal class SetFiletypeCommand(range: Range, modifier: CommandModifier, private val name: String) :
  Command.SingleExecution(range, modifier, name) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_REQUIRED, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    injector.optionGroup.setOptionValue(
      VsCodeOptions.filetype,
      OptionAccessScope.LOCAL(editor),
      VimString(name.trim()),
    )
    applyLanguage(editor)
    return ExecutionResult.Success
  }
}

/** One of the commands VS Code answers for itself. See [AcceptedCommand]. */
internal class AcceptedHostCommand(range: Range, modifier: CommandModifier, argument: String) :
  AcceptedCommand(range, modifier, argument)

