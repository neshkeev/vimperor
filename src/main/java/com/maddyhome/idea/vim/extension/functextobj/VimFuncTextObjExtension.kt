/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.functextobj

import com.maddyhome.idea.vim.extension.VimExtension

/**
 * The IntelliJ half of the vim-textobj-function port. The extension itself is in `vim-engine`, where
 * both hosts compile it; this keeps the `IdeaVIM.vimExtension` extension point working and goes when
 * the plugin does.
 *
 * What is *not* portable is the answer to "where does this function begin and end", and that is
 * `IjVimPsiService.getMethodRanges` now rather than something this extension reaches for itself.
 */
internal class VimFuncTextObjExtension : VimExtension {
  override fun getName(): String = FUNC_TEXT_OBJ

  override fun init() {
    registerFuncTextObj()
  }
}
