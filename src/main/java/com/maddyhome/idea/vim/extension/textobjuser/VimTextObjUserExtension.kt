/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.textobjuser

import com.intellij.vim.api.VimInitApi
import com.maddyhome.idea.vim.extension.VimExtension

/**
 * A port of kana/vim-textobj-user: a framework that lets users declaratively define their own text
 * objects via `textobj#user#plugin({name}, {specs})`.
 *
 * The extension itself is in `vim-engine` now, as a `@VimPlugin` function both hosts compile; this
 * is the adapter that keeps the IntelliJ extension point working, and it goes when the plugin does.
 *
 * Unlike the other adapters it is not two lines, because this extension registers Vimscript function
 * handlers rather than mappings, and the engine tracks mappings by owner but not functions. So the
 * teardown has to name them, which is what `dispose` does here.
 *
 * See: https://github.com/kana/vim-textobj-user
 */
internal class VimTextObjUserExtension : VimExtension {

  override fun getName(): String = TEXT_OBJ_USER

  override fun init(initApi: VimInitApi) {
    registerTextObjUserFunctions()
  }

  override fun dispose() {
    unregisterTextObjUserFunctions()
  }
}
