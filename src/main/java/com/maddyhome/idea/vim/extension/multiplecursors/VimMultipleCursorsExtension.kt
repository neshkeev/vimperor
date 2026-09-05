/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.multiplecursors

import com.maddyhome.idea.vim.extension.VimExtension

/**
 * The IntelliJ half of the vim-multiple-cursors port. The extension itself is in `vim-engine`, where
 * both hosts compile it; this keeps the `IdeaVIM.vimExtension` extension point working and goes when
 * the plugin does.
 *
 * `dispose` is here rather than left to the default because the extension remembers, per buffer,
 * what the last cursor was added from, and the loader's owner-based teardown knows nothing about it.
 */
internal class VimMultipleCursorsExtension : VimExtension {

  override fun getName(): String = MULTIPLE_CURSORS

  override fun init() {
    registerMultipleCursors()
  }

  override fun dispose() {
    super.dispose()
    disposeMultipleCursors()
  }
}
