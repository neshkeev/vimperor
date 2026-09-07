/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.textobjentire

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.intellij.vim.api.getVariable
import com.intellij.vim.api.scopes.TextObjectRange

/**
 * `vim-textobj-entire`: `ae` and `ie` for the whole buffer.
 *
 * `dae` deletes everything, `yie` yanks everything that is not leading or trailing whitespace. The
 * pair is worth having because `ggVG` leaves you in visual mode and does not compose with an
 * operator the way a text object does - `gUie` upper-cases the file and stays in normal mode.
 *
 * `g:textobj_entire_no_default_mappings` suppresses `ae` and `ie` for anyone who wants the
 * `<Plug>` targets on their own keys, which is read once at registration because that is when the
 * mappings are made.
 *
 * The body is the plugin's, unchanged; only the registration moved. See the paragraph-motion port
 * for why, and `VsCodeExtensions` for what the VS Code host does with it.
 */
@VimPlugin(name = TEXT_OBJ_ENTIRE)
public fun VimInitApi.init() {
  val skipDefaults = getVariable<Boolean>("g:textobj_entire_no_default_mappings") ?: false
  textObjects {
    register("ae", registerDefaultMapping = !skipDefaults) { _ ->
      TextObjectRange.CharacterWise(0, editor { read { textLength.toInt() } })
    }
    register("ie", registerDefaultMapping = !skipDefaults) { _ ->
      val content = editor { read { text.toString() } }
      var start = 0
      var end = content.length
      for (i in content.indices) {
        if (!content[i].isWhitespace()) {
          start = i
          break
        }
      }
      for (i in content.indices.reversed()) {
        if (!content[i].isWhitespace()) {
          end = i + 1
          break
        }
      }
      TextObjectRange.CharacterWise(start, end)
    }
  }
}

/** Public because the plugin's extension-point adapter names it too. */
public const val TEXT_OBJ_ENTIRE: String = "textobj-entire"
