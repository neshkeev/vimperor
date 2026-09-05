/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.highlightedyank

import com.intellij.openapi.util.Disposer
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.extension.VimExtension

/**
 * The IntelliJ half of the vim-highlightedyank port. The extension itself is in `vim-engine`, where
 * both hosts compile it; this keeps the `IdeaVIM.vimExtension` extension point working and goes when
 * the plugin does.
 *
 * What is left here is IntelliJ's lifecycle rather than the extension's behaviour, and it is the
 * same dance the extension used to do inline. IdeaVim can be switched off from its status-bar icon
 * without the *extension* being disabled, and a highlight left on screen would outlive it - so the
 * highlights are cleared on the plugin's on/off disposable. There is no callback for being switched
 * back on and a disposable that has fired is spent, so it is registered again the next time a yank
 * is highlighted, which is what `onYankHighlighted` is for. See VIM-3419.
 */
internal class VimHighlightedYank : VimExtension {

  private var registered = false

  override fun getName(): String = HIGHLIGHTED_YANK

  override fun init() {
    registerHighlightedYank()
    onYankHighlighted = ::registerOnOffCallback
    registerOnOffCallback()
  }

  private fun registerOnOffCallback() {
    if (registered) return
    registered = true
    Disposer.register(VimPlugin.getInstance().onOffDisposable) {
      clearHighlightedYank()
      registered = false
    }
  }

  override fun dispose() {
    super.dispose()
    onYankHighlighted = null
    disposeHighlightedYank()
    registered = false
  }
}
