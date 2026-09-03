/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.message

import com.maddyhome.idea.vim.annotations.TestOnly
import com.maddyhome.idea.vim.api.MessageType

/**
 * The messages this session has shown, so `:messages` can show them again.
 *
 * The question it answers is a real one and has no other answer: an error appears in the status
 * line for as long as it takes the next keystroke to replace it, and a `.ideavimrc` that reports
 * three problems while loading reports them into an editor nobody is looking at yet. Without a
 * history the only record of any of that is gone.
 *
 * What is kept is what Vim keeps. `:echomsg` and `:echoerr` are here and `:echo` is not - that is
 * the entire difference between the two commands, and Vim is explicit about it. Errors are here,
 * and so are the one-line reports that go to the status line, because in Vim those are the same
 * kind of thing: "E486: Pattern not found" and "3 substitutions on 2 lines" both belong to the
 * question "what did it just say?".
 *
 * Recorded before a host decides whether to draw, which is also Vim's rule: `:silent` says what
 * appears on the screen, not what was said, so a message it hid is still here afterwards. `:silent!`
 * is the one thing that really does stop an error existing - it never gets reported at all, in Vim
 * and here - and so there is nothing for this to remember about it.
 */
object MessageHistory {

  data class Entry(val text: String, val type: MessageType)

  private val entries = ArrayDeque<Entry>()

  /**
   * Vim's `'msghistory'` default. The option itself is not implemented; if it ever is, this is the
   * number it would set, and until then the limit exists so that a script in a loop cannot grow
   * this without bound.
   */
  const val LIMIT: Int = 500

  fun record(text: String?, type: MessageType) {
    // A null or blank message is how several callers say "clear the status line". There is nothing
    // to remember about that, and remembering it would push real messages off the end.
    if (text.isNullOrBlank()) return
    entries.addLast(Entry(text, type))
    while (entries.size > LIMIT) entries.removeFirst()
  }

  /** Oldest first, which is the order `:messages` prints them in. */
  fun all(): List<Entry> = entries.toList()

  fun clear() {
    entries.clear()
  }

  @TestOnly
  fun reset() {
    clear()
  }
}
