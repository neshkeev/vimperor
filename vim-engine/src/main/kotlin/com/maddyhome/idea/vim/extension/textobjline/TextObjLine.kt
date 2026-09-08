/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.textobjline

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.intellij.vim.api.getVariable
import com.intellij.vim.api.scopes.TextObjectRange

/**
 * `vim-textobj-line`: `al` and `il` for the current line.
 *
 * - `al` is every character on the line, without the newline. `0v$h` by hand.
 * - `il` is the same without the leading and trailing whitespace. `^vg_` by hand.
 *
 * Both select nothing on an empty line, and `il` selects nothing on a line of only whitespace,
 * because there is no text on it to select. Returning null rather than an empty range is what makes
 * `dil` on a blank line do nothing instead of joining it to the next one.
 *
 * The pair earns its place for the same reason `ae`/`ie` do: `dd` is linewise and takes the newline
 * with it, so it is not the same operation as deleting the text of a line. `cil` retypes a line's
 * contents while keeping its indentation, which `cc` does only when `'autoindent'` agrees.
 *
 * `g:textobj_line_no_default_key_mappings` suppresses `al` and `il`, read once at registration
 * because that is when the mappings are made.
 *
 * A characterwise range in both cases. Vim's own `il` is characterwise and this follows it; a
 * linewise `al` would be `dd` again and would take the newline.
 */
@VimPlugin(name = TEXT_OBJ_LINE)
public fun VimInitApi.init() {
  val skipDefaults = getVariable<Boolean>("g:textobj_line_no_default_key_mappings") ?: false

  textObjects {
    register("al", registerDefaultMapping = !skipDefaults, preserveSelectionAnchor = false) { _ ->
      val line = editor { read { withPrimaryCaret { line } } }
      if (line.start == line.end) null else TextObjectRange.CharacterWise(line.start, line.end)
    }

    register("il", registerDefaultMapping = !skipDefaults, preserveSelectionAnchor = false) { _ ->
      val line = editor { read { withPrimaryCaret { line } } }
      // `Line.text` carries the newline; `end - start` is the length without it, and indexing past
      // that would find the newline itself as trailing whitespace on every line.
      val content = line.text.substring(0, line.end - line.start)

      val firstNonBlank = content.indexOfFirst { !it.isWhitespace() }
      if (firstNonBlank == -1) return@register null
      val lastNonBlank = content.indexOfLast { !it.isWhitespace() }

      TextObjectRange.CharacterWise(line.start + firstNonBlank, line.start + lastNonBlank + 1)
    }
  }
}

/** Public because the VS Code host names it in `VsCodeExtensions.BUNDLED`. */
public const val TEXT_OBJ_LINE: String = "textobj-line"
