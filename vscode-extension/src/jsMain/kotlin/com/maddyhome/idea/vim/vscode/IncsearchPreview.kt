/*
 * Copyright 2003-2026 The IdeaVim authors
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
 * What is deliberately not here is Vim's cursor preview. Vim moves the caret to the match while you
 * type and puts it back if you cancel; this scrolls the match into view and leaves the caret alone.
 * The caret is engine state, and a host that moved it would be lying to the engine about where the
 * user is for as long as the prompt was open - a much worse failure than not previewing the move.
 */
internal class IncsearchPreview(private val highlighter: Highlighter) {

  /** Whether anything is painted, so that cancelling only repaints when there is something to undo. */
  private var showing: Boolean = false

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

    val pattern = patternIn(text, label)
    if (pattern.isEmpty()) {
      clear(vsCode)
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
      return
    }

    showing = true
    highlighter.showMatches(vsCode, matches)
    val current = nextMatch(matches, vsCode.primaryCaret().offset, direction)
    highlighter.showCurrentMatch(vsCode, current)
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
  fun finish(editor: VimEditor) {
    if (!showing) return
    showing = false
    val vsCode = editor as? VsCodeEditor ?: return
    highlighter.showCurrentMatch(vsCode, null)
    injector.searchGroup.updateSearchHighlightsAfterGlobalCommand()
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
