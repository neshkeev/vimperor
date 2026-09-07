/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

/**
 * One entry of Vim's buffer list, as `:ls` prints it and `:buffer` names it.
 *
 * Vim's buffers do not exist in either host. IntelliJ has open files and VS Code has tabs, and both
 * of those are close enough to a buffer for the two commands that ask about them - which is all
 * this carries: what `:ls` prints in a row, and enough of an identity for `:buffer name` to find
 * one. Nothing here is a handle, deliberately. A host that answered with its own editor objects
 * would be handing out references to buffers the user is free to close between the list being built
 * and a command acting on it.
 */
data class VimBuffer(
  /**
   * The file's base name, which is what `:buffer foo` matches against.
   *
   * Vim matches a buffer by any part of its name and this does the same, so `:buffer main` finds
   * `main.kt`. Separate from [displayPath] because that one is relative to the project and would
   * make `:buffer src` match every file under `src/`.
   */
  val name: String,

  /** The path as `:ls` shows it: relative to the project or workspace root when it is under one. */
  val displayPath: String,

  /** Vim's `%` - the buffer in the window the command was typed in. */
  val isCurrent: Boolean,

  /** Vim's `#` - the alternate buffer, which `<C-^>` and `:buffer #` go to. */
  val isAlternate: Boolean,

  /** Vim's `=`, which it prints for a buffer that cannot be written to. */
  val isReadOnly: Boolean,

  /** Vim's `+` - changes that are not on disk. */
  val isModified: Boolean,

  /**
   * The line the cursor is on, 1-based.
   *
   * Zero for a buffer that is listed but not loaded, which is what Vim prints for one it has only
   * a name for. A tab that has never been given an editor is exactly that case.
   */
  val line: Int,
)
