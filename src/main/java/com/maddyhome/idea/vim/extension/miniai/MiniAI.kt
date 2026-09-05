/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.miniai

import com.intellij.vim.api.VimInitApi
import com.maddyhome.idea.vim.extension.VimExtension

/**
 * The IntelliJ extension point's way in; the extension itself is in `vim-engine`, so that both
 * hosts get it from one source. See `ParagraphMotion` for the same shape.
 */
class MiniAI : VimExtension {
  override fun getName(): String = MINI_AI

  override fun init(initApi: VimInitApi) = initApi.init()
}
