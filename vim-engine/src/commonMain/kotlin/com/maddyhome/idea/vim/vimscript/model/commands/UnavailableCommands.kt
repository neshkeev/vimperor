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

// The preview window. Vim keeps a small window at the top showing a definition without leaving
// where you are, and neither host has one: a VS Code peek and an IntelliJ quick-documentation popup
// are the IDE's own, opened by the IDE, and not a window an extension can put a buffer in.

/** see "h :pclose" */
@ExCommand(command = "pc[lose]")
data class PreviewCloseCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :pedit" */
@ExCommand(command = "ped[it]")
data class PreviewEditCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/**
 * The tag commands that open the preview window rather than jumping.
 *
 * The tags themselves are read and jumped to - see [TagCommand] - so what is missing here is only
 * the window: `:ptag` is `:tag` into a preview window, and there is no preview window. That is the
 * same absence `:pclose` above reports, which is why these answer the same way rather than
 * quietly behaving like their non-preview twins - a `:ptag` that moved the caret would have taken
 * the reader away from the place `:ptag` exists to keep them at.
 */

/**
 * `:language`, which Vim only has when it was built with `+multi_lang`.
 *
 * The subject is a locale: Vim's `:language` sets the one its own messages, its `strftime` and its
 * character classes come from. This engine has no message catalogue to switch and neither host will
 * let an extension change the process locale - IntelliJ's language pack and VS Code's display
 * language are settings the IDE reads at startup, not something a buffer can ask for. So this is a
 * build without the feature rather than a command nobody has written.
 */
@ExCommand(command = "lan[guage]")
data class LanguageCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :ptag" */
@ExCommand(command = "pt[ag]")
data class PreviewTagCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :ptselect" */
@ExCommand(command = "pts[elect]")
data class PreviewTagSelectCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :ptjump" */
@ExCommand(command = "ptj[ump]")
data class PreviewTagJumpCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :ptnext" */
@ExCommand(command = "ptn[ext]")
data class PreviewTagNextCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :ptprevious" */
@ExCommand(command = "ptp[revious],ptN[ext]")
data class PreviewTagPreviousCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :ptfirst" */
@ExCommand(command = "ptf[irst],ptr[ewind]")
data class PreviewTagFirstCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :ptlast" */
@ExCommand(command = "ptl[ast]")
data class PreviewTagLastCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :psearch" */
@ExCommand(command = "ps[earch]")
data class PreviewSearchCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

// `'include'` and `'path'`. Vim follows `#include` lines through a search path to find a definition;
// this engine has neither option and neither host would use them if it did, since both index the
// project themselves and answer "go to definition" from that.

/** see "h :isearch" */
@ExCommand(command = "is[earch]")
data class IncludeSearchCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :ijump" */
@ExCommand(command = "ij[ump]")
data class IncludeJumpCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :ilist" */
@ExCommand(command = "il[ist]")
data class IncludeListCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/** see "h :isplit" */
@ExCommand(command = "isp[lit]")
data class IncludeSplitCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

// The rest, each for a reason of its own.

/**
 * see "h :ownsyntax"
 *
 * Sets the syntax for *this window* while the buffer keeps its own, so the same file can be shown
 * two ways at once. Both hosts hang the language off the document, and every view of a document
 * shows it the same way; `:setlocal syntax=` is the whole of what they can do.
 */
@ExCommand(command = "ow[nsyntax]")
data class OwnSyntaxCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/**
 * see "h :compiler"
 *
 * Reads `compiler/{name}.vim` out of the runtime directory and sets `'makeprg'` and
 * `'errorformat'` from it. There is no runtime directory: `:runtime` is accepted and finds nothing,
 * and the plugin files Vim ships with are not here to be read. `:set makeprg=` is what to write
 * instead, and `:make` reads it.
 */
@ExCommand(command = "comp[iler]")
data class CompilerCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/**
 * see "h :helpgrep"
 *
 * Searches Vim's own help files, of which there are none here - `:help` opens the documentation in
 * a browser rather than in a buffer, so there is nothing on disk to grep.
 */
@ExCommand(command = "helpg[rep]")
data class HelpGrepCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/**
 * see "h :cquit"
 *
 * Quits Vim with a non-zero exit code, which is how `git commit` learns that you changed your mind.
 * Neither host is a process you leave with a status: closing an editor tells the IDE nothing, and
 * the IDE is not what launched you.
 */
@ExCommand(command = "cq[uit]")
data class CQuitCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)

/**
 * see "h :trust"
 *
 * Vim 9's list of files it will source without asking, which is what makes a project-local `.vimrc`
 * safe. This fork reads one config, from one place the user chose; there is no list to add to.
 */
@ExCommand(command = "trus[t]")
data class TrustCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  UnavailableCommand(range, modifier, argument)
