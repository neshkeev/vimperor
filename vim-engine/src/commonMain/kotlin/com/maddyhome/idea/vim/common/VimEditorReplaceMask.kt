/*
 * Copyright 2003-2024 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.common

import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getLineEndForOffset
import com.maddyhome.idea.vim.api.injector

sealed interface ReplaceModeEdit {
  data class Overwrote(val original: Char) : ReplaceModeEdit

  data object Inserted : ReplaceModeEdit
}

/**
 * Vim's replace stack, keyed by the offset of the typed character rather than by insertion order.
 */
class VimEditorReplaceMask(private val editor: VimEditor) {
  private val edits = mutableMapOf<LiveRange, ReplaceModeEdit>()

  fun recordTypedCharacterAtCaret() {
    for (caret in editor.carets()) {
      val offset = caret.offset
      if (offset < editor.getLineEndForOffset(offset)) {
        record(offset, ReplaceModeEdit.Overwrote(editor.charAt(offset)))
      } else {
        record(offset, ReplaceModeEdit.Inserted)
      }
    }
  }

  fun recordLineBreakAtCaret() {
    for (caret in editor.carets()) {
      recordAutoIndentAndLineBreakBefore(caret.offset)
    }
  }

  private fun recordAutoIndentAndLineBreakBefore(caretOffset: Int) {
    var offset = caretOffset - 1
    while (offset >= 0 && isAutoIndentWhitespace(offset)) {
      record(offset, ReplaceModeEdit.Inserted)
      offset--
    }
    if (offset >= 0 && editor.charAt(offset) == '\n') {
      record(offset, ReplaceModeEdit.Inserted)
    }
  }

  private fun isAutoIndentWhitespace(offset: Int): Boolean {
    val char = editor.charAt(offset)
    return char != '\n' && char.isWhitespace()
  }

  /**
   * The edit recorded at [offset], removed.
   *
   * Searched for rather than looked up, which is not a pessimisation: building a key means asking
   * the editor for a *live marker*, and a live marker is something the host then has to move on
   * every subsequent edit. One per backspace, for a probe that is discarded on the next line. A
   * replace-mode stack holds a handful of entries, so scanning them costs nothing that matters.
   *
   * It also removes a trap. This used to depend on two markers over the same span comparing equal,
   * which is true of IntelliJ's range markers and was not true of the VS Code host's until a
   * fixture caught it - backspace in replace mode silently restored nothing for a whole phase.
   */
  fun popEditAt(offset: Int): ReplaceModeEdit? {
    val key = edits.keys.firstOrNull { it.startOffset == offset && it.endOffset == offset } ?: return null
    return edits.remove(key)
  }

  /** The markers this mask is keeping alive, so that a host can stop tracking them with it. */
  val markers: Collection<LiveRange> get() = edits.keys

  private fun record(offset: Int, edit: ReplaceModeEdit) {
    edits[editor.createLiveMarker(offset, offset)] = edit
  }
}

fun forgetAllReplaceMasks() {
  injector.editorGroup.getEditors().forEach { it.replaceMask = null }
}
