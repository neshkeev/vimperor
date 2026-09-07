/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

// The common tree has a file of this name too, and on the JVM a file's top-level
// declarations land in a facade class named after it - so both would generate
// `CollationKt` and the compilation fails. The names are deliberately identical, an
// `actual` beside its `expect`, so the facade moves instead of the file.
@file:JvmName("CollationJvm")

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
