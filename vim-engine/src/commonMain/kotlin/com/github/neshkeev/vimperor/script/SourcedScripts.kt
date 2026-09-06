/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.script
import com.maddyhome.idea.vim.annotations.TestOnly

/**
 * Every file that has been sourced this session, in the order it was, for `:scriptnames`.
 *
 * Vim numbers scripts as it loads them and never renumbers, because the number is an identity: it
 * is what `<SID>` expands to and what `:verbose set` names when it says where an option was last
 * set. Nothing here uses it for either yet, so what this is for now is the question `:scriptnames`
 * answers - which file did my config actually load, and in what order - and that question is worth
 * answering on its own. A `.ideavimrc` that sources three files and quietly fails to find one of
 * them looks exactly like a `.ideavimrc` that does not source it.
 *
 * A file sourced twice keeps its first number, which is Vim's: the number identifies the script,
 * not the loading of it.
 */
object SourcedScripts {

  private val scripts = mutableListOf<String>()

  /** Called from the one place a file is sourced. Returns the script's number, one-based. */
  fun record(path: String): Int {
    val existing = scripts.indexOf(path)
    if (existing >= 0) return existing + 1
    scripts += path
    return scripts.size
  }

  fun all(): List<String> = scripts.toList()

  @TestOnly
  fun reset() {
    scripts.clear()
  }
}
