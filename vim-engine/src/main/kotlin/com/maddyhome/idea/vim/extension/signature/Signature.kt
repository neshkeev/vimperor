/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.signature

import com.github.neshkeev.vimperor.sign.Signs
import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.maddyhome.idea.vim.api.VimMarkService
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.VimMarkListener

/**
 * `vim-signature`: the local marks of a file, shown in the gutter.
 *
 * Vim's `a`-`z` marks are invisible - `:marks` lists them and nothing on screen says a line holds
 * one - which is the whole reason kshenoy's plugin exists. Global marks `A`-`Z` are left alone: on
 * IntelliJ they are IDE bookmarks, which the platform draws itself, and drawing over them would be
 * two icons for one thing.
 *
 * ## It is signs, not a second drawing mechanism
 *
 * Upstream draws IntelliJ `RangeHighlighter`s with a `GutterIconRenderer` straight onto the markup
 * model, which is the one part of that port no other host can follow. This uses Vim's own signs
 * instead - which is what the real plugin does, and what `:h sign-place` is for - so the gutter is
 * reached the way every other sign reaches it, and both hosts draw it with code they already had.
 *
 * The signs go in a sign *group*, `signature`, which is exactly what Vim 8 added groups for: a
 * user's `:sign unplace *` is group-scoped and cannot sweep these away, and `:sign unplace` here
 * cannot sweep away the user's.
 *
 * ## What is redrawn, and when
 *
 * Everything, on any change to a lowercase mark. There is no attempt to move one sign: marks are
 * few, a redraw is a map and a list, and [Signs.repaint] already declines to touch the host when
 * nothing it would draw has changed.
 *
 * [VimMarkListener.marksChanged] passes null when it does not know what changed, and the engine
 * writes `.`, `^`, `'`, `[` and `]` on almost every edit - so the char is checked first and most
 * notifications cost nothing.
 */
@VimPlugin(name = SIGNATURE)
public fun VimInitApi.init() {
  injector.listenersNotifier.markListeners.add(SignatureListener)
  SignatureListener.marksChanged(null)
}

/** Public because the plugin's extension-point adapter names it too. */
public const val SIGNATURE: String = "signature"

/** The sign group these are placed in, so `:sign unplace *` cannot reach them. */
private const val GROUP: String = "signature"

/** Removes the listener and every sign it placed. See `VsCodeExtensions.TEARDOWN`. */
public fun disposeSignature() {
  injector.listenersNotifier.markListeners.remove(SignatureListener)
  Signs.unplace(group = GROUP)
  injector.editorGroup.getEditors().forEach { Signs.repaint(it) }
}

private object SignatureListener : VimMarkListener {

  override fun marksChanged(markChar: Char?) {
    if (markChar != null && markChar !in VimMarkService.LOWERCASE_MARKS) return

    for (editor in injector.editorGroup.getEditors()) {
      val path = editor.getPath() ?: continue

      // Every one of this file's signs goes, then the marks that are there now are placed. Doing it
      // by difference would need the old set kept somewhere, and there is nowhere honest to keep it
      // that a second window on the same file would not disagree with.
      Signs.unplace(path = path, group = GROUP)

      injector.markService.getAllLocalMarks(editor.primaryCaret())
        .filter { it.key in VimMarkService.LOWERCASE_MARKS }
        // A mark is a line number that outlives the text: local marks persist, so a file that has
        // shrunk since one was set can carry a mark past its end. Vim's `:marks` shows those and
        // `'a` reports E19; a sign on a line that is not there is a drawing bug, so it is dropped.
        .filter { it.line in 0 until editor.lineCount() }
        .forEach { mark ->
          Signs.define(signNameFor(mark.key)) { it.copy(text = mark.key.toString()) }
          Signs.place(
            Signs.Placement(
              // Stable per letter, so the same mark keeps its id across redraws.
              id = mark.key - 'a' + 1,
              group = GROUP,
              name = signNameFor(mark.key),
              path = path,
              line = mark.line,
              priority = Signs.DEFAULT_PRIORITY,
            ),
          )
        }

      Signs.repaint(editor)
    }
  }

  private fun signNameFor(mark: Char): String = "signature_$mark"
}
