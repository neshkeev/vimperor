/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

import com.maddyhome.idea.vim.key.VimKeyStroke


interface VimApplication {
  fun isMainThread(): Boolean
  fun invokeLater(editor: VimEditor, action: () -> Unit)
  fun invokeLater(action: () -> Unit)
  fun invokeAndWait(action: () -> Unit)
  fun isUnitTest(): Boolean
  fun isInternal(): Boolean
  fun postKey(stroke: VimKeyStroke, editor: VimEditor)

  fun <T> runWriteAction(action: () -> T): T
  fun <T> runReadAction(action: () -> T): T

  fun currentStackTrace(): String
  fun runAfterGotFocus(runnable: () -> Unit)

  /**
   * Runs [action] after [delayMillis], on whatever thread the host does its editing on.
   *
   * The one thing an extension cannot do for itself and every host already has: IntelliJ's `Alarm`,
   * VS Code's `setTimeout`. `highlightedyank` is what needed it first - a flash that goes away is a
   * flash and a timer, and without the timer it is just a highlight - and `'timeoutlen'`-shaped
   * work and Vim's own `timer_start()` want the same thing.
   *
   * The returned handle cancels it. Cancelling one that has already run is not an error, because
   * the caller has no way to know: `highlightedyank` cancels the pending fade every time it
   * highlights, whether or not the last one is still pending.
   */
  fun schedule(delayMillis: Int, action: () -> Unit): ScheduledTask

  /**
   * Runs [action] once the host has finished whatever it is doing to the document.
   *
   * Some of what Vim does, only the host can do: undo and redo, reformatting, a `<Action>` mapping.
   * IntelliJ does all of them in the call, so the next statement already sees the result. VS Code
   * does none of them in the call - each is a command it runs for itself, and the engine gets a
   * promise it cannot wait on, because the runtime has one thread and blocking it would stop the
   * command from ever finishing.
   *
   * That is not a problem while the caller is a keystroke: the host holds the user's keys and
   * everything resumes in order. It is a problem when one *statement* depends on the previous one
   * having landed, which is what an extension writing `u` and then re-pasting is doing. This is the
   * seam for that: the work that has to see the new document goes in [action], and the host runs it
   * when the document is back in step - immediately, if it never went out of step.
   *
   * Two things a caller has to know. Whatever [action] closes over is restored *after* it runs, not
   * after the function returns, so a `try`/`finally` around the call restores too early - see
   * `undoAndRepaste`, which had to move its register save inside. And the host has already re-read
   * the buffer by then, so [action] must not cache offsets from before it.
   */
  fun runAfterHostCatchesUp(action: () -> Unit)
}

/** A [VimApplication.schedule] request that has not run yet, or that has. */
fun interface ScheduledTask {
  fun cancel()

  companion object {
    /** For a host that cannot schedule anything; nothing is pending, so nothing is cancelled. */
    val NONE: ScheduledTask = ScheduledTask {}
  }
}
