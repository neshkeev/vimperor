/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

import com.maddyhome.idea.vim.helper.EngineMessageHelper
import com.maddyhome.idea.vim.annotations.PropertyKey

/**
 * How much of what a command has to say reaches the user, as `:silent` sets it.
 *
 * Vim draws the line between a message and an error: `:silent` hides the first and lets the second
 * through, and only `:silent!` hides both. That is the whole of the difference between the two, so
 * it is the whole of what this enum has to say.
 */
enum class MessageSuppression {
  NONE,

  /** `:silent` - the command's own output, and nothing it got wrong. */
  MESSAGES,

  /** `:silent!` - errors as well, which is what a config uses to guard something optional. */
  EVERYTHING,
}

interface VimMessages {

  /**
   * What is being hidden while a `:silent` command runs, or [MessageSuppression.NONE] outside one.
   *
   * A promise rather than a mechanism: a message does not pass through any one place in the engine
   * on its way to a host, so every implementation of this interface has to consult it in its own
   * `show*` methods. `SilentCommand` sets it and puts it back.
   */
  var suppression: MessageSuppression

  /** True when a message would be thrown away, so a caller can skip the work of building one. */
  val isSilent: Boolean
    get() = suppression != MessageSuppression.NONE

  /** True when an *error* would be thrown away - only `:silent!` goes that far. */
  val isSilentAboutErrors: Boolean
    get() = suppression == MessageSuppression.EVERYTHING

  /**
   * Whether output of this kind is being hidden, for the panel rather than the status line.
   *
   * `:echo` and the tables (`:registers`, `:marks`, ...) write to the output panel and never touch
   * a `show*` method, so an implementation of [VimOutputPanelService] has to ask this on its own
   * behalf - and it has to ask with the kind, because plain `:silent` hides output and lets errors
   * through.
   */
  fun hides(messageType: MessageType): Boolean = when (messageType) {
    MessageType.ERROR -> isSilentAboutErrors
    else -> isSilent
  }

  /**
   * Displays an informational message to the user.
   * The message panel closes on any keystroke and passes the key through to the editor.
   */
  fun showMessage(editor: VimEditor, message: String?)

  /**
   * Displays an error message to the user (typically in red).
   * Clears any existing output panel content before showing the message.
   * The message panel closes on any keystroke and passes the key through to the editor.
   */
  fun showErrorMessage(editor: VimEditor, message: String?)

  /**
   * Appends an error message to the existing output panel content (typically in red).
   * Unlike [showErrorMessage], this does not clear prior output.
   * Use this when reporting errors during script execution where earlier output should be preserved.
   */
  fun appendErrorMessage(editor: VimEditor, message: String?)

  /**
   * Legacy method for displaying messages.
   * @deprecated Use [showMessage] or [showErrorMessage] instead.
   */
  @Deprecated("Use showMessage or showErrorMessage instead", ReplaceWith("showMessage(editor, message)"))
  fun showStatusBarMessage(editor: VimEditor?, message: String?)

  fun getStatusBarMessage(): String?
  fun clearStatusBarMessage()
  fun indicateError()
  fun clearError()
  fun isError(): Boolean

  /**
   * Fetch a message from the engine's resource bundle.
   *
   * Note that this will _only_ return messages from the engine's resource bundle. It will not return messages from
   * the host's resource bundle. Hosts should use an alternative method to fetch messages from their own resources.
   */
  fun message(@PropertyKey(resourceBundle = EngineMessageHelper.BUNDLE) key: String, vararg params: Any): String

  fun updateStatusBar(editor: VimEditor)
}
