/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.api
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.resize.ResizeArgument

/**
 * How big the current window is: `<C-W>+`, `<C-W>-`, `<C-W><`, `<C-W>>`, `<C-W>_`, `<C-W>|`,
 * `<C-W>=` and `:resize`.
 *
 * Vim measures a window in rows and columns of text, which is why its commands say "three rows
 * taller" and "eighty columns wide". Neither host does: IntelliJ's splitters hold a *proportion*
 * and VS Code's hold a pixel size it will not name. So the arithmetic that turns "three rows" into
 * something the host can be told is the host's, and it is the whole of what this interface exists
 * for - the keys, the counts and the `:resize` argument forms are all the engine's, which is why
 * they moved there.
 *
 * [ResizeArgument] carries the three shapes Vim's commands come in, and a host is free to be honest
 * about not supporting one of them. [ResizeArgument.Absolute] is the one that usually cannot be
 * met: "make this window exactly twenty rows tall" needs a size the host will accept in rows, and
 * an editor that resizes in steps has no way to say it. Reporting that beats silently doing
 * something else.
 */
interface VimWindowResizeService {

  /** `<C-W>+`, `<C-W>-`, `<C-W>_`, and `:resize`. */
  fun resizeCurrentWindowHeight(editor: VimEditor, argument: ResizeArgument)

  /** `<C-W>>`, `<C-W><`, `<C-W>|`, and `:vertical resize`. */
  fun resizeCurrentWindowWidth(editor: VimEditor, argument: ResizeArgument)

  /** `<C-W>=` - make every window as nearly the same size as the host can manage. */
  fun equalizeWindows(editor: VimEditor)
}
