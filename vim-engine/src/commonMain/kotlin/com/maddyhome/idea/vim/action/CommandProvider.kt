/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.action

import com.maddyhome.idea.vim.action.change.LazyVimCommand

/**
 * A source of built-in commands, keyed by the keystrokes that invoke them.
 *
 * Deliberately says nothing about where the list comes from. On the JVM it is a JSON resource
 * written by the annotation processor and read back reflectively ([JsonCommandProvider]); a host
 * with no class loader supplies the same commands from a registry generated at build time. The
 * engine only ever asks for the collection, so both are equally valid answers.
 */
interface CommandProvider {
  fun getCommands(): Collection<LazyVimCommand>
}
