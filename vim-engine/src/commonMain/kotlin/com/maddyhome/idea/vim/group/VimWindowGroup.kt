/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.group

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimCaret

interface VimWindowGroup {
  fun selectWindowInRow(caret: VimCaret, context: ExecutionContext, relativePosition: Int, vertical: Boolean)

  // Note: window selection functions below have a known limitation.
  // After calling these, FileEditorManager.getSelectedTextEditor() may return the old editor
  // because the platform propagates the change asynchronously (IJPL-235369).
  // These functions are not exposed in the extension API (VimApi) until the platform
  // provides a way to observe or await the propagation (VIM-4138).
  fun selectNextWindow(context: ExecutionContext)
  fun selectWindow(context: ExecutionContext, index: Int)
  fun selectPreviousWindow(context: ExecutionContext)
  fun closeAllExceptCurrent(context: ExecutionContext)
  fun splitWindowVertical(context: ExecutionContext, filename: String, focusNew: Boolean = true)
  fun splitWindowHorizontal(context: ExecutionContext, filename: String, focusNew: Boolean = true)
  fun closeCurrentWindow(context: ExecutionContext)
  fun closeAll(context: ExecutionContext)

  /**
   * `:enew` - a new, empty, unnamed buffer, in the window that is already focused.
   *
   * Vim's is a buffer with no file behind it, waiting for a `:w` to give it a name. Both hosts have
   * something that is that and each calls it something else, so each answers with its own.
   */
  fun openNewBuffer(context: ExecutionContext)

  /**
   * `:tabnew` and `:tabedit` - a new tab, empty or showing [filename].
   *
   * Vim's tab page holds a whole window layout and neither host's tab does, which is a difference
   * `:tabnext` and `:tabclose` already live with. What matters here is that a file opened this way
   * gets a tab of its own, which is true in both, so the base implementation is the whole of it.
   */
  fun openNewTab(context: ExecutionContext, filename: String)
}
