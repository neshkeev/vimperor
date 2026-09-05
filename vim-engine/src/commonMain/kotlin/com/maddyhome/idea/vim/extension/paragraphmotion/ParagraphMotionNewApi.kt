/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.paragraphmotion

import com.intellij.vim.api.VimApi
import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.intellij.vim.api.getVariable
import com.intellij.vim.api.scopes.nmapPluginAction
import com.intellij.vim.api.scopes.omapPluginAction
import com.intellij.vim.api.scopes.xmapPluginAction

/**
 * `vim-paragraph-motion`: `{` and `}` that count a whitespace-only line as blank.
 *
 * Vim's own `{` and `}` stop only at a line with *nothing* on it, so a line holding two spaces is
 * not a paragraph boundary and the motion sails past it. This makes them stop there, which is what
 * anyone editing a file whose blank lines were left with trailing indentation actually wants.
 *
 * The body is the plugin's, unchanged. What moved is where it lives and how it registers: it was a
 * `VimExtension` on an IntelliJ extension point, and it is now a `@VimPlugin` function, which the
 * VS Code host can load because there is no classloader in it. The plugin keeps a thin adapter over
 * this same function so IntelliJ is unaffected.
 */
@VimPlugin(name = PARAGRAPH_MOTION)
public fun VimInitApi.init() {
  mappings {
    nmapPluginAction("}", PLUG_NEXT, keepDefaultMapping = true) { moveParagraph(1) }
    nmapPluginAction("{", PLUG_PREVIOUS, keepDefaultMapping = true) { moveParagraph(-1) }
    xmapPluginAction("}", PLUG_NEXT, keepDefaultMapping = true) { moveParagraph(1) }
    xmapPluginAction("{", PLUG_PREVIOUS, keepDefaultMapping = true) { moveParagraph(-1) }
    omapPluginAction("}", PLUG_NEXT, keepDefaultMapping = true) { moveParagraph(1) }
    omapPluginAction("{", PLUG_PREVIOUS, keepDefaultMapping = true) { moveParagraph(-1) }
  }
}

/**
 * `v:count1` rather than a parameter, because a motion's count is Vim's to supply: `3}` is three
 * paragraphs forward, and the count reaches an extension the same way it reaches a built-in.
 */
public fun VimApi.moveParagraph(direction: Int) {
  val count = getVariable<Int>("v:count1") ?: 1
  val actualCount = count * direction
  editor {
    change {
      forEachCaret {
        val newOffset = getNextParagraphBoundOffset(actualCount, includeWhitespaceLines = true)
        if (newOffset != null) {
          updateCaret(offset = newOffset)
        }
      }
    }
  }
}

/** Public because the plugin's extension-point adapter names them too. See ReplaceWithRegister. */
public const val PARAGRAPH_MOTION: String = "vim-paragraph-motion"
public const val PLUG_NEXT: String = "<Plug>(ParagraphNextMotion)"
public const val PLUG_PREVIOUS: String = "<Plug>(ParagraphPrevMotion)"
