/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

import com.maddyhome.idea.vim.common.TextRange

/**
 * Toggling comments, which is the one thing `commentary` needs and the engine cannot answer.
 *
 * A comment is `//` in one language, `#` in another, `%` in a third and `<!-- -->` in a fourth, and
 * which one applies is a fact about the file the host has and the engine does not. Guessing from the
 * file extension would be a table of lies that grows forever - the same reason
 * `VsCodePsiService.getCommentAtPos` answers `null` rather than inventing an answer.
 *
 * So the extension asks for a *range* to be toggled and the host decides what that means. Both hosts
 * already had the capability and neither exposed it: IntelliJ through its `Commenter` (reached over
 * RPC, because the editor may be on the other side of a frontend/backend split), VS Code through
 * `editor.action.commentLine` and `editor.action.blockComment`.
 *
 * ## Why toggling rather than commenting
 *
 * Vim's `gc` is a toggle - `gcc` on a commented line uncomments it - and both hosts implement the
 * toggle themselves, including the part that is genuinely hard: deciding whether a *mixed* selection
 * counts as commented. Asking for "comment this" and "uncomment this" separately would mean the
 * engine making that decision with less information than either host has.
 *
 * ## What a host may not be able to do
 *
 * Returning `false` says the host could not do it, and the caller leaves the buffer alone. That is
 * different from a host that has no commenting at all, which supplies no implementation of this at
 * all and simply does not bundle `commentary`.
 */
interface VimCommentService {

  /**
   * Toggles a line comment on every line from [startLine] to [endLine], both inclusive and 0-based.
   *
   * [caretOffset] is where the caret should end up afterwards, or `null` to leave it alone -
   * `:Commentary` as an ex command keeps the caret where the range put it, and `gc` moves it to the
   * start of what it commented. The offset is the one from *before* the toggle; a host that changes
   * the text has to place the caret itself, which is why this takes it rather than letting the
   * caller move the caret afterwards.
   */
  fun toggleLineComment(
    editor: VimEditor,
    context: ExecutionContext,
    startLine: Int,
    endLine: Int,
    caretOffset: Int?,
  ): Boolean

  /** Toggles a block comment around [range]. See [toggleLineComment] for [caretOffset]. */
  fun toggleBlockComment(
    editor: VimEditor,
    context: ExecutionContext,
    range: TextRange,
    caretOffset: Int?,
  ): Boolean
}
