/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.paragraphmotion

import com.intellij.vim.api.VimInitApi
import com.maddyhome.idea.vim.extension.VimExtension

/**
 * The IntelliJ extension point's way in.
 *
 * The extension itself moved to `vim-engine/src/commonMain` so that the VS Code host can load it
 * too - it is a `@VimPlugin` function there, which needs no classloader. This is the adapter that
 * keeps `set vim-paragraph-motion` working in IntelliJ, and it goes when the plugin does.
 */
internal class ParagraphMotion : VimExtension {
  override fun getName(): String = PARAGRAPH_MOTION

  override fun init(initApi: VimInitApi) = initApi.init()
}
