/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.api

import com.maddyhome.idea.vim.common.Direction
import com.maddyhome.idea.vim.history.VimHistory

/**
 * Vim's command-line window - `q:`, `q/` and `q?` - which is history in a buffer you can edit.
 *
 * This has no host in it and never had. It was `IjSearchWindowGroup`, in the IntelliJ module, with
 * not one `com.intellij` import: every line of it is `injector.virtualBufferGroup`,
 * `injector.historyGroup`, `injector.vimscriptExecutor` and `injector.searchGroup`. It was in the
 * plugin because the *buffer* is host-shaped, and the feature built on the buffer got filed with
 * it.
 *
 * So the seam is one interface lower than it looked. [VirtualBufferGroup] is what a host has to
 * supply - a light editor over no file - and this is the same for everybody: put the history in
 * one, and on `<CR>` take the line under the caret, close the window, and run the line against the
 * editor that was active when it opened.
 *
 * That order is Vim's and is load-bearing. `:help cmdwin-execute` says the line runs in the context
 * of the original window, so the original editor is resolved *before* the close - afterwards there
 * may be nothing to resolve it from.
 */
open class SearchWindowGroupBase : SearchWindowGroup {

  override fun openCommandHistoryWindow(editor: VimEditor, context: ExecutionContext) {
    injector.virtualBufferGroup.open(
      context,
      editor,
      VirtualBufferKind.Command,
      historyContent(VimHistory.Type.Command)
    )
  }

  override fun openSearchHistoryWindow(
    editor: VimEditor,
    context: ExecutionContext,
    direction: Direction,
  ) {
    injector.virtualBufferGroup.open(
      context,
      editor,
      VirtualBufferKind.Search(direction),
      historyContent(VimHistory.Type.Search)
    )
  }

  override fun executeCurrentLineAndClose(
    cmdwin: VimEditor,
    caret: ImmutableVimCaret,
    context: ExecutionContext,
  ) {
    val kind = cmdwin.getVirtualBufferKind() ?: return
    val lineStart = cmdwin.getLineStartForOffset(caret.offset)
    val lineEnd = cmdwin.getLineEndForOffset(caret.offset)
    val line = cmdwin.text().subSequence(lineStart, lineEnd).toString()

    // Resolve the original editor BEFORE closing the cmdwin (`:help cmdwin-execute`).
    val originalEditor = cmdwin.getCmdwinOriginalEditor() ?: cmdwin
    val originalContext = injector.executionContextManager.getEditorExecutionContext(originalEditor)

    injector.virtualBufferGroup.close(cmdwin)

    if (line.isBlank()) return
    when (kind) {
      VirtualBufferKind.Command -> {
        injector.vimscriptExecutor.execute(line, originalEditor, originalContext, skipHistory = false)
      }

      is VirtualBufferKind.Search -> {
        val startCaret = originalEditor.primaryCaret()
        val result = injector.searchGroup.processSearchCommand(
          originalEditor, line, startCaret.offset, 1, kind.direction,
        )
        if (result != null) {
          originalEditor.primaryCaret().moveToOffset(result.first)
        }
      }

      VirtualBufferKind.ControlCharsEditor, VirtualBufferKind.SubstitutePreview -> {}
    }
  }

  private fun historyContent(type: VimHistory.Type): String =
    injector.historyGroup.getEntries(type, 0, 0).joinToString(separator = "\n") { it.entry }
}
