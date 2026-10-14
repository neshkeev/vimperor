/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.cleverf

import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.state.mode.Mode

/**
 * Holds the searches of one clever-f instance. Like clever-f's `s:previous_*[mode]` dictionaries, each mode has its
 * own search, so a search in Normal mode is not repeated in Visual mode and vice versa.
 */
internal class CleverFSession {
  private val states = mutableMapOf<ModeKind, SearchState>()

  val highlighter = CandidateHighlighter()

  /**
   * Set by [SearchFinalizer] while it is attached, so a starting search can say which document to watch.
   *
   * It is here rather than the other way round because the session is the only thing that knows what is being
   * searched, and a `ChangesListener` is per document - IdeaVim needs neither, because IntelliJ's multicaster
   * reports every document there is.
   */
  var finalizer: SearchFinalizer? = null

  /** The search that moved the cursor last. Any cursor movement away from its position ends the searches. */
  var activeState: SearchState? = null
    private set

  /** The last searched character in any mode, used by `g:clever_f_repeat_last_char_inputs` */
  var lastChar: Char? = null
    private set

  fun stateFor(mode: Mode): SearchState? = states[ModeKind.of(mode)]

  fun startSearch(editor: VimEditor, map: Char, char: Char): SearchState {
    val state = SearchState(map, char, editor)
    states[ModeKind.of(editor.mode)] = state
    activeState = state
    lastChar = char
    return state
  }

  fun recordJump(state: SearchState, editor: VimEditor, offset: Int) {
    state.recordJump(editor, offset)
    activeState = state
  }

  /** Ends all searches, so the next `f`/`F`/`t`/`T` asks for a new character */
  fun endSearches() {
    states.clear()
    activeState = null
    highlighter.clear()
    finalizer?.stopWatchingDocument()
  }

  /** Ends all searches and forgets the last searched character */
  fun reset() {
    endSearches()
    lastChar = null
  }
}
