/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.sneak

import com.maddyhome.idea.vim.extension.VimExtension
import org.jetbrains.annotations.TestOnly

/**
 * The IntelliJ half of the vim-sneak port. The extension itself is in `vim-engine`, where both hosts
 * compile it; this keeps the `IdeaVIM.vimExtension` extension point working and goes when the plugin
 * does.
 *
 * `dispose` is here rather than left to the default because a sneak leaves a highlight behind for a
 * moment, and the loader's owner-based teardown knows nothing about it.
 */
internal class IdeaVimSneakExtension : VimExtension {

  override fun getName(): String = SNEAK

  override fun init() {
    registerSneak()
  }

  override fun dispose() {
    super.dispose()
    disposeSneak()
  }

  companion object {
    /**
     * Takes the highlight off now rather than when its timer fires.
     *
     * The tests call this because they assert on the highlighters and cannot wait 300ms for each
     * one. It used to stop a Swing `Timer` and run its listeners by hand; the timer is
     * `VimApplication.schedule` now, so the honest equivalent is to do what the callback would have.
     */
    @TestOnly
    @JvmStatic
    fun stopTimer() {
      disposeSneak()
    }
  }
}
