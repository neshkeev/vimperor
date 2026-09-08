/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.visualstarsearch

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getText
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.common.Direction
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.helper.exitVisualMode
import com.maddyhome.idea.vim.key.MappingOwner

/**
 * `vim-visual-star-search`: `*` and `#` in Visual mode search for the selection.
 *
 * Vim's `*` searches for the word under the caret and pays no attention to a Visual selection, so
 * the usual way to search for a phrase is to type it into `/` by hand. This makes `*` and `#`
 * search for whatever is selected, which is the one thing a selection is obviously *for*.
 *
 * The pattern is `\V` - "very nomagic", so `.` and `*` in the selection mean themselves - and `\C`,
 * so the search is case-sensitive whatever `'ignorecase'` says. Only the backslash is escaped,
 * because under `\V` it is the only character left with a meaning.
 *
 * ## What it needed
 *
 * Two engine seams, both extractions rather than new behaviour, in the commit before this one. A
 * `searchWord` overload taking a pattern, because everything a `*` does after finding the word -
 * recording the pattern and its direction, saving it to the register and history, refreshing the
 * highlights and the count - is the same for any pattern. And `collectSelections` on `VimEditor`,
 * which is what makes this work over a block: asking the primary caret for `selectionStart` and
 * `selectionEnd` spans the whole rectangle, newlines and unselected columns included, and searching
 * for *that* finds nothing.
 *
 * Upstream reaches for `com.jetbrains.rd.util.first` to take the first entry of that map. That is
 * JetBrains' Rd library, it appears nowhere else in this fork, and it is `.values.first()`.
 */
@VimPlugin(name = VISUAL_STAR_SEARCH)
public fun VimInitApi.init(): Unit = registerVisualStarSearch()

/** Public because the plugin's extension-point adapter names it too. */
public const val VISUAL_STAR_SEARCH: String = "visual-star-search"

private const val PLUG_STAR: String = "<Plug>VisualStarSearch"
private const val PLUG_HASH: String = "<Plug>VisualHashSearch"

public fun registerVisualStarSearch() {
  val owner = MappingOwner.Plugin.get(VISUAL_STAR_SEARCH)
  val star = injector.parser.parseKeys(PLUG_STAR)
  val hash = injector.parser.parseKeys(PLUG_HASH)

  putExtensionHandlerMapping(MappingMode.X, star, owner, VisualStarSearchHandler(Direction.FORWARDS), false)
  putExtensionHandlerMapping(MappingMode.X, hash, owner, VisualStarSearchHandler(Direction.BACKWARDS), false)

  putKeyMappingIfMissing(MappingMode.X, injector.parser.parseKeys("*"), owner, star, true)
  putKeyMappingIfMissing(MappingMode.X, injector.parser.parseKeys("#"), owner, hash, true)
}

private class VisualStarSearchHandler(private val direction: Direction) : ExtensionHandler {

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    // The first selection rather than the primary caret's offsets: a block is several ranges, and
    // the first is the one whose text the search is for.
    val selection = editor.collectSelections()?.values?.firstOrNull() ?: return
    val range = selection.toVimTextRange()
    val pattern = makePattern(editor.getText(range))

    val position = injector.searchGroup.searchWord(pattern, direction, editor, range, operatorArguments.count1)
    editor.exitVisualMode()
    editor.primaryCaret().moveToOffset(position)
  }

  /**
   * `\V` is very-nomagic, so everything but `\` is literal, and `\C` forces a case-sensitive match
   * whatever `'ignorecase'` and `'smartcase'` are set to - which is what searching for an exact
   * selection means.
   */
  private fun makePattern(text: String): String = "\\V\\C" + text.replace("\\", "\\\\")
}
