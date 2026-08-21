/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.command

import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.VimTimer
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.diagnostic.trace
import com.maddyhome.idea.vim.diagnostic.vimLogger
import com.maddyhome.idea.vim.key.VimKeyStroke

class MappingState : Cloneable {
  // Map command depth. 0 - if it is not a map command. 1 - regular map command. 2+ - nested map commands
  private var mapDepth = 0

  @Deprecated("This function is created only for binary compatibility")
  fun getMappingMode(): MappingMode = MappingMode.NORMAL

  fun isExecutingMap(): Boolean {
    return mapDepth > 0
  }

  fun startMapExecution() {
    ++mapDepth
  }

  fun stopMapExecution() {
    --mapDepth
  }

  // TODO: This should probably return List<VimKeyStroke> to match the keys we're using in KeyMapping
  // Let's avoid creating temporary wrapper lists when we could use this list directly
  val keys: Iterable<VimKeyStroke>
    get() = keyList

  val hasKeys
    get() = keyList.isNotEmpty()

  private var timer: VimTimer = injector.timerService.createOneShotTimer(injector.globalOptions().timeoutlen)
  private var keyList = mutableListOf<VimKeyStroke>()

  fun startMappingTimer(action: () -> Unit) {
    timer.start(injector.globalOptions().timeoutlen, action)
  }

  fun stopMappingTimer() {
    LOG.trace { "Stop mapping timer" }
    timer.stop()
  }

  fun addKey(key: VimKeyStroke) {
    keyList.add(key)
  }

  fun detachKeys(): List<VimKeyStroke> {
    val currentKeys = keyList
    keyList = mutableListOf()
    return currentKeys
  }

  fun resetMappingSequence() {
    LOG.trace("Reset mapping sequence")
    stopMappingTimer()
    keyList.clear()
    // NOTE: We intentionally don't reset mapping mode here
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || this::class != other::class) return false

    other as MappingState

    if (mapDepth != other.mapDepth) return false
    // Compares the timer's observable state rather than the timer, which is what the VimTimer this
    // replaced compared on. Note that isRunning changes on its own when the timer fires, so two
    // otherwise identical states can stop being equal without either being touched - that was true
    // before this change too, and is left alone rather than quietly fixed here.
    if (timer.isRunning != other.timer.isRunning) return false
    if (timer.delayMillis != other.timer.delayMillis) return false
    if (keyList != other.keyList) return false

    return true
  }

  override fun hashCode(): Int {
    // The old VimTimer.hashCode() was a constant, so the timer contributed nothing; keeping it
    // out means equal states still hash equally despite isRunning moving on its own.
    var result = mapDepth
    result = 31 * result + keyList.hashCode()
    return result
  }

  public override fun clone(): MappingState {
    val result = MappingState()
    result.timer = timer
    result.mapDepth = mapDepth
    result.keyList = keyList.toMutableList()
    return result
  }

  override fun toString(): String {
    return "Map depth = $mapDepth, keys = ${injector.parser.toKeyNotation(keys.toList())}"
  }

  companion object {
    private val LOG = vimLogger<MappingState>()
  }
}
