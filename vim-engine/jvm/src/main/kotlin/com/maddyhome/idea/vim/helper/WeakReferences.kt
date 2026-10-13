/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

import java.lang.ref.WeakReference

/** The JDK's own, which is what IdeaVim uses directly. */
actual class VimWeakReference<T : Any> actual constructor(referent: T) {
  private val reference = WeakReference(referent)

  actual fun get(): T? = reference.get()
}
