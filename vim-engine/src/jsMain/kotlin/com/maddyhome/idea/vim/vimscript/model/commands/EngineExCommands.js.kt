/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

/** Empty until the ex-command registry is generated - see the `expect` for what is missing. */
actual val engineExCommandProvider: ExCommandProvider = object : ExCommandProvider {
  override fun getCommands(): Map<String, LazyExCommandInstance> = emptyMap()
}
