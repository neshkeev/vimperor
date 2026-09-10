/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.label

import com.github.neshkeev.vimperor.highlight.HighlightGroup
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.visualLineToBufferLine
import com.maddyhome.idea.vim.common.TextRange

/**
 * A label to draw over the text: the keys that still reach [offset], of which a host shows as many
 * as fit. vim-easymotion shows two.
 *
 * [highlight] arrives resolved, the bargain `:sign` and `:match` already make: a group the user
 * defined with `:highlight` is painted as they said, and one they never mentioned - null attributes -
 * is left to the host, which answers with its theme. A literal colour chosen here would be unreadable
 * in half the themes people use, and the first one chosen was: red, on VS Code's dark background.
 */
data class JumpLabel(val offset: Int, val keys: String, val highlight: HighlightGroup)

/**
 * Letters drawn *over* the text, which is what a jump motion asks of a host and nothing before it did.
 *
 * [com.maddyhome.idea.vim.thinapi.VimHighlightingService] paints a colour on a range, and `:sign`
 * draws in the gutter. Neither puts a character where another character is, and that is the whole
 * of `easymotion`: each target shows the key that reaches it in place of the character underneath,
 * and the rest of the searched text is dimmed so the labels read.
 *
 * The whole set arrives each time and replaces what was there - the model `:match` and `:sign`
 * already use - because labels are redrawn as each key narrows them, never edited in place.
 *
 * Defaulted rather than abstract, like [com.github.neshkeev.vimperor.sign.VimSignDisplay]: the
 * motion is the engine's, so a host that draws nothing still finds targets and still jumps. It just
 * shows nothing.
 */
interface VimJumpLabelDisplay {

  /** Replaces the labels [editor] shows with [labels], and dims [shaded] in the colours of [shade]. */
  fun showLabels(editor: VimEditor, labels: List<JumpLabel>, shaded: List<TextRange>, shade: HighlightGroup)

  /** Removes every label and every dimmed range from [editor]. */
  fun clearLabels(editor: VimEditor)

  /**
   * The buffer lines on screen, top to bottom, one range per run with nothing folded in between.
   *
   * A label nobody can see is a key nobody will press - and it costs more than that, because labels
   * are handed out nearest first, so a target off screen takes a short label away from one on it.
   * This default asks the engine for the top and bottom of the screen, which knows nothing about
   * folds. A host that can say which lines are really showing should.
   */
  fun visibleLines(editor: VimEditor): List<IntRange> {
    val helper = injector.engineEditorHelper
    val top = editor.visualLineToBufferLine(helper.getVisualLineAtTopOfScreen(editor))
    val bottom = editor.visualLineToBufferLine(helper.getVisualLineAtBottomOfScreen(editor))
    return listOf(top..bottom)
  }
}

/** A host that draws no labels. The motion still runs; see [VimJumpLabelDisplay]. */
object NoJumpLabelDisplay : VimJumpLabelDisplay {
  override fun showLabels(editor: VimEditor, labels: List<JumpLabel>, shaded: List<TextRange>, shade: HighlightGroup) {}
  override fun clearLabels(editor: VimEditor) {}
}
