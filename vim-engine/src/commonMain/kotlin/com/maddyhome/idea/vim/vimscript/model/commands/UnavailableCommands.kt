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
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * The commands Vim only has when it was built with the feature, and neither host was.
 *
 * Vim's own answer for these is `E319: Sorry, the command is not available in this version`, which
 * is what a `vim` without `+python3` says to `:python`. It is a better answer than `E492: Not an
 * editor command`, because the two mean different things to whoever is reading the message: `E492`
 * says "no such command, check your spelling" and `E319` says "that command, and this build does
 * not have it". A config that guards with `has('python3')` never reaches either; a config that does
 * not gets told what is actually wrong.
 *
 * The line these are on the far side of is *not* "hard to do". It is "the thing itself is not here":
 * there is no embedded interpreter, no terminal to suspend to, no GUI window to move, no swap file
 * to recover from, and no session file. A command that could be written and has not been belongs on
 * the other side of that line and keeps reporting `E492`, because `E319` would be a claim about the
 * host rather than about the work.
 */
sealed class UnavailableCommand(range: Range, modifier: CommandModifier, argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = throw exExceptionMessage("E319")
}

// The language interfaces. Vim embeds an interpreter; this engine embeds none, and neither host
// offers one to an extension - IntelliJ's scripting and VS Code's are for the IDE, not for a buffer.

/** see "h :python" */
@ExCommand(command = "py[thon]")
data class PythonCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :python3" */
@ExCommand(command = "py3")
data class Python3Command(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :perl" */
@ExCommand(command = "pe[rl]")
data class PerlCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :ruby" */
@ExCommand(command = "rub[y]")
data class RubyCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :lua" */
@ExCommand(command = "lua")
data class LuaCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :tcl" */
@ExCommand(command = "tc[l]")
data class TclCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

// The GUI. Vim's own window is a thing it owns and can move, print from, and hang menus off. Here
// the window belongs to the IDE, and an extension is a guest in one of its editors.

/** see "h :gui" */
@ExCommand(command = "gu[i]")
data class GuiCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :gvim" */
@ExCommand(command = "gv[im]")
data class GvimCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :hardcopy" - Vim prints the buffer itself; neither host lets an extension reach a printer. */
@ExCommand(command = "ha[rdcopy]")
data class HardcopyCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :tearoff" */
@ExCommand(command = "tearo[ff]")
data class TearOffCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :tmenu" - a tooltip on a GUI menu item, of which there are none. */
@ExCommand(command = "tm[enu]")
data class TipMenuCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :tunmenu" */
@ExCommand(command = "tu[nmenu]")
data class TipUnmenuCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :winpos" - the position of Vim's own window on the screen. */
@ExCommand(command = "winp[os]")
data class WinPosCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :winsize" - the size of Vim's own window, in characters. */
@ExCommand(command = "win[size]")
data class WinSizeCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :options" - Vim's option window, which is a buffer full of Vim's own help. */
@ExCommand(command = "opt[ions]")
data class OptionsWindowCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :intro" - Vim's splash screen. */
@ExCommand(command = "int[ro]")
data class IntroCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :exusage" - Vim's own summary of ex commands. */
@ExCommand(command = "exu[sage]")
data class ExUsageCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :viusage" - Vim's own summary of normal-mode commands. */
@ExCommand(command = "viu[sage]")
data class ViUsageCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

// The terminal. Vim is a process in a shell and can hand it back; an IDE plugin is not.

/** see "h :stop" */
@ExCommand(command = "st[op]")
data class StopCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :suspend" */
@ExCommand(command = "sus[pend]")
data class SuspendCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :open" - Vim's open mode, a one-line editor for terminals that cannot do better. */
@ExCommand(command = "o[pen]")
data class OpenModeCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

// The files Vim keeps beside a buffer. None of them exists here: there is no swap file, no viminfo,
// no undo file, and no session - and no host API that could produce one that meant anything.

/** see "h :recover" - recovers a buffer from its swap file, and there are no swap files. */
@ExCommand(command = "rec[over]")
data class RecoverCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :rviminfo" */
@ExCommand(command = "rv[iminfo]")
data class ReadViminfoCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :wviminfo" */
@ExCommand(command = "wv[iminfo]")
data class WriteViminfoCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :rundo" */
@ExCommand(command = "rund[o]")
data class ReadUndoCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :wundo" */
@ExCommand(command = "wu[ndo]")
data class WriteUndoCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/**
 * see "h :undolist"
 *
 * Prints Vim's undo *tree* - the branches you get by undoing and then typing something new. Both
 * hosts keep a line rather than a tree, and neither offers a way to read it back, so there is
 * nothing to print. `:earlier` and `:later` walk that line, which is the part of the feature a
 * linear history can carry.
 */
@ExCommand(command = "undol[ist]")
data class UndoListCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/**
 * see "h :mksession"
 *
 * Writes a script that recreates the windows, tabs and their layout. Neither host lets an extension
 * read that layout, let alone rebuild it: `:wincmd` can move between editor groups and cannot ask
 * how they are arranged.
 */
@ExCommand(command = "mks[ession]")
data class MakeSessionCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :breakadd" - Vim's Vimscript debugger, which this engine does not have. */
@ExCommand(command = "breaka[dd]")
data class BreakAddCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)
