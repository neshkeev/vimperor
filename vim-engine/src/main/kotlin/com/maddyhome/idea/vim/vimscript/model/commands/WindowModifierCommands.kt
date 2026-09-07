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
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * The command modifiers that say something about the window a command is about to make.
 *
 * Vim has seven of them and they divide cleanly in two. `:vertical` and `:horizontal` name an
 * *axis*, and an axis is something both hosts have: a split is either beside the window or below
 * it, and `:resize` either sets a width or a height. Those two are implemented, through
 * [com.maddyhome.idea.vim.group.VimWindowGroup.verticalModifier].
 *
 * The other five name a *place* - `:topleft`, `:botright`, `:aboveleft`, `:belowright` and `:tab` -
 * and neither host lets an extension choose one. IntelliJ splits the window that has focus and
 * VS Code opens a group beside or below the active one, and there is no API on either side that
 * takes "put it at the very top". So those five run the command and let the host put the window
 * where it puts windows.
 *
 * That is worth having rather than reporting `E492`. `botright split` in a config or a mapping is
 * asking for a split, mostly, and a preference about where; refusing the whole line loses the split
 * as well as the preference. What is lost is written down here rather than hidden.
 */
sealed class WindowPlacementCommand(range: Range, modifier: CommandModifier, argument: String) :
  ModifierCommand(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = runModified(editor, context)
}

/**
 * see "h :vertical"
 *
 * Makes the following command work on the vertical axis: `:vertical split` splits beside rather
 * than below, and `:vertical resize 30` sets a width where a bare `:resize 30` sets a height.
 */
@ExCommand(command = "vert[ical]")
data class VerticalCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  ModifierCommand(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = withVerticalModifier(true) { runModified(editor, context) }
}

/** see "h :horizontal" - [VerticalCommand] the other way round. */
@ExCommand(command = "hor[izontal]")
data class HorizontalCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  ModifierCommand(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult = withVerticalModifier(false) { runModified(editor, context) }
}

private inline fun withVerticalModifier(vertical: Boolean, body: () -> ExecutionResult): ExecutionResult {
  val previous = injector.window.verticalModifier
  injector.window.verticalModifier = vertical
  try {
    return body()
  } finally {
    injector.window.verticalModifier = previous
  }
}

/** see "h :topleft" - the placement is the host's; see [WindowPlacementCommand]. */
@ExCommand(command = "to[pleft]")
data class TopLeftCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  WindowPlacementCommand(range, modifier, argument)

/** see "h :botright" - the placement is the host's; see [WindowPlacementCommand]. */
@ExCommand(command = "bo[tright]")
data class BotRightCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  WindowPlacementCommand(range, modifier, argument)

/** see "h :aboveleft" - the placement is the host's; see [WindowPlacementCommand]. */
@ExCommand(command = "abo[veleft],lefta[bove]")
data class AboveLeftCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  WindowPlacementCommand(range, modifier, argument)

/** see "h :belowright" - the placement is the host's; see [WindowPlacementCommand]. */
@ExCommand(command = "bel[owright],rightb[elow]")
data class BelowRightCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  WindowPlacementCommand(range, modifier, argument)

/**
 * see "h :tab"
 *
 * "Open a new tab page for the command that follows" - which is a placement like the four above,
 * and one neither host takes as an instruction: a command that opens something opens it where the
 * host opens things. `:tabnew` and `:tabedit` are how you ask for a tab here.
 */
@ExCommand(command = "tab")
data class TabModifierCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  WindowPlacementCommand(range, modifier, argument)
