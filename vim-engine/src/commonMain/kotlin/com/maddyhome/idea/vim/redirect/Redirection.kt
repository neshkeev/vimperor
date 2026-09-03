/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.redirect

import com.maddyhome.idea.vim.annotations.TestOnly
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor

/**
 * Where output is going while `:redir` is on, which is somewhere as well as the screen.
 *
 * Only one at a time, which is Vim's rule and not a simplification: "calls to `:redir` will close
 * any active redirection before starting redirection to the new target". So this is one object with
 * one buffer, and starting a second redirection finishes the first properly rather than losing it.
 *
 * The engine knows nothing about registers, variables or files here. A [Sink] is a closure the
 * command built, which is what keeps `:redir @a`, `:redir => var` and `:redir > file` from turning
 * into three cases in the middle of the output path - and what lets a register and a variable share
 * one line of code, since both are `LValueExpression` and both already know how to be assigned to.
 *
 * Written through on every capture rather than at `:redir END`. Vim buffers a variable until the
 * redirection ends and writes a file as it goes; doing the first for everything means an
 * unterminated `:redir` - a script that threw halfway, which is exactly when somebody is redirecting
 * output to find out why - loses everything it captured. Writing through costs one assignment per
 * message and is what the reader would want.
 */
object Redirection {

  /**
   * One place captured output goes.
   *
   * [prefix] is what was already there when the redirection started, and it is how appending works:
   * `:redir >> file`, `:redir @A` and `:redir =>> var` all resolve to reading the old content once
   * and writing the whole thing each time. Reading once is deliberate - re-reading would pick up
   * the redirection's own writes and double them.
   */
  class Sink(
    val describe: String,
    val prefix: String,
    val write: (editor: VimEditor, context: ExecutionContext, content: String) -> Unit,
  )

  private var sink: Sink? = null
  private val captured = StringBuilder()

  /** True while a `:redir` is on, which is what `:redir END` needs to know before complaining. */
  val isActive: Boolean get() = sink != null

  /** What the active redirection is writing to, for `:redir` with nothing after it. */
  val describe: String? get() = sink?.describe

  fun start(editor: VimEditor, context: ExecutionContext, target: Sink) {
    end(editor, context)
    sink = target
    captured.clear()
  }

  /**
   * Adds one message to the redirection, if there is one.
   *
   * Each message becomes a line, because that is what it is on Vim's screen and what every config
   * that splits the result on newlines expects. Vim also puts a newline *first*, so its redirected
   * output begins with an empty line; that is an artifact of writing a screen line-break rather
   * than something anybody wants, and it is not reproduced. A script written for Vim that strips a
   * leading newline still works - stripping one that is not there does nothing.
   */
  fun capture(editor: VimEditor, context: ExecutionContext, text: String) {
    val target = sink ?: return
    captured.append(text)
    if (!text.endsWith("\n")) captured.append("\n")
    target.write(editor, context, target.prefix + captured.toString())
  }

  /** `:redir END`. Returns false when there was nothing to end, which is Vim's `E1185`. */
  fun end(editor: VimEditor, context: ExecutionContext): Boolean {
    val target = sink ?: return false
    // Written once more so that a redirection that captured nothing still clears its target, which
    // is what `:redir @a | redir END` means and what a config uses to empty a register.
    target.write(editor, context, target.prefix + captured.toString())
    sink = null
    captured.clear()
    return true
  }

  @TestOnly
  fun reset() {
    sink = null
    captured.clear()
  }
}
