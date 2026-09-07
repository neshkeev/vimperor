/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

import java.text.Collator

/**
 * A fresh `Collator` per call, as the call site did before: the default locale can change while the
 * IDE is running, and `sort()` should follow it.
 */
actual fun localeCollator(): Comparator<String> {
  val collator = Collator.getInstance()
  return Comparator { a, b -> collator.compare(a, b) }
}
