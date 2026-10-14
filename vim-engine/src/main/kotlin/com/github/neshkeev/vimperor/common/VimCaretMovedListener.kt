/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.common

import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.common.Listener

/**
 * Notified when the cursor has moved - Vim's `CursorMoved`, which `clever-f` ends a search on.
 *
 * ## Why this is checked rather than hooked
 *
 * IdeaVim gets this from IntelliJ's `CaretListener`, which fires from inside whatever moved the
 * caret. There is no equivalent here, and hooking the engine's own `VimCaret.moveToOffset` would be
 * a half-answer: a caret also moves through `moveToBufferPosition`, through a block selection
 * replacing every caret at once, and through the host adopting a selection the user made with the
 * mouse - so a listener on one of those routes would be quiet exactly when it mattered.
 *
 * Vim does not hook anything either. It compares the cursor's position against the last known one
 * when it gets back to the main loop, which is why `:h CursorMoved` says "not triggered when there
 * is typeahead" - and that comparison is what a host does here, once per keystroke and once per
 * selection it adopts. Complete, because it does not care *how* the cursor got there.
 *
 * ## What a listener may and may not do
 *
 * It runs inside the keystroke, so it owes the same restraint as [com.maddyhome.idea.vim.common.VimKeyTypedListener]:
 * cheap work only. And it must not move the caret, because the position it is being told about has
 * already been written out.
 *
 * [oldOffset] is where the primary caret was, so a listener can tell a jump from a nudge without
 * keeping its own copy.
 */
interface VimCaretMovedListener : Listener {
  fun caretMoved(editor: VimEditor, oldOffset: Int, newOffset: Int)
}
