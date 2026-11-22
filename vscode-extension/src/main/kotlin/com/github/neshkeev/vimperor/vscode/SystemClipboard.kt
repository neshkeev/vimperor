/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

/**
 * The system clipboard, which VS Code will only talk about in promises.
 *
 * `"+p` has to produce text *now* - it is a register read in the middle of a command - and
 * `env.clipboard.readText()` returns a promise. There is no version of this that is exactly right,
 * so the question is which way to be wrong.
 *
 * Writing is the easy half: `"+y` can hand the text over and not wait, because nothing depends on
 * when it lands. Reading keeps a copy of what the clipboard last said. The window regaining focus
 * refreshes it - a user copying in a browser and switching back - and a paste refreshes it again
 * and waits, because `Cmd+C` in this same window never takes the focus away. See [VimHost]'s put
 * path for that wait.
 *
 * A write that has not landed yet is newer than a read of whatever is still on the clipboard.
 * `yyp` types the `p` a moment after the yank, and refreshing there has to paste the yank rather
 * than whatever the clipboard held before the write arrived.
 */
interface SystemClipboard {
  /** What the clipboard said when it was last read. */
  fun read(): String?

  fun write(text: String)

  /**
   * Asks the system for the current contents, updating [read] when the answer arrives.
   *
   * [onDone] runs once the answer has landed, whether it landed or failed. It is what makes a
   * paste that the *user* asked for exact rather than approximately current: the mirror is refreshed
   * on window focus, which covers copying in another application and switching back, and a paste
   * gesture can afford to wait a promise for the case that does not - copying in the integrated
   * terminal, say, which never takes focus away from the window.
   */
  fun refresh(onDone: () -> Unit = {})

  /** For a host with no system clipboard: an in-memory one, which is what tests want too. */
  class InMemory(private var contents: String? = null) : SystemClipboard {
    override fun read(): String? = contents

    override fun write(text: String) {
      contents = text
    }

    override fun refresh(onDone: () -> Unit) {
      onDone()
    }
  }
}

/**
 * What a refresh should leave in the mirror.
 *
 * [pendingWrite] is text this host has handed to the system and not yet been told has landed. A
 * read that disagrees with it is the clipboard as it was before that write, and pasting it would
 * put back whatever `yyp` had just replaced. Once the write has landed there is no pending text,
 * and the read is the truth - a `Cmd+C` made after the yank included.
 */
internal fun textAfterClipboardRead(pendingWrite: String?, read: String): String =
  if (pendingWrite != null && read != pendingWrite) pendingWrite else read

class VsCodeClipboard : SystemClipboard {
  private var mirror: String? = null

  /** The latest write handed to VS Code, until that write is acknowledged. */
  private var pendingText: String? = null

  /** So an earlier write's acknowledgement cannot clear a later one. */
  private var writeGeneration: Int = 0

  override fun read(): String? = mirror

  override fun write(text: String) {
    mirror = text
    val generation = ++writeGeneration
    pendingText = text
    // Nothing waits for this. A failed write leaves the mirror ahead of the system clipboard, which
    // is the same state as a user copying elsewhere - and the next refresh corrects it, once
    // [pendingText] has been dropped.
    env.clipboard.writeText(text).then(
      { acknowledge(generation) },
      { acknowledge(generation) },
    )
  }

  private fun acknowledge(generation: Int) {
    if (generation == writeGeneration) pendingText = null
  }

  override fun refresh(onDone: () -> Unit) {
    env.clipboard.readText().then(
      { text ->
        mirror = textAfterClipboardRead(pendingText, text)
        onDone()
      },
      // A clipboard that will not be read is not a reason to drop the gesture: the caller carries
      // on with whatever the mirror last said, which is what every other read here does.
      { onDone() },
    )
  }
}
