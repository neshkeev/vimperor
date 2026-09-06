/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.diff
import com.maddyhome.idea.vim.annotations.TestOnly

/**
 * Which files have been marked for diffing, per project.
 *
 * The whole of the engine's share of diff mode, and it is small on purpose. Vim's diff mode is a
 * property of *windows* - two windows in diff mode are compared to each other, and `'diffopt'`, the
 * folds and the colours all follow. Neither host has a window mode to turn on. What both have is a
 * diff *view*: a thing you open over two files, which then owns its own scrolling, folding and
 * highlighting and needs nothing further from an extension.
 *
 * So `:diffthis` cannot switch anything on; what it can do is remember that this file is one of the
 * two, and open the view when a second one is named. That is the same journey with the same two
 * files at the end of it, and it is why this holds a file rather than a flag.
 */
object Diff {

  private val marked = mutableMapOf<String, String>()

  /**
   * Marks [path] as one side of a diff, and returns the other side when there is one.
   *
   * The first `:diffthis` has nothing to compare against and returns null - which is not a failure,
   * it is the first half of the command. The second returns the first file and forgets it, so a
   * third `:diffthis` starts a new pair rather than joining the finished one.
   */
  fun mark(projectId: String, path: String): String? {
    val other = marked[projectId]
    if (other == null || other == path) {
      marked[projectId] = path
      return null
    }
    marked.remove(projectId)
    return other
  }

  /** Whether this project has a file waiting for its pair. `:diffoff` asks, to know what to say. */
  fun isWaiting(projectId: String): Boolean = projectId in marked

  fun clear(projectId: String) {
    marked.remove(projectId)
  }

  @TestOnly
  fun reset() {
    marked.clear()
  }
}
