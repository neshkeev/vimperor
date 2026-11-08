/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.api

/**
 * Manages IdeaVim's virtual buffers (the command-line / search history windows, the control-chars
 * editor, and the `inccommand=split` substitute preview) — light in-memory editors that are not
 * backed by a real file.
 */
interface VirtualBufferGroup {

  /**
   * Opens a virtual buffer of [kind] populated with [content].
   *
   * How it opens is [kind]'s own flags. An [exclusive][VirtualBufferKind.exclusive] buffer already
   * on screen refuses another (`:help cmdwin` is E1292). A [reused][VirtualBufferKind.reuseOpen]
   * buffer takes the new [content] instead of a second window. The substitute preview is transient
   * and still opens beside an exclusive one.
   *
   * @param focus when false, the buffer is shown without stealing focus (used by `inccommand=split`).
   * @param caretLine the line to put the caret on, which is how `:help {subject}` reaches its tag.
   */
  fun open(
    context: ExecutionContext,
    editor: VimEditor,
    kind: VirtualBufferKind,
    content: String,
    focus: Boolean = true,
    caretLine: Int = 0,
  )

  /** Closes the virtual buffer hosted in [editor] (typically the cmdwin editor itself). */
  fun close(editor: VimEditor)

  /** Closes the open virtual buffer of [kind] in the project associated with [context]. */
  fun close(context: ExecutionContext, kind: VirtualBufferKind)

  /** Returns whether a virtual buffer of [kind] is currently open in the project associated with [context]. */
  fun isOpen(context: ExecutionContext, kind: VirtualBufferKind): Boolean

  /**
   * Replaces the content of an already-open virtual buffer of [kind] in place.
   *
   * [caretLine] puts the caret on that line when it is not null. Null leaves the caret alone, which
   * is what `:help` and `:actionlist` want — they place it themselves after this returns. Does
   * nothing when no buffer of [kind] is open.
   */
  fun refresh(context: ExecutionContext, kind: VirtualBufferKind, content: String, caretLine: Int? = null)

  /**
   * Shows [editor], then runs [after].
   *
   * Enter in the quickfix window, and `:cclose` typed in that window, use this so the file the
   * window was opened from has the focus before a jump. A host whose edits follow focus shows the
   * editor first. The default runs [after] immediately, which is a host with no window to show.
   */
  fun showOriginal(editor: VimEditor, after: () -> Unit) {
    after()
  }
}
