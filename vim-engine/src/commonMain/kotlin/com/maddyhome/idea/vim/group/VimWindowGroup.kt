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
   * Which axis the next window command works on, as `:vertical` and `:horizontal` set it.
   *
   * Null outside one of those modifiers, which is not the same as false: `:split` is horizontal and
   * `:vsplit` vertical of their own accord, and a modifier is what overrides that. `:vertical
   * resize 30` sets the width where a bare `:resize 30` sets the height, which is the same rule
   * read on a different command.
   *
   * State rather than an action, and here rather than in the commands, because the command that
   * sets it and the commands that read it are three files apart and both ends already reach the
   * window group.
   */
  var verticalModifier: Boolean?

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

  /**
   * `:diffsplit` and `:diffthis` - the two files, side by side, with their differences marked.
   *
   * Vim's diff mode is a property of *windows*: two windows both in diff mode are compared, and
   * `'diffopt'`, the folds and the highlighting all follow from that. Neither host has a window
   * mode to turn on; both have a diff *view* that is opened over a pair of files and owns itself
   * from then on. So this is that pair, and the engine's job is only to decide which two files
   * they are - see [com.github.neshkeev.vimperor.diff.Diff].
   *
   * Returns false when the host could not open one, so the command can say so rather than appear
   * to have worked.
   */
  fun showDiff(context: ExecutionContext, leftPath: String, rightPath: String): Boolean = false
}
