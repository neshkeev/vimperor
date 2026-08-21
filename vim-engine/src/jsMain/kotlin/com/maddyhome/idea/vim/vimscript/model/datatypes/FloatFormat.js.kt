/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.datatypes

/**
 * Unimplemented on purpose.
 *
 * `FloatFormatTest` holds a 46-row table of exactly what this has to produce, generated from the
 * `DecimalFormat` implementation it replaces. Half-even rounding is defined over the *exact* binary
 * value of the double, and JS `toFixed` rounds half-away-from-zero on the decimal string, so it
 * does not satisfy the table. This is what `:echo` prints.
 *
 * A JS host has to reproduce that table, not approximate it.
 */
actual fun formatVimFloat(value: Double, scientific: Boolean): String =
  TODO("float formatting must reproduce FloatFormatTest's golden table on JS")
