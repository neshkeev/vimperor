/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.extension.surround

import com.maddyhome.idea.vim.extension.VimExtension

/**
 * The IntelliJ half of the vim-surround port. The extension itself is in `vim-engine`, where both
 * hosts compile it; this keeps the `IdeaVIM.vimExtension` extension point working and goes when the
 * plugin does.
 */
internal class VimSurroundExtension : VimExtension {
  override fun getName(): String = SURROUND

  override fun init() {
    registerSurround()
  }
}
