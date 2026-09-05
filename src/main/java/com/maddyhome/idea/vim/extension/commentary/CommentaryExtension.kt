/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.commentary

import com.intellij.vim.api.VimInitApi
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.extension.VimExtension

/**
 * The IntelliJ half of the commentary port. The extension itself is in `vim-engine`, where both
 * hosts compile it; this keeps the `IdeaVIM.vimExtension` extension point working and goes when the
 * plugin does.
 *
 * `dispose` is here rather than left to the default because the extension registers the
 * `:Commentary` command as well as its mappings, and the default teardown only drops mappings.
 */
internal class CommentaryExtension : VimExtension {

  override fun getName(): String = COMMENTARY

  override fun init(initApi: VimInitApi) {
    registerCommentary(initApi)
  }

  override fun dispose() {
    super.dispose()
    injector.commandGroup.removeAlias(COMMENTARY_COMMAND)
  }
}
