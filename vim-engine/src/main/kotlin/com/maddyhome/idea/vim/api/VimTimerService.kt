/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

/**
 * A one-shot timer, used for the `'timeoutlen'` wait on an unfinished mapping sequence.
 *
 * The host supplies this because the callback must run wherever that host requires editor work to
 * happen - the IntelliJ implementation fires on the EDT, matching the `javax.swing.Timer` this
 * replaced. Firing it on the wrong thread would not fail here; it would fail somewhere inside the
 * mapping timeout handler.
 */
interface VimTimer {
  /**
   * (Re)starts the timer. [action] runs once, [delayMillis] from now, unless [stop] is called
   * first. Starting an already running timer restarts it with the new delay.
   */
  fun start(delayMillis: Int, action: () -> Unit)

  /** Cancels a pending fire. Doing this to a stopped timer is not an error. */
  fun stop()

  /** True between [start] and either the fire or [stop]. */
  val isRunning: Boolean

  /** The delay most recently passed to [start], or the value the timer was created with. */
  val delayMillis: Int
}

interface VimTimerService {
  fun createOneShotTimer(delayMillis: Int): VimTimer
}
