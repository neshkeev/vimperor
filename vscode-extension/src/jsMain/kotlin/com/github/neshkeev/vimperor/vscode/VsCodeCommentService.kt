/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimCommentService
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.common.TextRange

/**
 * `gc`, `gcc` and `:Commentary` on this host.
 *
 * VS Code knows what a comment is in the file at hand - it is in the language configuration every
 * language extension ships - and exposes it as two commands that act on the selection. So the shape
 * here is the one `=` already uses: put a selection over the range, dispatch the command, and put
 * the caret where it belongs once the command has landed.
 *
 * ## Why the caret is placed in a callback
 *
 * Commenting changes the text before the caret, so the offset the caller asked for is an offset in
 * the *old* document. `hostCommands.run` re-reads the buffer before running the hook, and by then
 * the offset would name a different character - so the caret is not restored to the offset it was
 * given but to the start of the line that offset was on. Adding `//` never adds or removes a line,
 * so a line number survives the toggle where an offset does not. This is the same reasoning
 * `autoIndentRange` records, and for the same reason.
 *
 * ## Why this returns true without knowing
 *
 * The commands resolve a promise and report nothing about what they did - the same limitation
 * `undo` has here. `false` would tell the extension the toggle failed, which would be wrong far more
 * often than it was right; nothing downstream of `commentary`'s `doCommentary` does anything with
 * the answer but return it. The keystroke after this one waits for the command to land, so the
 * ordering is right even though the answer is a guess.
 */
internal class VsCodeCommentService(private val hostCommands: HostCommandRunner) : VimCommentService {

  override fun toggleLineComment(
    editor: VimEditor,
    context: ExecutionContext,
    startLine: Int,
    endLine: Int,
    caretOffset: Int?,
  ): Boolean = toggle(
    editor,
    TextRange(editor.getLineStartOffset(startLine), editor.getLineEndOffset(endLine)),
    caretOffset,
    VsCodeCommands.COMMENT_LINE,
  )

  override fun toggleBlockComment(
    editor: VimEditor,
    context: ExecutionContext,
    range: TextRange,
    caretOffset: Int?,
  ): Boolean = toggle(editor, range, caretOffset, VsCodeCommands.BLOCK_COMMENT)

  private fun toggle(editor: VimEditor, range: TextRange, caretOffset: Int?, command: String): Boolean {
    val vsCodeEditor = editor as? VsCodeEditor ?: return false
    val caretLine = caretOffset?.let { editor.offsetToBufferPosition(it).line }

    vsCodeEditor.selectForHostCommand(listOf(range))
    hostCommands.run(command) {
      if (caretLine != null) {
        editor.primaryCaret().moveToOffset(editor.getLineStartOffset(caretLine.coerceAtMost(editor.lineCount() - 1)))
      }
    }
    return true
  }
}
