/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.common

/**
 * Notified after a mark has been set, moved or removed.
 */
interface VimMarkListener : Listener {

  /**
   * @param markChar the mark that changed, or `null` when an unknown number of marks may have changed - a batch reset,
   * or an editor that has just been opened and whose marks have never been seen. A listener that only cares about some
   * of the marks still has to do the full work when this is `null`.
   */
  fun marksChanged(markChar: Char?)
}
