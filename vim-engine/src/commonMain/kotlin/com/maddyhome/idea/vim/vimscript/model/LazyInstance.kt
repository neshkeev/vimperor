/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model

/**
 * A value built on first use, deferring the cost until something actually needs it.
 *
 * [factory] is supplied by whoever knows how to make one. On the JVM that is a reflective
 * constructor call built by the providers; the point of taking a lambda is that this class no
 * longer has to know that.
 */
abstract class LazyInstance<T>(private val factory: () -> T) {
  open val instance: T by lazy { factory() }
}