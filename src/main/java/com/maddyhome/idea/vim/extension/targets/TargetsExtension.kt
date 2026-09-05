/*
 * Copyright 2003-2024 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.extension.targets

import com.maddyhome.idea.vim.extension.VimExtension

/**
 * The IntelliJ half of the targets.vim port. The extension itself is [Targets], in `vim-engine`,
 * where both hosts compile it; this keeps the `IdeaVIM.vimExtension` extension point working and
 * goes when the plugin does.
 */
internal class TargetsExtension : VimExtension {
  override fun getName(): String = TARGETS

  override fun init() {
    Targets().register()
  }
}
