/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.newapi

import com.maddyhome.idea.vim.api.VimTimer
import com.maddyhome.idea.vim.api.VimTimerService
import javax.swing.Timer

/**
 * Backed by [javax.swing.Timer], so the callback fires on the EDT - which is what the mapping
 * timeout handler has always relied on.
 */
internal class IjVimTimer(delayMillis: Int) : VimTimer {
  private val timer = Timer(delayMillis, null).apply { isRepeats = false }

  override val isRunning: Boolean
    get() = timer.isRunning

  override val delayMillis: Int
    get() = timer.initialDelay

  override fun start(delayMillis: Int, action: () -> Unit) {
    stop()
    timer.initialDelay = delayMillis
    timer.delay = delayMillis
    timer.addActionListener { action() }
    timer.start()
  }

  override fun stop() {
    timer.stop()
    // The listener captures the previous action, so it must go with it - otherwise a restarted
    // timer would fire every action it had ever been given.
    timer.actionListeners.forEach { timer.removeActionListener(it) }
  }
}

internal class IjVimTimerService : VimTimerService {
  override fun createOneShotTimer(delayMillis: Int): VimTimer = IjVimTimer(delayMillis)
}
