/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.MutableVimEditor
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getLineEndOffset
import com.maddyhome.idea.vim.undo.LineChange

/**
 * `U` - undo line, which is not `u` and is not an undo stack.
 *
 * Vim keeps exactly one pristine copy of one line, taken the first time that line is touched, and
 * `U` swaps it with what is there now. So a second `U` puts the change back, which is why it stores
 * what it replaced rather than clearing anything.
 *
 * The sweep had this down as "U needs the same host history undo does", which was the wrong reading
 * of it. `u` walks a history; `U` walks nothing. What it needs is a copy taken *before* an edit -
 * and every edit here goes through [DocumentBuffer], which now says so.
 *
 * One snapshot per buffer, keyed by the editor's URI so two views of one file share it, the way
 * Vim's is per buffer rather than per window.
 */
internal class VsCodeLineChange : LineChange {

  private data class Snapshot(val line: Int, val text: String, val column: Int)

  private val snapshots: MutableMap<String, Snapshot> = mutableMapOf()

  override fun snapshotLine(line: Int, editor: VimEditor): Boolean {
    if (line < 0 || line >= editor.lineCount()) return false
    val key = editor.getPath() ?: return false
    // Already saved: keep the copy from before the *first* change, which is what makes `U` revert
    // every change to the line rather than the last one.
    if (snapshots[key]?.line == line) return false
    val start = editor.getLineStartOffset(line)
    val end = editor.getLineEndOffset(line)
    snapshots[key] = Snapshot(line, editor.text().substring(start, end), caretColumn(editor, line, start))
    return true
  }

  override fun undoLineChange(editor: VimEditor, context: ExecutionContext): Boolean {
    val key = editor.getPath() ?: return false
    val snapshot = snapshots[key] ?: return false
    if (snapshot.line >= editor.lineCount()) return false

    val start = editor.getLineStartOffset(snapshot.line)
    val end = editor.getLineEndOffset(snapshot.line)
    val current = editor.text().substring(start, end)
    val currentColumn = caretColumn(editor, snapshot.line, start)

    (editor as MutableVimEditor).replaceString(start, end, snapshot.text)

    // What was just replaced, so the next `U` toggles back. This has to be written *after* the edit:
    // the edit runs `beforeChange`, which sees the line already saved and leaves the old copy alone.
    snapshots[key] = Snapshot(snapshot.line, current, currentColumn)

    val newStart = editor.getLineStartOffset(snapshot.line)
    val newEnd = editor.getLineEndOffset(snapshot.line)
    editor.primaryCaret().moveToOffsetNative((newStart + snapshot.column).coerceIn(newStart, newEnd))
    return true
  }

  private fun caretColumn(editor: VimEditor, line: Int, lineStart: Int): Int {
    val offset = editor.primaryCaret().offset
    return if (editor.offsetToBufferPosition(offset).line == line) offset - lineStart else 0
  }
}
