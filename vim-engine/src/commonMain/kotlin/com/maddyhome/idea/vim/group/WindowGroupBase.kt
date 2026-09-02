/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.group

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.injector

abstract class WindowGroupBase : VimWindowGroup {
  override var verticalModifier: Boolean? = null

  /**
   * A file opened by name lands in a tab of its own in both hosts, so `:tabedit file` is `:edit
   * file`; only the empty case needs the host to say what a new buffer is.
   */
  override fun openNewTab(context: ExecutionContext, filename: String) {
    if (filename.isEmpty()) {
      openNewBuffer(context)
    } else {
      injector.file.openFile(filename, context)
    }
  }
}
