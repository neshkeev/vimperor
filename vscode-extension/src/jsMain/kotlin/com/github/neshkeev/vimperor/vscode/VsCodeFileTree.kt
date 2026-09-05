/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimFileTreeService

/**
 * The Explorer, for `NERDTree`'s ex commands.
 *
 * Five commands VS Code already has, which is the whole implementation - and is the point. This
 * half of NERDTree was never IntelliJ-shaped; it was written in IntelliJ's vocabulary, and once
 * `VimFileTreeService` gave it a host-independent one there was nothing left to port.
 *
 * None of these waits. `waitForIt` holds the user's keys until a command lands and exists for
 * commands that rewrite the document behind the engine's back; opening a sidebar changes what is on
 * screen and not what is in the buffer, so holding the keyboard for it would only make the editor
 * feel slow - the same reasoning as `:split` and changing tab.
 */
internal class VsCodeFileTree(private val hostCommands: HostCommandRunner) : VimFileTreeService {

  override fun focus(editor: VimEditor, context: ExecutionContext) {
    run(VsCodeCommands.FOCUS_EXPLORER)
  }

  /**
   * `:NERDTreeToggle`.
   *
   * The sidebar rather than the Explorer specifically - see [VsCodeCommands.TOGGLE_SIDEBAR] for
   * what that costs. A "show the Explorer if the sidebar is showing something else, otherwise
   * toggle" would need to ask which view is open, which VS Code does not tell an extension.
   */
  override fun toggle(editor: VimEditor, context: ExecutionContext) {
    run(VsCodeCommands.TOGGLE_SIDEBAR)
  }

  override fun close(editor: VimEditor, context: ExecutionContext) {
    run(VsCodeCommands.CLOSE_SIDEBAR)
  }

  override fun revealCurrentFile(editor: VimEditor, context: ExecutionContext) {
    run(VsCodeCommands.REVEAL_IN_EXPLORER)
  }

  override fun refresh(editor: VimEditor, context: ExecutionContext) {
    run(VsCodeCommands.REFRESH_EXPLORER)
  }

  private fun run(command: String) = hostCommands.run(command, waitForIt = false)
}
