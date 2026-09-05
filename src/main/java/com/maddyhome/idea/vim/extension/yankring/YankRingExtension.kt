/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.yankring

import com.intellij.vim.api.VimInitApi
import com.maddyhome.idea.vim.extension.VimExtension

/**
 * The IntelliJ half of the YankRing port. The extension itself is in `vim-engine`, as the
 * `@VimPlugin` function `init`; this keeps the `IdeaVIM.vimExtension` extension point working and
 * goes when the plugin does.
 *
 * `dispose` is here rather than left to the default because the extension registers commands and a
 * register listener, and the default teardown only drops mappings. See [disposeYankRing].
 */
internal class YankRingExtension : VimExtension {

  override fun getName(): String = PLUGIN_NAME

  override fun init(initApi: VimInitApi) {
    initApi.init()
  }

  override fun dispose() {
    disposeYankRing()
  }
}
