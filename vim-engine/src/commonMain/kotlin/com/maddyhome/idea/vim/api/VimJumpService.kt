/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

import com.maddyhome.idea.vim.mark.Jump
import com.maddyhome.idea.vim.annotations.TestOnly
import com.maddyhome.idea.vim.helper.currentTimeMillis

// todo should it be multicaret?
// todo docs
// todo it would be better to have some Vim scope for this purpose (p:), to store things project-wise like for buffers
/**
 * This service manages jump lists for different projects
 */
interface VimJumpService {
  /**
   * Timestamp (`currentTimeMillis()`) of the last Jump command <C-o>, <C-i>
   * it's a temporary sticky tape to avoid difficulties with Platform, which counts <C-o>, <C-i> as new jump locations
   * and messes up our jump list
   */
  var lastJumpTimeStamp: Long

  fun getJump(projectId: String, count: Int): Jump?
  fun getJumps(projectId: String): List<Jump>
  fun getJumpSpot(projectId: String): Int

  /**
   * True while `:keepjumps` runs a command, and [saveJumpLocation] records nothing while it is.
   *
   * Vim's `:keepjumps` is "the jumplist, the alternate file mark and the changelist are not
   * changed". [saveJumpLocation] is the one place the first two are written from, so this is the
   * whole of it here.
   */
  var recordingSuppressed: Boolean

  fun addJump(projectId: String, jump: Jump, reset: Boolean)
  fun saveJumpLocation(editor: VimEditor)

  fun removeJump(projectId: String, jump: Jump)
  fun dropLastJump(projectId: String)
  fun clearJumps(projectId: String)

  fun updateJumpsFromInsert(projectId: String, startOffset: Int, length: Int)
  fun updateJumpsFromDelete(projectId: String, startOffset: Int, length: Int)

  fun includeCurrentCommandAsNavigation(editor: VimEditor)

  /**
   * Loads legacy jump state from an XML element for version migration.
   * Default no-op; overridden by implementations that support PersistentStateComponent.
   */
  fun loadLegacyState(element: Any) {}

  @TestOnly
  fun resetJumps()
}

fun VimJumpService.addJump(editor: VimEditor, reset: Boolean) {
  val virtualFile = editor.getVirtualFile() ?: return
  val path = virtualFile.path
  val protocol = virtualFile.protocol
  val position = editor.offsetToBufferPosition(editor.currentCaret().offset)
  val jump = Jump(position.line, position.column, path, protocol)
  addJump(editor, jump, reset)
}

fun VimJumpService.getJump(editor: VimEditor, count: Int): Jump? {
  return getJump(editor.projectId, count)
}

fun VimJumpService.getJumps(editor: VimEditor): List<Jump> {
  return getJumps(editor.projectId)
}

fun VimJumpService.getJumpSpot(editor: VimEditor): Int {
  return getJumpSpot(editor.projectId)
}

fun VimJumpService.addJump(editor: VimEditor, jump: Jump, reset: Boolean) {
  return addJump(editor.projectId, jump, reset)
}

fun VimJumpService.removeJump(editor: VimEditor, jump: Jump) {
  return removeJump(editor.projectId, jump)
}

fun VimJumpService.dropLastJump(editor: VimEditor) {
  return dropLastJump(editor.projectId)
}

fun VimJumpService.updateJumpsFromInsert(editor: VimEditor, startOffset: Int, length: Int) {
  return updateJumpsFromInsert(editor.projectId, startOffset, length)
}

fun VimJumpService.updateJumpsFromDelete(editor: VimEditor, startOffset: Int, length: Int) {
  return updateJumpsFromDelete(editor.projectId, startOffset, length)
}
