/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.cleverf

import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.helper.nanoTime

/**
 * The search started by [map] for [char] in an editor, and where it last left the primary caret.
 *
 * [map] is the key that started the search and defines its direction for the repeats. Until the first successful
 * jump, the position is where the search started.
 *
 * ## Two things this spells differently from IdeaVim
 *
 * **The editor is a path, not a reference.** IdeaVim keeps a `WeakReference<Editor>` and compares by identity, so
 * that a closed editor can be collected; here the editor's identity *is* a string - `scheme://path`, which
 * `VimEditor.getPath` answers and which `exchange` and `multiple-cursors` already key their state by. A string needs
 * no weak reference, and it survives the thing a reference cannot: this host hands out a new `VimEditor` wrapper
 * whenever a hidden tab is shown, so identity would have said "a different editor" about the same buffer.
 *
 * **The timeout is measured with a monotonic clock.** `g:clever_f_timeout_ms` is an elapsed interval, and
 * `currentTimeMillis` is wall-clock time that can jump backwards - the engine's own `Time.kt` says so and says to
 * use [nanoTime] for this. IdeaVim uses `System.currentTimeMillis()`; a clock change there can make a search that
 * has just happened look hours old, or one from hours ago look current.
 */
internal class SearchState(val map: Char, val char: Char, editor: VimEditor) {
  private var path = editor.getPath()
  private var offset = editor.primaryCaret().offset
  private var jumpTime = 0L
  private var hasJumped = false

  fun firstSearch(): CharSearch = CharSearch(this, map, char, isFirstMove = true)

  /**
   * By default, the direction of a repeat is relative to the original search: a lowercase key continues it and an
   * uppercase key reverses it. With `g:clever_f_fix_key_direction` a lowercase key always searches forward and an
   * uppercase key always backward.
   */
  fun repeatSearch(back: Boolean): CharSearch {
    val reverse = if (CleverFSettings.fixKeyDirection && map.isUpperCase()) !back else back
    val effectiveMap = if (reverse) map.swapCase() else map
    return CharSearch(this, effectiveMap, char, isFirstMove = !hasJumped)
  }

  fun canRepeat(editor: VimEditor): Boolean =
    hasJumped && !isTimedOut() && isAt(editor, editor.primaryCaret().offset)

  fun isAt(editor: VimEditor, offset: Int): Boolean = isIn(editor) && this.offset == offset

  fun isIn(editor: VimEditor): Boolean = path == editor.getPath()

  fun recordJump(editor: VimEditor, offset: Int) {
    path = editor.getPath()
    this.offset = offset
    jumpTime = nanoTime()
    hasJumped = true
  }

  private fun isTimedOut(): Boolean {
    val timeout = CleverFSettings.timeoutMs
    return timeout > 0 && (nanoTime() - jumpTime) / 1_000_000 > timeout
  }

  private fun Char.swapCase(): Char = if (isUpperCase()) lowercaseChar() else uppercaseChar()
}
