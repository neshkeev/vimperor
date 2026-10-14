/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.cleverf

import com.github.neshkeev.vimperor.extension.readKeys
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.state.mode.Mode

/**
 * Handles `<Plug>(clever-f-{map})`. Repeats the search of the current mode if the cursor is still where it left it,
 * in the direction implied by [map]. Otherwise asks for a character and starts a new search.
 *
 * ## The one thing this host had to do differently, and it needed no engine change
 *
 * `dfa` is inclusive and `dFa` is not, and that difference is what `f` is for. clever-f gets it by handing the
 * command builder a `MotionActionHandler` of its own *inside the keystroke* `d` is still part of - which it can do
 * because its character read blocks. Here the character arrives a keystroke later, through `readKeys`.
 *
 * It still works, and the reason is smaller than the first three explanations written for it. **A `readKeys`
 * callback runs inside the keystroke that delivered the character, and that keystroke has its own
 * `finishedCommandPreparation` still to come** - which runs any command builder that has just become ready. So
 * `addAction` from the callback is the whole of it: the operator's `d` is still pending, and the keystroke that
 * completed the motion is the one that runs the command.
 *
 * Two things that look load-bearing and are not, both measured in `DeferredExtensionMotionTest`:
 *
 *  - **[continueVimExecution] is a no-op here.** It reaches the same `finishedCommandPreparation`, which only
 *    executes a builder that is still `isReady`. It is called anyway, because it is [WithCallback]'s contract and
 *    costs a comparison.
 *  - **`myFun` would not have interfered either way.** That is `ToHandlerMappingInfo`'s caret-delta route, the one
 *    that gives `easymotion`'s jump to an operator as "where the caret went" - exclusive, which is what the triage
 *    predicted would make `dfa` behave as `dta`. It is skipped twice over: it is guarded by
 *    `!commandBuilder.hasCurrentCommandPartArgument()`, and nothing here has moved the caret when it runs, so all
 *    of its branches are skipped and it adds nothing. A plain [ExtensionHandler] reaches the operator just as well.
 *
 * [WithCallback] is kept because it is the honest declaration for a handler that finishes later, and because it is
 * what stops `myFun` reading a caret that has not moved for a reason it cannot see.
 */
internal class FindHandler(
  private val map: Char,
  private val session: CleverFSession,
  private val executor: SearchExecutor,
) : ExtensionHandler.WithCallback() {
  private val charReader = CharReader(session)

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    // Both remembered now: by the time a character is typed, the mode is a later keystroke's question and the count
    // belongs to a command the builder has moved on from.
    val underOperator = editor.mode is Mode.OP_PENDING
    val count = operatorArguments.count1

    val repeated = repeatedSearch(editor)
    if (repeated != null) {
      run(editor, repeated, count, underOperator)
      return
    }

    readKeys(
      editor,
      context,
      isComplete = { keys -> keys.isNotEmpty() },
      onCancel = { abandon(editor, underOperator) },
    ) { keys ->
      val char = charReader.charOf(keys.first())
      if (char == null) {
        abandon(editor, underOperator)
      } else {
        val search = session.startSearch(editor, map, char).firstSearch()
        // A change to the text ends the search, and a document listener is per document - so the search says which
        // one to watch. See [SearchFinalizer].
        session.finalizer?.watchDocument(editor)
        run(editor, search, count, underOperator)
      }
    }
  }

  /**
   * Runs the search, and continues the Vim execution when it became an operator's motion.
   *
   * The same on the repeat path, which costs nothing and is one branch fewer:  a second
   * `finishedCommandPreparation` is harmless, because it only executes a builder that is still `isReady` and the
   * first run clears it. No test reaches it, and that is worth knowing rather than fixing - an operator-pending
   * *repeat* needs the cursor still sitting where an operator-pending search left it, and the operator's own edit
   * is a text change, which ends the searches. Upstream keeps the same unreachable path.
   */
  private fun run(editor: VimEditor, search: CharSearch, count: Int, underOperator: Boolean) {
    if (executor.execute(editor, search, count, underOperator)) continueVimExecution()
  }

  /** While a macro runs, clever-f always asks for a character, so a recorded `fa` replays as `fa` */
  private fun repeatedSearch(editor: VimEditor): CharSearch? {
    if (injector.macro.isExecutingMacro) return null
    val state = session.stateFor(editor.mode)?.takeIf { it.canRepeat(editor) } ?: return null
    return state.repeatSearch(back = map.isUpperCase())
  }

  /**
   * `<Esc>` at the prompt, or a repeat-last-character key with nothing to repeat.
   *
   * Under an operator the prompt closing is not enough: the `d` is still waiting for a motion and would take the
   * next key typed as one. Same reasoning as `easymotion`'s `abandon`.
   */
  private fun abandon(editor: VimEditor, underOperator: Boolean) {
    if (underOperator) KeyHandler.getInstance().reset(editor)
  }
}
