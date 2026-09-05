/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.newapi

import com.intellij.openapi.editor.impl.editorId
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimCommentService
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.group.comment.CommentaryRemoteApi
import com.maddyhome.idea.vim.group.rpc

/**
 * IntelliJ's half of [VimCommentService], which is the body `CommentaryExtension.Util.doCommentary`
 * used to have.
 *
 * It goes over RPC because the editor may be on the far side of a frontend/backend split, and the
 * `Commenter` that knows what a comment looks like in this file lives on the backend. That is the
 * whole reason this could not stay in the extension once the extension moved to `vim-engine`: not
 * the commenting, the RPC.
 *
 * `-1` is the remote API's spelling of "leave the caret alone", which is why the engine's `null`
 * becomes one here.
 */
internal class IjCommentService : VimCommentService {

  override fun toggleLineComment(
    editor: VimEditor,
    context: ExecutionContext,
    startLine: Int,
    endLine: Int,
    caretOffset: Int?,
  ): Boolean {
    val ijEditor = editor.ij
    val editorId = ijEditor.editorId()
    rpc(ijEditor.project) {
      CommentaryRemoteApi.getInstance().toggleLineComment(editorId, startLine, endLine, caretOffset ?: -1)
    }
    return true
  }

  override fun toggleBlockComment(
    editor: VimEditor,
    context: ExecutionContext,
    range: TextRange,
    caretOffset: Int?,
  ): Boolean {
    val ijEditor = editor.ij
    val editorId = ijEditor.editorId()
    rpc(ijEditor.project) {
      CommentaryRemoteApi.getInstance()
        .toggleBlockComment(editorId, range.startOffset, range.endOffset, caretOffset ?: -1)
    }
    return true
  }
}
