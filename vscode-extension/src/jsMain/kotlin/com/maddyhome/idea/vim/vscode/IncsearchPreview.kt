/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.TextRange

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
    val direction = directionOf(label) ?: return
    val from = caretBefore ?: vsCode.primaryCaret().offset.also { caretBefore = it }

    val pattern = patternIn(text, label)
    if (pattern.isEmpty()) {
      clear(vsCode)
      restoreCaret(vsCode)
      return
    }

    // A pattern being typed is an unfinished one - `\(` on its way to `\(foo\)` is not a regex yet -
    // so a failure here is the normal state of a half-typed search, not an error to report.
    val matches = try {
      injector.searchHelper.findAll(vsCode, pattern, 0, -1, shouldIgnoreCase(pattern))
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
    val current = nextMatch(matches, from, direction)
    highlighter.showCurrentMatch(vsCode, current)
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
     * The pattern out of what has been typed, which is not all of it: `/foo/e+1` searches for `foo`.
     *
     * Vim ends the pattern at the first unescaped separator, and the separator is whichever of `/`
     * or `?` opened the prompt. A trailing backslash escapes the separator that has not been typed
     * yet, so it belongs to the pattern.
     */
    fun patternIn(text: String, label: String): String {
      val separator = label.firstOrNull() ?: return text
      val end = StringBuilder()
      var index = 0
      while (index < text.length) {
        val character = text[index]
        if (character == '\\' && index + 1 < text.length) {
          end.append(character).append(text[index + 1])
          index += 2
          continue
        }
        if (character == separator) break
        end.append(character)
        index++
      }
      return end.toString()
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
