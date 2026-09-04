/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.sign

import com.maddyhome.idea.vim.annotations.TestOnly
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.highlight.Highlights

/**
 * The signs that have been defined and the places they have been put.
 *
 * Two tables, and Vim keeps them apart for a reason worth keeping: a *definition* says what a mark
 * looks like and a *placement* says where one is. One definition serves any number of placements,
 * which is what makes `:sign define` a thing a config does once and `:sign place` a thing something
 * else does per line.
 *
 * Placements are keyed by file path rather than by buffer number. Vim's own commands accept either
 * and this fork has no buffers - `:ls` numbers what the host has open, and those numbers move when
 * a tab closes, so a sign that remembered one would end up on a different file. A path does not
 * move.
 *
 * Repainting is deliberately not what `:match` does. A standing highlight has to be recomputed after
 * every keystroke because its ranges are derived from the text; a sign sits on a line, and both
 * hosts' markers already follow a line as text is inserted above it. So [repaint] hands the host a
 * new list only when the list has actually changed, and hands it nothing at all when it has not -
 * which is what keeps the per-keystroke call from undoing the host's own tracking.
 */
object Signs {

  /** What a sign looks like. Vim's `:sign define` arguments, one field each. */
  data class Definition(
    val name: String,
    /** One or two printable characters in the gutter, when there is no icon. */
    val text: String? = null,
    /** Highlight group for [text]. */
    val textHighlight: String? = null,
    /** Highlight group for the whole line - "most useful is defining a background color". */
    val lineHighlight: String? = null,
    /** Highlight group for the line *number*, which not every host can colour separately. */
    val numberHighlight: String? = null,
    /** Highlight group for [text] when the caret is on the line and `'cursorline'` is set. */
    val cursorLineHighlight: String? = null,
    /** A bitmap file. Neither host is a GUI toolkit; this is carried and reported, not drawn. */
    val icon: String? = null,
    val priority: Int = DEFAULT_PRIORITY,
  )

  /** Where a sign is. [line] is Vim's, counting from one. */
  data class Placement(
    val id: Int,
    val group: String,
    val name: String,
    val path: String,
    val line: Int,
    val priority: Int,
  )

  /** Vim's default `sign-priority`. */
  const val DEFAULT_PRIORITY: Int = 10

  /** The group a sign with no `group=` belongs to. Vim calls it the global group and spells it "". */
  const val GLOBAL_GROUP: String = ""

  private val definitions = mutableMapOf<String, Definition>()
  private val placements = mutableListOf<Placement>()
  /**
   * What each file's host was last handed, which is what makes [repaint] cheap and correct.
   *
   * Never pruned when a sign is removed, and that is the point: taking the last-painted list away
   * would make "no signs anywhere" indistinguishable from "no signs any more", and the second one
   * still has to reach the host so it can take the old ones off the screen.
   */
  private val lastPainted = mutableMapOf<String, List<PlacedSign>>()

  // ---- definitions -------------------------------------------------------------------------------

  /**
   * Defines [name], or changes what is already there.
   *
   * Vim merges: "define a new sign **or set attributes for an existing sign**", so a second
   * `:sign define` with one argument changes that one argument and leaves the rest. [changes] is
   * applied over whatever was there.
   */
  fun define(name: String, changes: (Definition) -> Definition) {
    val key = normalise(name)
    definitions[key] = changes(definitions[key] ?: Definition(name = key))
  }

  /** `:sign undefine`. False when there was nothing by that name, which is Vim's `E155`. */
  fun undefine(name: String): Boolean = definitions.remove(normalise(name)) != null

  fun definition(name: String): Definition? = definitions[normalise(name)]

  /** In the order they were defined, which is the order `:sign list` prints them. */
  fun definitions(): List<Definition> = definitions.values.toList()

  /**
   * Vim: "leading zeros are ignored, thus 0012, 012 and 12 are considered the same name".
   *
   * Only for an all-digit name - a sign called `007bond` keeps its zeros, because the rule is about
   * signs named by number and not about names that happen to start with one.
   */
  private fun normalise(name: String): String =
    if (name.isNotEmpty() && name.all { it.isDigit() }) name.trimStart('0').ifEmpty { "0" } else name

  // ---- placements --------------------------------------------------------------------------------

  /** `:sign place`. An id already in this group and file is moved rather than duplicated. */
  fun place(placement: Placement) {
    placements.removeAll { it.id == placement.id && it.group == placement.group && it.path == placement.path }
    placements.add(placement)
  }

  /** Everything matching, in the order `:sign place` lists them: by line, then by id. */
  fun placed(path: String? = null, group: String? = null, id: Int? = null): List<Placement> =
    placements
      .filter { (path == null || it.path == path) && (group == null || it.group == group) && (id == null || it.id == id) }
      .sortedWith(compareBy({ it.line }, { it.id }))

  /** `:sign unplace`. Returns how many went, so the caller can tell "none matched" from "done". */
  fun unplace(path: String? = null, group: String? = null, id: Int? = null): Int {
    val going = placed(path, group, id)
    placements.removeAll(going)
    return going.size
  }

  /** The next id `:sign place` should hand out when the command did not name one. */
  fun nextId(): Int = (placements.maxOfOrNull { it.id } ?: 0) + 1

  // ---- painting ----------------------------------------------------------------------------------

  /**
   * Hands [editor]'s host the signs for its file, if they have changed since the last time.
   *
   * Called from the same two places `:match` repaints from, and the "if they have changed" is the
   * whole difference between the two. `:match` must recompute after every keystroke because its
   * ranges come from the text. A sign is on a line, and a host's marker follows that line as text
   * is inserted above it - so repainting unchanged signs on every key would move them back to where
   * they were placed and undo exactly the tracking the host is doing.
   */
  fun repaint(editor: VimEditor) {
    if (placements.isEmpty() && lastPainted.isEmpty()) return
    val path = editor.getPath() ?: return
    val wanted = placed(path = path).map { it.resolve() }
    if (lastPainted[path] == wanted) return
    lastPainted[path] = wanted
    injector.signDisplay.showSigns(editor, wanted)
  }

  /**
   * A placement plus its definition, with the highlight groups already looked up.
   *
   * Resolved here rather than in each host for the reason `:match` resolves its group here: a host
   * should be handed colours it can paint, and `:highlight` is the only thing that knows whether
   * `texthl=Search` means the user's red or the theme's find colour.
   */
  private fun Placement.resolve(): PlacedSign {
    val definition = definitions[normalise(name)]
    return PlacedSign(
      id = id,
      group = group,
      line = line,
      text = definition?.text,
      textHighlight = definition?.textHighlight?.let { Highlights.group(it) },
      lineHighlight = definition?.lineHighlight?.let { Highlights.group(it) },
      numberHighlight = definition?.numberHighlight?.let { Highlights.group(it) },
      priority = priority,
    )
  }

  @TestOnly
  fun reset() {
    definitions.clear()
    placements.clear()
    lastPainted.clear()
  }
}
