/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.action.change

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.Command
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.key.KeySource

/**
 * `.` - do the last change again, by feeding its keys back through the key handler.
 *
 * Two kinds of last change, and the second is easy to leave out. The usual one is remembered as the
 * **keys that were typed for it** ([VimRedoBuffer]) and repeated by replaying them, the way `@`
 * replays a macro. But a mapping installed by an extension is not keys: `ysiw)` is `surround`'s
 * handler, and the way to repeat it is to run **the handler** again, with the keys it read the first
 * time played back to it out of [Extension]. Both hosts had this written out separately and this
 * host's copy had only the first branch, so `ysiw)l.` surrounded once and the `.` did nothing at all
 * - eleven of IdeaVim's fixtures, from `surround` and `vim-exchange`.
 *
 * ## Keys rather than a command, which is the change ported from IdeaVim (VIM-1153)
 *
 * This used to keep the last change as a [Command] and repeat it by handing that command back to the
 * action executor. The trouble with a command is that it is only *this* editor's half of the change:
 * anything the host contributed - an auto-closed bracket, a reformat on type, a closing tag renamed
 * to match - was not in it, so the repeat produced different text from the original. Replaying the
 * keys asks the host to do its part again, where the repeat is happening.
 *
 * It also deletes a pile of special cases. A count on the `.` replacing the original, `2d3w`
 * repeating as `6dw`, `"1p....` walking the numbered registers, `1v` reselecting an area the same
 * size as the last Visual one: all of those are rendering rules on the recorded keys now
 * ([VimRedoBuffer]), which is where Vim keeps them too (`prep_redo`).
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
  val lastHandler = Extension.lastExtensionHandler
  val repeatHandler = VimRepeater.repeatHandler
  val handlerToRun = lastHandler.takeIf { repeatHandler }
  if (handlerToRun == null && VimRedoBuffer.isEmpty) return false

  val save = state.executingCommand
  val lastFTCmd = injector.motion.lastFTCmd
  val lastFTChar = injector.motion.lastFTChar
  val register = injector.registerGroup.currentRegister

  try {
    inOneUndoStep {
      if (handlerToRun != null) {
        // The flag is for the handler branch only. A handler is a Kotlin callback being run a second
        // time, and several read this to know not to ask the user for input again. The keystroke
        // branch must *not* set it: the keys are the keys, and an insert session that knows it is
        // being repeated re-inserts its own text on top of the text the replayed keys just typed -
        // `cwbar<Esc>w.` came out as `bar bbarar`, with the second `bar` inserted a second time.
        state.isDotRepeatInProgress = true
        try {
          injector.actionExecutor.executeCommand(
            editor,
            { handlerToRun.execute(editor, context, operatorArguments) },
            "Vim " + handlerToRun.toString(),
            null,
          )
        } finally {
          state.isDotRepeatInProgress = false
        }
      } else {
        replayKeys(editor, context, cmd.rawCount)
      }
    }
  } finally {
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

/**
 * Feeds the keys of the last change back through the key handler.
 *
 * They go on the key stack and come off one at a time, which is what
 * [com.maddyhome.idea.vim.key.MappingInfo] does for the right-hand side of a mapping, and they are
 * fed as *typed* keys: what was recorded is what the user typed, so a change made through a mapping
 * runs the mapping again. Recording is frozen for the duration, so that a second `.` repeats the
 * same change rather than the replay of the first.
 */
private fun replayKeys(editor: VimEditor, context: ExecutionContext, rawCount: Int) {
  val keys = VimRedoBuffer.keysForReplay(rawCount)
  VimRedoBuffer.replaying {
    val keyHandler = KeyHandler.getInstance()
    keyHandler.keyStack.addKeys(keys)
    try {
      while (keyHandler.keyStack.hasStroke()) {
        val key = keyHandler.keyStack.feedStroke()
        keyHandler.handleKey(editor, key, KeySource.TYPED, context, keyHandler.keyHandlerState)
      }
    } finally {
      keyHandler.keyStack.removeFirst()
    }
  }
}
