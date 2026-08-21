/*
 * Copyright 2003-2025 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.intellij.vim.api.models

/**
 * Represents a color in RGBA format.
 *
 * @property hexCode The string representation of the color in hex format (#RRGGBB or #RRGGBBAA).
 */
data class Color(
  val hexCode: String,
) {
  /**
   * Creates a color from individual RGB(A) components.
   *
   * @param r Red component (0-255).
   * @param g Green component (0-255).
   * @param b Blue component (0-255).
   * @param a Alpha component (0-255), defaults to 255 (fully opaque).
   */
  constructor(r: Int, g: Int, b: Int, a: Int = 255) : this("#" + hex2(r) + hex2(g) + hex2(b) + hex2(a))

  /**
   * The red component of the color (0-255).
   */
  val r: Int = hexCode.substring(1..2).toInt(16)

  /**
   * The green component of the color (0-255).
   */
  val g: Int = hexCode.substring(3..4).toInt(16)

  /**
   * The blue component of the color (0-255).
   */
  val b: Int = hexCode.substring(5..6).toInt(16)

  /**
   * The alpha component of the color (0-255).
   * Defaults to 255 (fully opaque) if not specified in the hex code.
   */
  val a: Int = if (hexCode.length == 9) hexCode.substring(7..8).toInt(16) else 255

  init {
    require(hexCode.matches(Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$"))) {
      "Hex code should be in format #RRGGBB[AA]"
    }
  }
}

/**
 * One component as two lowercase hex digits, matching `String.format("%02x", value)`.
 *
 * `toUInt()` rather than `toString(16)` directly because Java formats `%x` as an unsigned 32-bit
 * value: `String.format("%02x", -1)` is `ffffffff`, where `(-1).toString(16)` would be `-1`.
 * Components are documented as 0-255, but nothing enforces that, so the out-of-range behaviour is
 * reproduced rather than quietly changed.
 */
private fun hex2(value: Int): String = value.toUInt().toString(16).padStart(2, '0')
