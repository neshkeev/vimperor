/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

/**
 * The system clipboard, which VS Code will only talk about in promises.
 *
 * `"+p` has to produce text *now* - it is a register read in the middle of a command - and
 * `env.clipboard.readText()` returns a promise. There is no version of this that is exactly right,
 * so the question is which way to be wrong.
 *
 * Writing is the easy half: `"+y` can hand the text over and not wait, because nothing depends on
 * when it lands. Reading keeps a copy of what the clipboard last said, and refreshes it at the
 * moments the clipboard can have changed without this process knowing - which is when the window
 * regains focus, since that is what a user copying from a browser and switching back does.
 *
 * What stays wrong: copying in another application *while* VS Code has focus, and pasting without
 * clicking away and back. That window is small and the alternative is making paste asynchronous,
 * which is a change to the key path rather than to the clipboard.
 */
interface SystemClipboard {
  /** What the clipboard said when it was last read. */
  fun read(): String?

  fun write(text: String)

  /** Asks the system for the current contents, updating [read] when the answer arrives. */
  fun refresh()

  /** For a host with no system clipboard: an in-memory one, which is what tests want too. */
  class InMemory(private var contents: String? = null) : SystemClipboard {
    override fun read(): String? = contents

    override fun write(text: String) {
      contents = text
    }

    override fun refresh() {}
  }
}

class VsCodeClipboard : SystemClipboard {
  private var mirror: String? = null

  override fun read(): String? = mirror

  override fun write(text: String) {
    mirror = text
    // Nothing waits for this. A failed write leaves the mirror ahead of the system clipboard, which
    // is the same state as a user copying elsewhere - and the next refresh corrects it.
    env.clipboard.writeText(text)
  }

  override fun refresh() {
    env.clipboard.readText().then { text -> mirror = text }
  }
}
