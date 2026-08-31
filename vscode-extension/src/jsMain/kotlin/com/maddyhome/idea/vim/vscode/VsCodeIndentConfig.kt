/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.VimIndentConfig

/**
 * How wide an indent is and what it is made of, from the editor rather than from an option.
 *
 * The engine has no `'expandtab'` or `'shiftwidth'`, which looks like a gap until you notice it is
 * deliberate: IdeaVim asks IntelliJ's code style, so the engine asks its host. VS Code answers the
 * same question through `editor.options`, already resolved for the file, its language, the user's
 * settings and - with `detectIndentation` on - what the file itself does. Deferring to that is what
 * makes `>>` in this port agree with pressing Tab in the same file without Vim.
 *
 * Read through a lambda rather than captured, because the user can change a file's indentation from
 * the status bar while it is open, and a config that read once at open would keep indenting the old
 * way for the rest of the session.
 */
internal class VsCodeIndentConfig(private val options: () -> TextEditorOptions) : VimIndentConfig {

  /** Vim's `'tabstop'` and `'shiftwidth'` at once, which is what VS Code has one number for. */
  private val width: Int
    get() = (options().tabSize as? Int)?.takeIf { it > 0 } ?: DEFAULT_WIDTH

  /** Whether this file indents with tab characters. `insertSpaces` is the API's way round. */
  val usesTabs: Boolean
    get() = options().insertSpaces != true

  /** How many columns a Tab pressed in [column] covers: the distance to the next stop, never zero. */
  fun toNextTabStop(column: Int): Int = width - (column % width)

  override fun getIndentSize(depth: Int): Int = width * depth

  override fun createIndentByDepth(depth: Int): String = createIndentBySize(getIndentSize(depth))

  /**
   * [size] columns of whitespace, as tabs and spaces the way this file writes them.
   *
   * The remainder is spaces even in a tab-indented file, because a tab cannot express a column that
   * is not a multiple of the tab width - which is the same arithmetic IntelliJ's `IndentConfig`
   * does, for the same reason.
   */
  override fun createIndentBySize(size: Int): String {
    if (size <= 0) return ""
    if (!usesTabs) return " ".repeat(size)
    return "\t".repeat(size / width) + " ".repeat(size % width)
  }

  /**
   * What to indent by when the editor will not say.
   *
   * `tabSize` is documented as always resolved on a live editor, but it is `number | string` in the
   * API and this host has no compiler to hold VS Code to that. Four is VS Code's own default.
   */
  private companion object {
    const val DEFAULT_WIDTH = 4
  }
}
