/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

import com.github.neshkeev.vimperor.redirect.Redirection

abstract class VimOutputPanelServiceBase : VimOutputPanelService {
  override fun getOrCreate(editor: VimEditor, context: ExecutionContext): VimOutputPanel {
    return getCurrentOutputPanel() ?: create(editor, context)
  }

  /**
   * The one place output reaches on its way to a panel, and so the one place `:redir` can tap.
   *
   * The order of the three things that happen here is the whole of it. `:filter` runs first,
   * because it decides what the output *is* and a redirection should catch what a reader would
   * have seen. `:redir` runs second, before `:silent` - which is not an ordering detail but the
   * entire idiom: `:redir => x | silent map | redir END` wants the table and does not want it on
   * the screen, and a capture placed after the silence check would return every one of those
   * empty. `:silent` runs last and only decides whether to draw.
   */
  override fun output(editor: VimEditor, context: ExecutionContext, text: String, messageType: MessageType) {
    val shown = injector.messages.filterOutput(text) ?: return
    Redirection.capture(editor, context, shown)
    if (injector.messages.hides(messageType)) return
    val panel = getOrCreate(editor, context)
    panel.addText(shown, true, messageType)
    panel.show()
  }

  override fun clear(
    editor: VimEditor,
    context: ExecutionContext,
  ) {
    val panel = getOrCreate(editor, context)
    panel.clearText()
  }
}