/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.datatypes

/**
 * Renders a float the way `:echo` shows it: at least one fraction digit, at most six, half-even
 * rounding, and no grouping. When [scientific] the mantissa carries the same digit limits and the
 * exponent is separated by a lowercase `e`.
 *
 * This stays on the platform's formatter rather than being reimplemented, and that is a
 * considered choice. Half-even rounding is defined over the *exact* binary value of the double, so
 * getting it right needs arbitrary-precision decimal expansion, which common Kotlin has no
 * equivalent of - `Double.toString` gives the shortest round-tripping form, which is a different
 * answer. The output is user-visible, so an approximation would be a wrong answer rather than a
 * slightly different one.
 *
 * `FloatFormatTest` holds a golden table of inputs and outputs. It is the contract for any other
 * host: reproduce that table, do not merely look close.
 */
expect fun formatVimFloat(value: Double, scientific: Boolean): String
