/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.common

import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.key.VimKeyStroke

/**
 * Notified about every key the user types, before any mapping is applied.
 *
 * Keys the engine feeds to itself - the right-hand side of a mapping, macro playback, `.` repeat, a
 * replayed mapping prefix - are not reported. This is the stream Vim writes with `-w {scriptout}`,
 * and [com.maddyhome.idea.vim.KeyHandler.handleUserKey] is the one entry point that reports it.
 */
interface VimKeyTypedListener : Listener {
  fun keyTyped(editor: VimEditor, key: VimKeyStroke)
}
