/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.visualmulti

import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.common.ModeChangeListener
import com.maddyhome.idea.vim.extension.multiplecursors.isGlobalFlagEnabled
import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.state.mode.inVisualMode

/**
 * Whether a session is running, per buffer - `b:visual_multi` in vim-visual-multi.
 *
 * IdeaVim keeps this in IntelliJ's editor user data. The engine has no per-editor storage and
 * `IjVimEditor` throws from `equals`, so it is keyed by the path, which is what everything else in
 * this fork that needs per-buffer state does.
 */
private val sessions: MutableSet<String> = mutableSetOf()

/**
 * The multi-cursor session of a buffer.
 *
 * It starts with the first region and ends with Exit, or when the last region is removed.
 */
internal object VmSession {

  /**
   * vim-visual-multi keeps its own regions; here they are the carets and their selections, and the
   * host can remove those - so the regions are checked as well as the flag.
   */
  fun isActive(editor: VimEditor): Boolean = editor.key in sessions && hasRegions(editor)

  fun startIfRegionsCreated(editor: VimEditor) {
    if (hasRegions(editor)) sessions.add(editor.key)
  }

  fun end(editor: VimEditor) {
    sessions.remove(editor.key)
  }

  /** Drops every buffer's session, for `set novisual-multi`. */
  fun endAll(): Unit = sessions.clear()

  /** `VimEditor.carets` counts the carets of blockwise Visual mode as one, so that is not a session. */
  private fun hasRegions(editor: VimEditor): Boolean = editor.inVisualMode || editor.carets().size > 1

  private val VimEditor.key: String get() = getPath() ?: ""

  /**
   * Ends the session when the mode changes with at most one cursor - after the last region was
   * removed, or after leaving Visual mode with `v`.
   *
   * vim-visual-multi keeps a single cursor in Normal mode until the session is exited. These
   * selections are the host's own and nothing highlights the regions, so such a session would be
   * invisible and `n` would behave unexpectedly differently.
   *
   * With `g:VM_quit_after_leaving_insert_mode`, leaving Insert mode exits the session too.
   */
  object EndOnModeChange : ModeChangeListener {
    override fun modeChanged(editor: VimEditor, oldMode: Mode) {
      if (editor.carets().size <= 1) {
        end(editor)
      } else if (oldMode is Mode.INSERT && isActive(editor) && quitsAfterInsertMode()) {
        end(editor)
        editor.removeSecondaryCarets()
      }
    }

    private fun quitsAfterInsertMode(): Boolean =
      isGlobalFlagEnabled("VM_quit_after_leaving_insert_mode", default = false)
  }
}
