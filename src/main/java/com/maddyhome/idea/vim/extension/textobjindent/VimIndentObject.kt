/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.extension.textobjindent

import com.maddyhome.idea.vim.extension.VimExtension

/**
 * The IntelliJ half of the vim-indent-object port. The extension itself is in `vim-engine`, where
 * both hosts compile it; this keeps the `IdeaVIM.vimExtension` extension point working and goes when
 * the plugin does.
 */
internal class VimIndentObject : VimExtension {
  override fun getName(): String = TEXT_OBJ_INDENT

  override fun init() {
    registerIndentObject()
  }
}
