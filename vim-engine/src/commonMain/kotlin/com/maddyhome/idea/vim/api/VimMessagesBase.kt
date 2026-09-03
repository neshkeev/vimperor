/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

import com.maddyhome.idea.vim.message.MessageHistory

/**
 * The half of a host's messages that is the same in every host.
 *
 * The four `show*` methods are final here and each records before delegating, which makes this the
 * single place every message passes through - the thing [VimMessages] says its `suppression` field
 * does *not* have. That was fine while the only question was whether to draw, since a host has to
 * answer that itself anyway; it stopped being fine when `:messages` needed to know what had been
 * said, because "each implementation consults a flag" cannot answer a question about the past.
 *
 * So a host now implements `display*` instead of `show*`. It is the same method with the same body
 * - the silence checks stay exactly where they were - and the only thing that changed is that it no
 * longer decides whether the message is remembered. A message `:silent` hid is remembered, because
 * `:silent` says what is drawn rather than what was said. An error `:silent!` swallowed is not,
 * because it never reaches here: the executor stops reporting it, exactly as Vim's `emsg_core`
 * does.
 */
abstract class VimMessagesBase : VimMessages {
  override var suppression: MessageSuppression = MessageSuppression.NONE
  override var outputFilter: OutputFilter? = null

  final override fun showMessage(editor: VimEditor, message: String?) {
    MessageHistory.record(message, MessageType.STANDARD)
    displayMessage(editor, message)
  }

  final override fun showErrorMessage(editor: VimEditor, message: String?) {
    MessageHistory.record(message, MessageType.ERROR)
    displayErrorMessage(editor, message)
  }

  final override fun appendErrorMessage(editor: VimEditor, message: String?) {
    MessageHistory.record(message, MessageType.ERROR)
    appendDisplayedErrorMessage(editor, message)
  }

  final override fun showStatusBarMessage(editor: VimEditor?, message: String?) {
    // The status line is where "E486: Pattern not found" and "3 substitutions on 2 lines" go, and
    // those are messages in every sense Vim means; the mode indicator has its own path.
    MessageHistory.record(message, MessageType.STANDARD)
    displayStatusBarMessage(editor, message)
  }

  /** [showMessage], minus the remembering. */
  protected abstract fun displayMessage(editor: VimEditor, message: String?)

  /** [showErrorMessage], minus the remembering. */
  protected abstract fun displayErrorMessage(editor: VimEditor, message: String?)

  /** [appendErrorMessage], minus the remembering. */
  protected abstract fun appendDisplayedErrorMessage(editor: VimEditor, message: String?)

  /** [showStatusBarMessage], minus the remembering. */
  protected abstract fun displayStatusBarMessage(editor: VimEditor?, message: String?)
}
