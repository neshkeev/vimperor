/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.cleverf

import com.github.neshkeev.vimperor.common.VimCaretMovedListener
import com.maddyhome.idea.vim.api.VimDocument
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.ChangesListener
import com.maddyhome.idea.vim.common.ModeChangeListener
import com.maddyhome.idea.vim.state.mode.Mode

/**
 * Ends the searches of [session] when the cursor leaves the position the last search left it at, the text changes,
 * or Insert mode is entered. These are clever-f's `CursorMoved`, `TextChanged` and `InsertEnter` autocommands.
 *
 * ## Where the three signals come from here
 *
 * IdeaVim takes the first two off IntelliJ's event multicaster - one `CaretListener` and one `DocumentListener` over
 * every editor there is. This engine has neither as a global, and the two answers are different:
 *
 *  - **The cursor** is [VimCaretMovedListener], which is new. It is Vim's own `CursorMoved`: a comparison the host
 *    makes once per keystroke and once per selection it adopts, rather than a hook on one of the several routes a
 *    caret can move by. See that interface for why hooking `moveToOffset` would have been a half-answer.
 *  - **The text** needed nothing new. `ChangesListener` has been in the engine all along, one level down from the
 *    notifier and attached per document through `VimDocument` - which is exactly where IntelliJ's is too. This was
 *    recorded in CLAUDE.md as a missing seam for two passes because `VimListenersNotifier` was the only place
 *    anybody looked.
 *
 * A document listener is per document, so it is attached when a search starts and taken off when the searches end.
 * [CleverFSession] drives that, because it is the only thing that knows which document is being searched.
 */
internal class SearchFinalizer(private val session: CleverFSession) : VimCaretMovedListener, ModeChangeListener {
  private var watched: VimDocument? = null

  private val changes = object : ChangesListener {
    override fun documentChanged(change: ChangesListener.Change) {
      session.endSearches()
    }
  }

  fun attach() {
    injector.listenersNotifier.caretMovedListeners.add(this)
    injector.listenersNotifier.modeChangeListeners.add(this)
    session.finalizer = this
  }

  fun detach() {
    session.finalizer = null
    stopWatchingDocument()
    injector.listenersNotifier.caretMovedListeners.remove(this)
    injector.listenersNotifier.modeChangeListeners.remove(this)
  }

  /** Called when a search starts, so a change to the text it is searching ends it. */
  fun watchDocument(editor: VimEditor) {
    val document = editor.document
    if (watched === document) return
    stopWatchingDocument()
    document.addChangeListener(changes)
    watched = document
  }

  fun stopWatchingDocument() {
    watched?.removeChangeListener(changes)
    watched = null
  }

  override fun caretMoved(editor: VimEditor, oldOffset: Int, newOffset: Int) {
    val state = session.activeState ?: return
    if (state.isIn(editor) && !state.isAt(editor, newOffset)) session.endSearches()
  }

  override fun modeChanged(editor: VimEditor, oldMode: Mode) {
    if (editor.mode is Mode.INSERT || editor.mode is Mode.REPLACE) session.endSearches()
  }
}
