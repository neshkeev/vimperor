/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.multiplecursors

import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getText
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.options
import com.maddyhome.idea.vim.common.Direction
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.helper.SearchOptions
import com.maddyhome.idea.vim.helper.enumSetOf

/**
 * The search that adds the cursors, one occurrence at a time.
 *
 * The flags are decided when the search starts and kept while the next occurrences are added, so a
 * `FOO` found for `foo` must not turn `'smartcase'` case sensitive half way through.
 *
 * @param lastOccurrence what the last added cursor selected. When the current selection is something
 *   else, the user has selected something else, and a new search starts.
 * @param direction the direction of the last search, which skipping follows.
 */
internal data class OccurrenceSearch(
  val wholeWord: Boolean,
  val ignoreCase: Boolean,
  val lastOccurrence: TextRange,
  val direction: Direction = Direction.FORWARDS,
)

/**
 * The search, per buffer.
 *
 * IdeaVim keeps this in IntelliJ's editor user data. The engine has no per-editor storage and
 * `IjVimEditor` throws from `equals`, so it cannot be a map key either - the path is what both hosts
 * agree on, which is how `exchange` and the rest of this extension key their own state.
 */
private val searches: MutableMap<String, OccurrenceSearch?> = mutableMapOf()

internal var VimEditor.occurrenceSearch: OccurrenceSearch?
  get() = searches[getPath() ?: ""]
  set(value) {
    searches[getPath() ?: ""] = value
  }

/** Drops every buffer's search, for `set nomultiple-cursors` and `set novisual-multi`. */
internal fun forgetOccurrenceSearches(): Unit = searches.clear()

/** Starts a search for the text of [selection], without adding a cursor yet. */
internal fun startOccurrenceSearch(
  editor: VimEditor,
  selection: TextRange,
  wholeWord: Boolean,
  caseSensitivity: CaseSensitivity,
): OccurrenceSearch {
  val ignoreCase = caseSensitivity.ignoresCase(editor.getText(selection))
  return OccurrenceSearch(wholeWord, ignoreCase, selection).also { editor.occurrenceSearch = it }
}

/**
 * The start offset of the occurrence of [text] after [current], or before it when searching
 * backwards, or null when there is none. The search wraps around if `'wrapscan'` is set.
 */
internal fun findOccurrence(
  editor: VimEditor,
  current: TextRange,
  text: String,
  wholeWord: Boolean,
  ignoreCase: Boolean,
  direction: Direction,
): Int? {
  val searchOptions = enumSetOf(SearchOptions.WHOLE_FILE)
  if (injector.options(editor).wrapscan) {
    searchOptions.add(SearchOptions.WRAP)
  }
  if (direction == Direction.BACKWARDS) {
    searchOptions.add(SearchOptions.BACKWARDS)
  }
  // A selection ends after its last character, so a forward search starts *at* that last character
  // to step over the occurrence it is already on.
  val startOffset = if (direction == Direction.FORWARDS) current.endOffset - 1 else current.startOffset
  val pattern = occurrencePattern(text, wholeWord, ignoreCase)
  return injector.searchHelper.findPattern(editor, pattern, startOffset, 1, searchOptions)?.startOffset
}

internal fun findAllOccurrences(
  editor: VimEditor,
  text: String,
  wholeWord: Boolean,
  ignoreCase: Boolean,
): List<TextRange> {
  val pattern = occurrencePattern(text, wholeWord, ignoreCase)
  return injector.searchHelper.findAll(editor, pattern, 0, -1, false)
}

/**
 * The text is matched literally, with "very nomagic", where only the backslash and the newline need
 * escaping. The case is forced with `\c` or `\C`, which takes precedence over `'ignorecase'` and
 * `'smartcase'` - so the flags decided when the search started are the ones that apply.
 */
private fun occurrencePattern(text: String, wholeWord: Boolean, ignoreCase: Boolean): String {
  val escapedText = text.replace("\\", "\\\\").replace("\n", "\\n")
  val case = if (ignoreCase) "\\c" else "\\C"
  val body = if (wholeWord) "\\<$escapedText\\>" else escapedText
  return "\\V$case$body"
}
