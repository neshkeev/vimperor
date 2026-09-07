/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.quickfix

import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.Key
import com.maddyhome.idea.vim.api.getOrPutWindowData

/**
 * One line of a compiler's or a grep's output, once it has been read.
 *
 * [path] is null for a line nothing could be made of, which Vim keeps in the list anyway: an error
 * spread over three lines shows all three, and only the one with a file behind it can be jumped to.
 */
data class QuickfixEntry(
  val path: String?,
  val line: Int,
  val column: Int,
  val text: String,
) {
  val isValid: Boolean get() = path != null
}

/**
 * Vim's quickfix list and location list, which are one thing kept in two places.
 *
 * The quickfix list belongs to the session and the location list belongs to a window - that is the
 * entire difference between `:copen` and `:lopen`, and between `:cnext` and `:lnext`. So there is
 * one list here and two places to keep it, the second of them in the window storage the engine
 * already has for window-local options.
 *
 * What is not here is `'errorformat'`. Vim's is a small pattern language with its own multi-line
 * and nesting rules, and this reads the four shapes that cover a compiler, a linter and `grep -n`
 * (see [parseQuickfixLines]). The option is declared so that a config setting it is read rather
 * than reported, and nothing consults it; that is written down at the option itself.
 */
class QuickfixList {
  private var entries: List<QuickfixEntry> = emptyList()

  /** The entry `:cc` would go to, counted from zero. */
  var index: Int = 0
    private set

  val size: Int get() = entries.size
  val isEmpty: Boolean get() = entries.isEmpty()

  fun all(): List<QuickfixEntry> = entries

  fun replaceWith(newEntries: List<QuickfixEntry>) {
    entries = newEntries
    index = 0
  }

  fun add(newEntries: List<QuickfixEntry>) {
    entries = entries + newEntries
  }

  fun at(position: Int): QuickfixEntry? = entries.getOrNull(position)

  /**
   * Moves to the entry [count] steps from here and returns it, or null when there is not one.
   *
   * Invalid entries - the lines with no file behind them - are stepped over rather than counted,
   * which is what makes `:cnext` walk errors rather than lines of output.
   */
  fun step(count: Int): QuickfixEntry? {
    if (entries.isEmpty()) return null
    val direction = if (count < 0) -1 else 1
    var position = index
    repeat(kotlin.math.abs(count)) {
      var next = position + direction
      while (next in entries.indices && !entries[next].isValid) next += direction
      if (next !in entries.indices) return null
      position = next
    }
    index = position
    return entries[position]
  }

  fun moveTo(position: Int): QuickfixEntry? {
    val entry = entries.getOrNull(position) ?: return null
    index = position
    return entry
  }

  /** The first or last entry that can be jumped to. */
  fun end(last: Boolean): QuickfixEntry? {
    val position = if (last) entries.indexOfLast { it.isValid } else entries.indexOfFirst { it.isValid }
    if (position < 0) return null
    index = position
    return entries[position]
  }
}

/**
 * The two lists, reached the way every command that uses them reaches them.
 *
 * The location list is stored on the window rather than on this object, so two split views of the
 * same file have one each - which is Vim's rule and the reason `:lopen` exists beside `:copen`.
 */
object Quickfix {
  private val locationListKey = Key<QuickfixList>("VimLocationList")

  /** The session's one quickfix list. */
  val quickfix: QuickfixList = QuickfixList()

  fun location(editor: VimEditor): QuickfixList =
    injector.vimStorageService.getOrPutWindowData(editor, locationListKey) { QuickfixList() }

  fun listFor(editor: VimEditor, isLocation: Boolean): QuickfixList =
    if (isLocation) location(editor) else quickfix

  /**
   * Empties the quickfix list, which is what a new session starts with.
   *
   * The list belongs to the session and a host builds one injector for one session, so this is
   * called from there. In a running editor it is a no-op at startup; what it is actually for is a
   * process that starts more than one session - a test run, where a list left behind by one test
   * would be found by the next.
   */
  fun reset() {
    quickfix.replaceWith(emptyList())
  }
}

/**
 * Compiler and grep output, read into entries.
 *
 * Four shapes, in the order they are tried, and they are the four that cover what anyone actually
 * pipes into a quickfix list:
 *
 * - `file:line:col: message` - clang, gcc since 4.x, eslint, ripgrep with `--column`
 * - `file:line: message` - `grep -n`, older compilers, most linters
 * - `file(line,col): message` and `file(line): message` - MSVC, and the shape Kotlin's compiler
 *   uses on Windows
 * - anything else - kept, with no file, so that a message spread over several lines still shows
 *
 * A Windows drive letter is the reason the first two are not a single regex over `:`: `C:\a.kt:3:1`
 * has four colons and the file name contains one of them.
 */
fun parseQuickfixLines(lines: List<String>): List<QuickfixEntry> =
  lines.filter { it.isNotBlank() }.map { line -> parseQuickfixLine(line.trimEnd('\r')) }

private val COLON_FORM = Regex("""^\s*((?:[A-Za-z]:)?[^:]+):(\d+):(?:(\d+):)?\s*(.*)$""")
private val PAREN_FORM = Regex("""^\s*((?:[A-Za-z]:)?[^(]+)\((\d+)(?:,\s*(\d+))?\)\s*:\s*(.*)$""")

private fun parseQuickfixLine(line: String): QuickfixEntry {
  COLON_FORM.matchEntire(line)?.let { match ->
    val (path, lineNumber, column, text) = match.destructured
    return QuickfixEntry(path, lineNumber.toIntOrNull() ?: 1, column.toIntOrNull() ?: 1, text)
  }
  PAREN_FORM.matchEntire(line)?.let { match ->
    val (path, lineNumber, column, text) = match.destructured
    return QuickfixEntry(path, lineNumber.toIntOrNull() ?: 1, column.toIntOrNull() ?: 1, text)
  }
  return QuickfixEntry(null, 0, 0, line)
}
