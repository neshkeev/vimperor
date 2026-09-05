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
}

/** A [VimApplication.schedule] request that has not run yet, or that has. */
fun interface ScheduledTask {
  fun cancel()

  companion object {
    /** For a host that cannot schedule anything; nothing is pending, so nothing is cancelled. */
    val NONE: ScheduledTask = ScheduledTask {}
  }
}
