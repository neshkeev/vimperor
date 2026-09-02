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
    "sy[ntax]" to command { range, modifier, argument -> SyntaxCommand(range, modifier, argument) },
    "filet[ype]" to command { range, modifier, argument -> FiletypeCommand(range, modifier, argument) },
    "colo[rscheme]" to command { range, modifier, argument -> ColorschemeCommand(range, modifier, argument) },
    "hi[ghlight]" to command { range, modifier, argument -> HighlightCommand(range, modifier, argument) },
    "ru[ntime]" to command { range, modifier, argument -> RuntimeCommand(range, modifier, argument) },
    "scriptencoding" to command { range, modifier, argument -> ScriptEncodingCommand(range, modifier, argument) },
    "lan[guage]" to command { range, modifier, argument -> LanguageCommand(range, modifier, argument) },
    "behave" to command { range, modifier, argument -> BehaveCommand(range, modifier, argument) },
    "setf[iletype]" to command { range, modifier, argument -> SetFiletypeCommand(range, modifier, argument) },

    // The rest of what a `~/.vimrc` reaches for and VS Code answers for itself. Measured rather
    // than guessed at: every name the engine registers was typed at the prompt, and these are the
    // ones that came back `E492` and turn up in real configuration.
    "packl[oadall]" to command { range, modifier, argument -> AcceptedHostCommand(range, modifier, argument) },
    "scrip[tnames]" to command { range, modifier, argument -> AcceptedHostCommand(range, modifier, argument) },
    "mes[sages]" to command { range, modifier, argument -> AcceptedHostCommand(range, modifier, argument) },
    "redi[r]" to command { range, modifier, argument -> AcceptedHostCommand(range, modifier, argument) },
    "mkvie[w]" to command { range, modifier, argument -> AcceptedHostCommand(range, modifier, argument) },
    "loadv[iew]" to command { range, modifier, argument -> AcceptedHostCommand(range, modifier, argument) },
    "sign" to command { range, modifier, argument -> AcceptedHostCommand(range, modifier, argument) },
    "prof[ile]" to command { range, modifier, argument -> AcceptedHostCommand(range, modifier, argument) },
    "menu" to command { range, modifier, argument -> AcceptedHostCommand(range, modifier, argument) },
    "unme[nu]" to command { range, modifier, argument -> AcceptedHostCommand(range, modifier, argument) },
    "difft[his]" to command { range, modifier, argument -> AcceptedHostCommand(range, modifier, argument) },
    "diffo[ff]" to command { range, modifier, argument -> AcceptedHostCommand(range, modifier, argument) },
    "lc[d]" to command { range, modifier, argument -> WorkingDirectoryCommand(range, modifier, argument) },
    "cd" to command { range, modifier, argument -> WorkingDirectoryCommand(range, modifier, argument) },
  )

  private inline fun <reified T : Command> command(
    noinline factory: (Range, CommandModifier, String) -> T,
  ) = LazyExCommandInstance(T::class, factory)
}

/**
 * A command VS Code has already answered, which exists so that a `~/.vimrc` loads.
 *
 * Every one of these names something the editor decides for itself: what highlighting looks like,
 * which colours are used, which files get which language, where a plugin's files are. There is
 * nothing for the command to do, and there is a great deal for it to *not* do - a `~/.vimrc`
 * sourced from an `.ideavimrc` is full of them, and an unknown command is `E492`, one line of red
 * per line of config.
 *
 * Saying nothing is deliberate and it is only half the story. [contradiction] is the other half:
 * `:syntax off` and `:filetype off` ask for something VS Code will not do, and those are worth a
 * word precisely because they are rare. A message on every `syntax on` would be the wall of errors
 * again in a different colour.
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
 * `:syntax`, which is on and cannot be turned off.
 *
 * VS Code highlights with a grammar and a language server and offers an extension no way to stop
 * it for one editor. `syntax on` is the line this was written for: it is in most `~/.vimrc` files
 * and it was `E492: Not an editor command`.
 */
internal class SyntaxCommand(range: Range, modifier: CommandModifier, argument: String) :
  AcceptedCommand(range, modifier, argument) {
  override fun contradiction(argument: String): String? =
    if (argument == "off" || argument == "clear") {
      "VS Code highlights with its own grammars and cannot be told to stop, so `:syntax $argument` did nothing."
    } else {
      null
    }
}

/** `:filetype`, which VS Code decides from the file and its language extensions. */
internal class FiletypeCommand(range: Range, modifier: CommandModifier, argument: String) :
  AcceptedCommand(range, modifier, argument) {
  override fun contradiction(argument: String): String? =
    if (argument.split(" ").any { it == "off" }) {
      "VS Code works out a file's language itself, so `:filetype $argument` did nothing."
    } else {
      null
    }
}

/** `:colorscheme`, which is VS Code's colour theme and a user setting rather than a buffer's. */
internal class ColorschemeCommand(range: Range, modifier: CommandModifier, argument: String) :
  AcceptedCommand(range, modifier, argument) {
  override fun contradiction(argument: String): String? =
    if (argument.isEmpty()) {
      null
    } else {
      "VS Code's colour theme is a setting rather than a buffer's, so `:colorscheme $argument` did nothing. " +
        "The Command Palette changes it: Preferences: Color Theme."
    }
}

/** `:highlight`, which is the theme's business for the same reason. */
internal class HighlightCommand(range: Range, modifier: CommandModifier, argument: String) :
  AcceptedCommand(range, modifier, argument)

/** `:runtime`, which loads Vim script files this host cannot run. */
internal class RuntimeCommand(range: Range, modifier: CommandModifier, argument: String) :
  AcceptedCommand(range, modifier, argument)

/** `:scriptencoding`, which says how the file being sourced is encoded. Node reads UTF-8. */
internal class ScriptEncodingCommand(range: Range, modifier: CommandModifier, argument: String) :
  AcceptedCommand(range, modifier, argument)

/** `:language`, which sets a terminal Vim's locale. */
internal class LanguageCommand(range: Range, modifier: CommandModifier, argument: String) :
  AcceptedCommand(range, modifier, argument)

/** `:behave`, which chooses between `mswin` and `xterm` mouse and selection behaviour. */
internal class BehaveCommand(range: Range, modifier: CommandModifier, argument: String) :
  AcceptedCommand(range, modifier, argument)


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

/**
 * `:cd`, `:lcd` and their kind.
 *
 * VS Code's working directory is the workspace folder, which it opens and an extension does not
 * move. A window-local one, which is what `:lcd` asks for, has no counterpart at all.
 */
internal class WorkingDirectoryCommand(range: Range, modifier: CommandModifier, argument: String) :
  AcceptedCommand(range, modifier, argument) {
  override fun contradiction(argument: String): String? =
    if (argument.isEmpty()) {
      null
    } else {
      "VS Code's working directory is the workspace folder and an extension cannot move it, " +
        "so `:cd $argument` did nothing."
    }
}
