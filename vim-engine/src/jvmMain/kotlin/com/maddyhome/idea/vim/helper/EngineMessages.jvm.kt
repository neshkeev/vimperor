/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

import java.text.MessageFormat
import java.util.ResourceBundle

private val bundle: ResourceBundle by lazy {
  // Same module the bundle was always loaded from: EngineMessageHelper's own class, which is what
  // `::javaClass.get()` resolved to when this lived inside that object.
  ResourceBundle.getBundle(EngineMessageHelper.BUNDLE, EngineMessageHelper::class.java.module)
}

internal actual fun lookupEngineMessage(key: String, params: Array<out Any>): String {
  val pattern = bundle.getString(key)
  return if (params.isEmpty()) pattern else MessageFormat.format(pattern, *params)
}
