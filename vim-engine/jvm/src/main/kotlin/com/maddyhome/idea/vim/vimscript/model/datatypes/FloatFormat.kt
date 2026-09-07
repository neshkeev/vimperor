/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

// The common tree has a file of this name too, and on the JVM a file's top-level
// declarations land in a facade class named after it - so both would generate
// `FloatFormatKt` and the compilation fails. The names are deliberately identical, an
// `actual` beside its `expect`, so the facade moves instead of the file.
@file:JvmName("FloatFormatJvm")

package com.maddyhome.idea.vim.vimscript.model.datatypes

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

actual fun formatVimFloat(value: Double, scientific: Boolean): String {
  val pattern = if (scientific) "0.0#####E0" else "0.0#####"
  val symbols = DecimalFormatSymbols.getInstance(Locale.ROOT).apply {
    exponentSeparator = "e"
  }
  return DecimalFormat(pattern, symbols).format(value)
}
