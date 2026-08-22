/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.functions

/**
 * A source of built-in vimscript functions, such as `strlen()` or `getline()`.
 *
 * See [com.maddyhome.idea.vim.action.CommandProvider] for why this does not mention where the list
 * comes from.
 */
interface VimscriptFunctionProvider {
  fun getFunctions(): Collection<LazyVimscriptFunction>
}
