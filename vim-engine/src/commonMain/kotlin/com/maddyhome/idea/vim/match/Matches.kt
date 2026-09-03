/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.match

import com.maddyhome.idea.vim.annotations.TestOnly
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.helper.enumSetOf
import com.maddyhome.idea.vim.regexp.VimRegex
import com.maddyhome.idea.vim.regexp.VimRegexOptions

/**
 * The patterns `:match`, `:2match` and `:3match` are showing, and the repainting of them.
 *
 * Vim's `:match` is a *standing* highlight: you name a pattern once and every occurrence stays lit
 * as you edit, which is the whole difference between it and a search. So the pattern is what is
 * kept here, and the ranges are recomputed rather than stored - a stored range is wrong the moment
 * a character is typed above it.
 *
 * Recomputed from [repaint], which the key handler calls after every keystroke. That sounds
 * expensive and is not: the first line of it returns when nothing is matching, which is the state
 * every session is in until someone types `:match`, and the cost after that is one regex pass over
 * one buffer - the same thing `'hlsearch'` already does on every keystroke.
 *
 * Per window in Vim, and per editor path here, which is the same thing in a host where a file has
 * one editor. Two windows onto one file share their `:match` here and would not in Vim; that is a
 * divergence worth knowing and not one worth a second identity for.
 */
object Matches {

  /** A channel's pattern, and the highlight group it is painted in. */
  data class Match(val group: String, val pattern: String)

  private val channels = mutableMapOf<String, MutableMap<Int, Match>>()

  fun set(editor: VimEditor, channel: Int, group: String, pattern: String) {
    val path = editor.getPath() ?: return
    channels.getOrPut(path) { mutableMapOf() }[channel] = Match(group, pattern)
    repaint(editor)
  }

  fun clear(editor: VimEditor, channel: Int) {
    val path = editor.getPath() ?: return
    channels[path]?.remove(channel)
    injector.matchHighlighter.clearMatches(editor, channel)
  }

  fun current(editor: VimEditor, channel: Int): Match? = channels[editor.getPath()]?.get(channel)

  /**
   * Repaints every channel of [editor].
   *
   * Called after each keystroke, so the first thing it does is find out whether there is anything
   * to do. A session that has never used `:match` pays one map lookup per key.
   */
  fun repaint(editor: VimEditor) {
    if (channels.isEmpty()) return
    val showing = channels[editor.getPath()] ?: return
    if (showing.isEmpty()) return

    val options = enumSetOf<VimRegexOptions>()
    if (injector.globalOptions().smartcase) options.add(VimRegexOptions.SMART_CASE)
    if (injector.globalOptions().ignorecase) options.add(VimRegexOptions.IGNORE_CASE)

    for ((channel, match) in showing) {
      val ranges = try {
        VimRegex(match.pattern).findAll(editor, 0, editor.text().length, options)
          .map { TextRange(it.range.startOffset, it.range.endOffset) }
      } catch (e: Throwable) {
        // A pattern that no longer compiles paints nothing rather than throwing on every keystroke,
        // which is what an exception here would become.
        emptyList()
      }
      injector.matchHighlighter.showMatches(editor, channel, match.group, ranges)
    }
  }

  @TestOnly
  fun reset() {
    channels.clear()
  }
}
