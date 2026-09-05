/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.exchange

import com.maddyhome.idea.vim.extension.VimExtension

/**
 * The IntelliJ half of the vim-exchange port. The extension itself is in `vim-engine`, where both
 * hosts compile it; this keeps the `IdeaVIM.vimExtension` extension point working and goes when the
 * plugin does.
 *
 * `dispose` is here rather than left to the default because a pending exchange holds a highlight,
 * and the loader's owner-based teardown knows nothing about either.
 */
internal class VimExchangeExtension : VimExtension {

  override fun getName(): String = EXCHANGE

  override fun init() {
    registerExchange()
  }

  override fun dispose() {
    super.dispose()
    disposeExchange()
  }
}
