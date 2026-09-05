/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.abolish

import com.maddyhome.idea.vim.extension.VimExtension

/**
 * The IntelliJ half of the vim-abolish port. The extension itself is [Abolish], in `vim-engine`,
 * where both hosts compile it; this keeps the `IdeaVIM.vimExtension` extension point working and
 * goes when the plugin does.
 */
internal class AbolishExtension : VimExtension {
  override fun getName(): String = ABOLISH

  override fun init() {
    Abolish().register()
  }
}
