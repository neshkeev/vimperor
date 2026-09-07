/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.IncsearchPreviewRequest
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.incsearchPreviewRequest
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.pattern
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.helper.exitVisualMode
import com.maddyhome.idea.vim.state.mode.inVisualMode

/**
 * `'incsearch'` - the matches shown while a search is still being typed.
 *
 * This was written down as not portable, on the grounds that the preview needs the pattern as typed
 * so far and that arrives on the command line rather than through the search group. Both halves of
 * that are true and the conclusion was wrong: the command line here *is* the host's, a buffer this
 * module owns keystroke by keystroke, so the pattern as typed so far is a string it already has.
 * IntelliJ needs a document listener on a text field for this. There is no text field.
 *
 * It moves the caret too, which was written off here once on the grounds that a host moving the
 * caret would be "lying to the engine about where the user is". That was wrong twice over: Vim
 * itself moves the caret while you type and puts it back if you cancel, and the caret is how the
 * *selection* follows the preview - `ve/dolor` extends the Visual selection to the match, because
 * moving a caret in Visual mode is what moves the end of a selection. Fourteen of IdeaVim's
 * fixtures say so. The saved offset is the one every search starts from, so typing another
 * character re-searches from where the user was rather than from the previous match.
 *
 * `'incsearch'` is not only about `/` and `?`. Vim previews `:s`, `:g` and `:v` too, and those need
 * the command line taken apart - a range, a command name, a separator, and only then a pattern.
 * That parse is [incsearchPreviewRequest], in the engine where both hosts can reach it; it used to
 * be a private method of the plugin's `ExEntryPanel`, next to a Swing document listener, which is
 * the only reason this host previewed one prompt and not the other.
 */
internal class IncsearchPreview(private val highlighter: Highlighter) {

  /** Whether anything is painted, so that cancelling only repaints when there is something to undo. */
  private var showing: Boolean = false

  /**
   * Where the caret was when the prompt opened.
   *
   * Every keystroke searches from here rather than from wherever the last preview left the caret -
   * otherwise typing `d`, `o`, `l` in `/dolor` would walk forwards through the buffer one match per
   * character instead of narrowing the same search.
   */
  private var caretBefore: Int? = null

  /**
   * The pattern as typed so far, painted.
   *
   * Called on every command-line render, which is every keystroke - including the ones that only
   * move the caret or set a prompt character. Recomputing for those is wasted work and not wrong;
   * the search is over a buffer that a `/` prompt cannot change.
   */
  fun update(editor: VimEditor, label: String, text: String) {
    val vsCode = editor as? VsCodeEditor ?: return
    if (!injector.globalOptions().incsearch) return
    val from = caretBefore ?: vsCode.primaryCaret().offset.also { caretBefore = it }

    val request = incsearchPreviewRequest(vsCode, label, text)
    if (request !is IncsearchPreviewRequest.Show) {
      // `Reset` means `'hlsearch'` had something of its own on screen before the preview covered it
      // and this is the moment to give it back; `None` means there was never anything to show.
      clear(vsCode)
      restoreCaret(vsCode)
      if (request is IncsearchPreviewRequest.Reset) {
        injector.searchGroup.updateSearchHighlightsAfterGlobalCommand()
      }
      return
    }

    val pattern = request.pattern()
    if (pattern.isEmpty()) {
      clear(vsCode)
      restoreCaret(vsCode)
      return
    }

    // A command's own range is where it looks *and* where it starts looking: `:%s/dolor` previews
    // the first match in the file, not the first one after the caret. A search prompt has no range
    // and starts from where the prompt opened.
    val startLine = request.searchRange?.startLine ?: 0
    val endLine = request.searchRange?.endLine ?: -1

    // A pattern being typed is an unfinished one - `\(` on its way to `\(foo\)` is not a regex yet -
    // so a failure here is the normal state of a half-typed search, not an error to report.
    val matches = try {
      injector.searchHelper.findAll(vsCode, pattern, startLine, endLine, shouldIgnoreCase(pattern))
    } catch (e: Throwable) {
      emptyList()
    }

    if (matches.isEmpty()) {
      clear(vsCode)
      restoreCaret(vsCode)
      return
    }

    showing = true
    highlighter.showMatches(vsCode, matches)
    val current = if (request.isExCommand) {
      matches.first()
    } else {
      nextMatch(matches, from, directionOf(label) ?: 1)
    }
    highlighter.showCurrentMatch(vsCode, current)
    // A command's preview has nothing to do with the selection, so the selection goes: `V` then
    // `:<C-U>%s/foo` is a command over the whole file that happens to have been started from Visual
    // mode. `v` then `/foo` is the opposite - there the caret move *is* the selection move - which
    // is why this is only done for a command. Exiting Visual leaves the command line open, because
    // the engine's mode is Command-line with Visual pending.
    if (request.isExCommand && vsCode.inVisualMode) vsCode.exitVisualMode()
    // The engine's move rather than the native one, because in Visual mode moving the caret is what
    // moves the end of the selection - which is the whole of what this preview shows there.
    vsCode.primaryCaret().moveToOffset(current.startOffset)
    vsCode.scrollLineIntoView(vsCode.offsetToBufferPosition(current.startOffset).line)
  }

  /**
   * The prompt closed, either way.
   *
   * Whether the search ran or was cancelled, the preview is not what should be on screen
   * afterwards: an accepted search paints its own matches through the search group, and a cancelled
   * one leaves whatever `'hlsearch'` had before. Both are the search group's answer, so this only
   * undoes the preview and lets the engine repaint.
   */
  fun finish(editor: VimEditor, resetCaret: Boolean = true) {
    val vsCode = editor as? VsCodeEditor
    if (vsCode != null && resetCaret) restoreCaret(vsCode)
    caretBefore = null
    if (!showing) return
    showing = false
    if (vsCode == null) return
    highlighter.showCurrentMatch(vsCode, null)
    injector.searchGroup.updateSearchHighlightsAfterGlobalCommand()
  }

  /**
   * Puts the caret back where the prompt opened, without touching the selection.
   *
   * The native move, deliberately, and IdeaVim does the same: on `<CR>` the command line closes
   * before the search runs, so the search has to start from where the user was - but re-deriving
   * the selection from a caret that is only passing through would throw away what Visual mode is
   * holding.
   */
  private fun restoreCaret(editor: VsCodeEditor) {
    val offset = caretBefore ?: return
    if (editor.primaryCaret().offset != offset) editor.primaryCaret().moveToOffsetNative(offset)
  }

  private fun clear(editor: VsCodeEditor) {
    if (!showing) return
    showing = false
    highlighter.clear(editor)
    highlighter.showCurrentMatch(editor, null)
  }

  private companion object {
    /** Forwards for `/`, backwards for `?`; any other prompt is not a search. */
    fun directionOf(label: String): Int? = when (label) {
      "/" -> 1
      "?" -> -1
      else -> null
    }

    /**
     * The match a `<CR>` would land on: the next one after the caret, wrapping round the buffer.
     *
     * Vim wraps under `'wrapscan'` and stops at the end without it. Wrapping unconditionally here
     * is the smaller lie of the two - the preview is a preview, and showing the match that is about
     * to be jumped to is the point of it.
     */
    fun nextMatch(matches: List<TextRange>, caretOffset: Int, direction: Int): TextRange = if (direction > 0) {
      matches.firstOrNull { it.startOffset > caretOffset } ?: matches.first()
    } else {
      matches.lastOrNull { it.startOffset < caretOffset } ?: matches.last()
    }

    /** The same rule the search group paints by, so the preview and the result agree. */
    fun shouldIgnoreCase(pattern: String): Boolean {
      val options = injector.globalOptions()
      if (!options.ignorecase) return false
      if (options.smartcase && pattern.any { it.isUpperCase() }) return false
      return true
    }
  }
}
