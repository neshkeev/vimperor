/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.match
import com.maddyhome.idea.vim.annotations.TestOnly
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.helper.enumSetOf
import com.github.neshkeev.vimperor.highlight.Highlights
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

  /**
   * One standing highlight: what is lit, in what colour, and how loudly.
   *
   * Either a [pattern] or a list of [positions], never both, which is the difference between
   * `matchadd()` and `matchaddpos()`. The second exists for speed: a plugin that already knows
   * which words it wants lit should not make the engine find them again with a regex.
   */
  data class Match(
    val id: Int,
    val group: String,
    val pattern: String?,
    val positions: List<TextRange>? = null,
    val priority: Int = DEFAULT_PRIORITY,
  )

  /**
   * Vim's default `matchadd()` priority, and the one `:match` sits at.
   *
   * Which is why `:match` and a `matchadd()` at the default overlap in whichever order the host
   * decides - Vim breaks that tie by id, and neither host here paints one decoration "under"
   * another in a way an extension can choose.
   */
  const val DEFAULT_PRIORITY: Int = 10

  /**
   * The ids `:match`, `:2match` and `:3match` occupy, which `matchadd()` may not use.
   *
   * Vim reserves exactly these three, and this is the reason the two features are one table rather
   * than two: `getmatches()` lists a `:match` alongside everything a plugin added, and
   * `clearmatches()` takes them all off together.
   */
  const val RESERVED_IDS: Int = 3

  private val channels = mutableMapOf<String, MutableMap<Int, Match>>()

  fun set(editor: VimEditor, channel: Int, group: String, pattern: String) {
    val path = editor.getPath() ?: return
    channels.getOrPut(path) { mutableMapOf() }[channel] = Match(channel, group, pattern)
    repaint(editor)
  }

  /**
   * `matchadd()` and `matchaddpos()`. Returns the id, or -1 when [wantedId] is taken or reserved.
   *
   * Vim refuses rather than reassigning, which is right for a function whose whole purpose is to
   * hand back a handle: a plugin that asked for id 7 and silently got 8 would delete somebody
   * else's match later.
   */
  fun add(
    editor: VimEditor,
    group: String,
    pattern: String?,
    positions: List<TextRange>? = null,
    priority: Int = DEFAULT_PRIORITY,
    wantedId: Int = -1,
  ): Int {
    val path = editor.getPath() ?: return -1
    val here = channels.getOrPut(path) { mutableMapOf() }

    val id = if (wantedId > 0) {
      if (wantedId <= RESERVED_IDS || here.containsKey(wantedId)) return -1
      wantedId
    } else {
      ((here.keys.maxOrNull() ?: RESERVED_IDS) + 1).coerceAtLeast(RESERVED_IDS + 1)
    }

    here[id] = Match(id, group, pattern, positions, priority)
    repaint(editor)
    return id
  }

  /** `matchdelete()`. False when there was no such id, which the function reports as `E803`. */
  fun delete(editor: VimEditor, id: Int): Boolean {
    val path = editor.getPath() ?: return false
    if (channels[path]?.remove(id) == null) return false
    injector.matchHighlighter.clearMatches(editor, id)
    return true
  }

  /** `getmatches()` - everything showing here, highest priority first, as Vim orders it. */
  fun all(editor: VimEditor): List<Match> =
    channels[editor.getPath()].orEmpty().values.sortedWith(compareByDescending<Match> { it.priority }.thenBy { it.id })

  /** `clearmatches()` - every one of them, `:match` included. */
  fun clearAll(editor: VimEditor) {
    val path = editor.getPath() ?: return
    val ids = channels[path]?.keys?.toList().orEmpty()
    channels.remove(path)
    ids.forEach { injector.matchHighlighter.clearMatches(editor, it) }
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
      // A match made from positions is already ranges; only a pattern has to be found again. That
      // is the whole performance argument for `matchaddpos()` and it lives on this line.
      val ranges = match.positions ?: try {
        VimRegex(match.pattern ?: "").findAll(editor, 0, editor.text().length, options)
          .map { TextRange(it.range.startOffset, it.range.endOffset) }
      } catch (e: Throwable) {
        // A pattern that no longer compiles paints nothing rather than throwing on every keystroke,
        // which is what an exception here would become.
        emptyList()
      }
      injector.matchHighlighter.showMatches(editor, channel, Highlights.group(match.group), ranges)
    }
  }

  @TestOnly
  fun reset() {
    channels.clear()
  }
}
