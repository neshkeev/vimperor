/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.api

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
/**
 * The panel showing the files in the project: IntelliJ's Project view, VS Code's Explorer.
 *
 * This exists for `NERDTree`, and it is worth being exact about which half of that extension it
 * serves. NERDTree in IdeaVim is two things that share a name. One is six ex commands - `:NERDTree`,
 * `:NERDTreeToggle`, `:NERDTreeFind` and their friends - which say *show me the file tree* and
 * nothing more. The other is thirty key mappings that work only while the tree itself has keyboard
 * focus, installed on the Project view's Swing component.
 *
 * The first half is what this is. It is a panel every editor has, opened and closed by commands
 * every editor already ships, and the engine's whole part in it is knowing which of five things the
 * user asked for. IdeaVim wrote those five as IntelliJ action ids, which is the only reason they
 * could not travel.
 *
 * The second half is not here and will not be. A key pressed inside a file tree never reaches this
 * extension in VS Code - `type` is an editor command, and the sidebar is not an editor - so there
 * is no runtime seam to build. What VS Code offers instead is declarative: keybindings in
 * `package.json` with a `when` clause, which is a different mechanism from anything else in this
 * port and cannot be driven by `set NERDTree` or remapped by `g:NERDTreeMapOpenSplit`. See
 * `vscode-extension/package.json` for what is bound there and `NerdTree.kt` for the full list of
 * what NERDTree maps, with the ones this host cannot reach marked.
 *
 * A host with no such panel implements nothing and does not bundle the extension.
 */
interface VimFileTreeService {

  /** `:NERDTree` and `:NERDTreeFocus` - show the tree and put the keyboard in it. */
  fun focus(editor: VimEditor, context: ExecutionContext)

  /**
   * `:NERDTreeToggle` - show it if it is hidden, hide it if it is showing.
   *
   * A single call rather than [focus] and [close] over an `isVisible`, because "is it showing" is a
   * question about a panel that only the host can answer and the answer is stale the moment it is
   * given. Both hosts have a toggle of their own.
   */
  fun toggle(editor: VimEditor, context: ExecutionContext)

  /** `:NERDTreeClose` - hide it. */
  fun close(editor: VimEditor, context: ExecutionContext)

  /** `:NERDTreeFind` - show it with the file the caret is in selected. */
  fun revealCurrentFile(editor: VimEditor, context: ExecutionContext)

  /** `:NERDTreeRefreshRoot` - re-read the files from disk. */
  fun refresh(editor: VimEditor, context: ExecutionContext)
}
