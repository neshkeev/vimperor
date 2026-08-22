/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

/**
 * A source of ex-commands, keyed by the command name as it is typed after `:`.
 *
 * See [CommandProvider] for why this does not mention where the mapping comes from.
 */
interface ExCommandProvider {
  fun getCommands(): Map<String, LazyExCommandInstance>
}
