/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

interface VimrcFileState {
  var filePath: String?

  fun saveFileState(filePath: String)

  /**
   * Whether [path] names the `.ideavimrc` this host would load.
   *
   * `:source` needs to know, because sourcing the vimrc is tracked differently from sourcing any
   * other file. Only the host can answer it: where the file lives is a host convention - `$HOME`,
   * XDG, or an IDE setting - and comparing two paths for the same file is a filesystem question,
   * not a string one.
   */
  fun isVimRcFile(path: String): Boolean
}
