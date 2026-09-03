/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.changelist

import com.maddyhome.idea.vim.annotations.TestOnly
import kotlin.math.abs

/**
 * The change list behind `g;`, `g,` and `:changes` - where this project was last edited.
 *
 * Index and merge semantics follow Neovim's `get_changelist` (`src/nvim/mark.c`) and
 * `changed_common` (`src/nvim/change.c`): after each recorded change the index sits past the end,
 * so the first `g;` lands on the newest entry rather than on the one before it.
 *
 * Engine state rather than a host service, which it was until this fork needed it twice. The
 * *recording* still cannot be shared - IntelliJ hears about a change through the platform's
 * `RecentPlacesListener`, which catches a refactoring the engine never saw, and VS Code has nothing
 * of the kind and records the `.` mark instead. See [fedByHost]. The list itself, the merge rule
 * and the walk are the same on both, and this is them.
 */
object VimChangeList {

  private val projectToChanges = mutableMapOf<String, MutableList<Change>>()
  private val projectToIndex = mutableMapOf<String, Int>()

  /**
   * Whether the host records changes itself, in which case the engine does not.
   *
   * IntelliJ sets it. Its `RecentPlacesListener` sees every change the IDE makes, the engine's
   * included, so the engine recording the `.` mark as well would be the same change arriving twice
   * - which the merge rule would mostly hide and not always. A host that leaves this false gets
   * Vim's own definition instead: the changelist is where the `.` mark has been.
   */
  var fedByHost: Boolean = false

  data class Change(
    val line: Int,
    val col: Int,
    val filepath: String,
    val protocol: String,
  )

  sealed interface MoveResult {
    object Empty : MoveResult
    object AtStart : MoveResult
    object AtEnd : MoveResult
    data class At(val change: Change) : MoveResult
  }

  fun addChange(projectId: String, change: Change) {
    val list = projectToChanges.getOrPut(projectId) { mutableListOf() }
    if (list.lastOrNull()?.shouldMergeWith(change) == true) {
      list[list.lastIndex] = change
    } else {
      list.add(change)
      if (list.size > CHANGE_LIST_LIMIT) list.removeAt(0)
    }
    projectToIndex[projectId] = list.size
  }

  fun goToChange(projectId: String, count: Int): MoveResult {
    val list = projectToChanges[projectId]
    if (list.isNullOrEmpty()) return MoveResult.Empty

    val current = projectToIndex.getOrPut(projectId) { list.size }
    val target = current + count

    if (target < 0 && current == 0) return MoveResult.AtStart
    if (target >= list.size && current == list.size - 1) return MoveResult.AtEnd

    val newIndex = target.coerceIn(0, list.size - 1)
    projectToIndex[projectId] = newIndex
    return MoveResult.At(list[newIndex])
  }

  /** Oldest first, which is the order `:changes` prints and numbers them in. */
  fun getChanges(projectId: String): List<Change> = projectToChanges[projectId].orEmpty()

  /** Where `g;` has walked to, as an index into [getChanges]; the size when nothing has. */
  fun getIndex(projectId: String): Int = projectToIndex[projectId] ?: getChanges(projectId).size

  private fun Change.shouldMergeWith(next: Change): Boolean =
    filepath == next.filepath &&
      line == next.line &&
      abs(col - next.col) < TEXTWIDTH_FALLBACK

  @TestOnly
  fun reset() {
    projectToChanges.clear()
    projectToIndex.clear()
  }

  private const val CHANGE_LIST_LIMIT = 100
  private const val TEXTWIDTH_FALLBACK = 79
}
