/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.datatypes

import com.maddyhome.idea.vim.ex.exExceptionMessage
import kotlin.math.abs

/**
 * Represents a Vim Float
 *
 * This type has value semantics. I.e., two instances of [VimFloat] are considered equal if they have the same
 * underlying value.
 *
 * A Vim Float cannot be converted to a Number or String.
 */
data class VimFloat(val value: Double) : VimDataType("float") {
  override fun toVimFloat() = this

  override fun toVimNumber(): VimInt {
    throw exExceptionMessage("E805")
  }

  override fun toVimString(): VimString {
    throw exExceptionMessage("E806")
  }

  override fun toOutputString(): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
    // Note the branch is decided on the unrounded value, so 999999.9999999 formats as 1000000.0
    // rather than switching to scientific notation. That is existing behaviour, preserved.
    val tooBigOrTooSmall = abs(value) >= 1e6 || (abs(value) < 1e-3 && value != 0.0)
    return formatVimFloat(value, scientific = tooBigOrTooSmall)
  }

  override fun copy() = VimFloat(value)

  override fun lockVar(depth: Int) {
    this.isLocked = true
  }

  override fun unlockVar(depth: Int) {
    this.isLocked = false
  }
}
