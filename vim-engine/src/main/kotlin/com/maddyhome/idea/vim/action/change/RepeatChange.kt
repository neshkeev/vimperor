/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.action.change

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.Command
import com.maddyhome.idea.vim.command.OperatorArguments

/**
 * `.` - do the last change again.
 *
 * Two kinds of last change, and the second is easy to leave out. Usually the engine kept the change
 * as a [Command] and repeating it means handing that command back to the action executor. But a
 * mapping installed by an extension is not a command: `ysiw)` is `surround`'s handler, and the way
 * to repeat it is to run **the handler** again, with the keys it read the first time played back to
 * it out of [Extension]. Both hosts had this written out separately and this host's copy had only
 * the first branch, so `ysiw)l.` surrounded once and the `.` did nothing at all - eleven of
 * IdeaVim's fixtures, from `surround` and `vim-exchange`.
 *
 * Everything around the choice is bookkeeping that has to be put back afterwards: the register the
 * change used, the last `f`/`t` search, and the flag several handlers read to know they are being
 * repeated and must not ask the user for input a second time.
 *
 * [inOneUndoStep] is the one thing that differs between hosts and it is not the grouping itself -
 * `injector.actionExecutor.executeCommand` is IntelliJ's `CommandProcessor` and a plain call here.
 * It is IdeaVim's split-mode undo marker, which is an RPC to a backend this host does not have.
 */
fun repeatLastChange(
  editor: VimEditor,
  context: ExecutionContext,
  cmd: Command,
  operatorArguments: OperatorArguments,
  inOneUndoStep: (() -> Unit) -> Unit = { it() },
): Boolean {
  val state = injector.vimState
  val lastCommand = VimRepeater.lastChangeCommand
  val lastHandler = Extension.lastExtensionHandler
  if (lastCommand == null && lastHandler == null) return false

  val save = state.executingCommand
  val lastFTCmd = injector.motion.lastFTCmd
  val lastFTChar = injector.motion.lastFTChar
  val register = injector.registerGroup.currentRegister
  val repeatHandler = VimRepeater.repeatHandler

  state.isDotRepeatInProgress = true
  try {
    inOneUndoStep {
      // The redo-register feature: repeating a change that came from a numbered register walks to
      // the next one, so `"1p....` pastes the last five deletes in turn. See `:h redo-register`.
      if (VimRepeater.lastChangeRegister in '1'..'8') {
        VimRepeater.lastChangeRegister = VimRepeater.lastChangeRegister.inc()
      }
      injector.registerGroup.selectRegister(VimRepeater.lastChangeRegister)

      if (repeatHandler && lastHandler != null) {
        injector.actionExecutor.executeCommand(
          editor,
          { lastHandler.execute(editor, context, operatorArguments) },
          "Vim " + lastHandler.toString(),
          null,
        )
      } else if (!repeatHandler && lastCommand != null) {
        // A count on the `.` itself replaces the count the original change carried, rather than
        // multiplying it: `3dw` then `2.` deletes two words, not six.
        val repeated = if (cmd.rawCount > 0) lastCommand.copy(rawCount = cmd.rawCount) else lastCommand
        state.executingCommand = repeated

        val arguments = operatorArguments.copy(count0 = repeated.rawCount)
        injector.actionExecutor.executeVimAction(editor, repeated.action, context, arguments)

        VimRepeater.saveLastChange(repeated)
      }
    }
  } finally {
    state.isDotRepeatInProgress = false
    if (save != null) state.executingCommand = save
    injector.motion.setLastFTCmd(lastFTCmd, lastFTChar)
    // The handler survives its own repeat, so that `.` after `.` repeats it again rather than
    // falling back to whatever command ran in between.
    if (lastHandler != null) Extension.lastExtensionHandler = lastHandler
    VimRepeater.repeatHandler = repeatHandler
    // The keys the handler read are played back from the start next time, not consumed once.
    Extension.reset()
    injector.registerGroup.selectRegister(register)
  }
  return true
}
