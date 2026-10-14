/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.multiplecursors

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.key.MappingOwner

private const val NEXT_WHOLE_OCCURRENCE = "<Plug>NextWholeOccurrence"

private const val NEXT_OCCURRENCE = "<Plug>NextOccurrence"

private const val SKIP_OCCURRENCE = "<Plug>SkipOccurrence"

private const val REMOVE_OCCURRENCE = "<Plug>RemoveOccurrence"

private const val ALL_WHOLE_OCCURRENCES = "<Plug>AllWholeOccurrences"

private const val ALL_OCCURRENCES = "<Plug>AllOccurrences"

/**
 * When set to `0`, the default key mappings are not created, allowing the user to map the `<Plug>` mappings to keys of
 * their own choosing (e.g. mapping back to the old `<A-n>` based mappings). This matches the
 * `g:multi_cursor_use_default_mapping` option of the original vim-multiple-cursors plugin.
 */
private const val USE_DEFAULT_MAPPING = "multi_cursor_use_default_mapping"

/**
 * Port of vim-multiple-cursors.
 *
 * See https://github.com/terryma/vim-multiple-cursors
 *
 * ## What moving it cost
 *
 * Carets, and less than it looked. `VimEditor` has had `addCaret(offset)` and `removeCaret` all
 * along, so the only thing IntelliJ's `CaretModel` was really being asked for was a caret at a
 * *visual* position - which is a buffer position here, and the two differ only where something is
 * folded or soft-wrapped. `updateCaretsVisualAttributes` goes: it repaints IntelliJ's carets for
 * the current mode, which both hosts already do when they flush.
 *
 * ## Why this file is now six
 *
 * The search, the selection helpers and the four handlers live beside this one, in the shape IdeaVim
 * gave them when it added `visual-multi`: that extension implements multiple cursors with the *same*
 * handlers and differs almost entirely in its mappings, so what used to be private to this file is
 * what the other one is built out of. The behaviour of these six mappings is unchanged - see
 * `MultipleCursorsTest`, which was written against the single-file version and was not touched.
 *
 * Three capabilities came with the split, and each is here because `visual-multi` needs it rather
 * than because this extension does: [CaseSensitivity], which vim-multiple-cursors answers "never"
 * to; [WriteActionHandler.acceptsCount], which its mappings do not take; and
 * [OccurrenceSearch.direction], which only Find Prev searches against.
 */
@VimPlugin(name = MULTIPLE_CURSORS)
public fun VimInitApi.init(): Unit = registerMultipleCursors()

/** Public because the plugin's extension-point adapter names it too. */
public const val MULTIPLE_CURSORS: String = "multiple-cursors"

/** Drops every buffer's search, for `set nomultiple-cursors`. */
public fun disposeMultipleCursors(): Unit = forgetOccurrenceSearches()

public fun registerMultipleCursors() {
  val owner = MappingOwner.Plugin.get(MULTIPLE_CURSORS)

  fun plug(modes: Set<MappingMode>, plug: String, handler: ExtensionHandler) {
    putExtensionHandlerMapping(modes, injector.parser.parseKeys(plug), owner, handler, false)
  }

  fun key(modes: Set<MappingMode>, keys: String, plug: String) {
    putKeyMappingIfMissing(modes, injector.parser.parseKeys(keys), owner, injector.parser.parseKeys(plug), true)
  }

  plug(MappingMode.NXO, NEXT_WHOLE_OCCURRENCE, NextOccurrenceHandler(wholeWord = true, AlwaysCaseSensitive))
  plug(MappingMode.NXO, NEXT_OCCURRENCE, NextOccurrenceHandler(wholeWord = false, AlwaysCaseSensitive))
  plug(MappingMode.NXO, ALL_WHOLE_OCCURRENCES, AllOccurrencesHandler(wholeWord = true, AlwaysCaseSensitive))
  plug(MappingMode.NXO, ALL_OCCURRENCES, AllOccurrencesHandler(wholeWord = false, AlwaysCaseSensitive))
  plug(MappingMode.X, SKIP_OCCURRENCE, SkipOccurrenceHandler(AlwaysCaseSensitive))
  plug(MappingMode.X, REMOVE_OCCURRENCE, RemoveOccurrenceHandler())

  if (isGlobalFlagEnabled(USE_DEFAULT_MAPPING)) {
    key(MappingMode.NXO, "<C-n>", NEXT_WHOLE_OCCURRENCE)
    key(MappingMode.NXO, "g<C-n>", NEXT_OCCURRENCE)
    key(MappingMode.NXO, "<A-n>", ALL_WHOLE_OCCURRENCES)
    key(MappingMode.NXO, "g<A-n>", ALL_OCCURRENCES)
    key(MappingMode.X, "<C-x>", SKIP_OCCURRENCE)
    key(MappingMode.X, "<C-p>", REMOVE_OCCURRENCE)
  }
}

/**
 * Whether a global flag variable such as `g:multi_cursor_use_default_mapping` is on. Any non-zero
 * value turns it on, and [default] is the answer when the variable is not set at all.
 */
internal fun isGlobalFlagEnabled(variableName: String, default: Boolean = true): Boolean =
  injector.variableService.getGlobalVariableValue(variableName)?.toVimNumber()?.booleanValue ?: default
