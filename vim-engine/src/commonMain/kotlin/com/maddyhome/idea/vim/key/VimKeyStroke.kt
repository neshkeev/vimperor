/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.key

/**
 * A single keystroke: either a typed character or a pressed key, with modifiers.
 *
 * A platform-neutral replacement for `javax.swing.KeyStroke`, reproducing its observable behaviour
 * exactly. That is deliberate rather than conservative: keystrokes are map keys in
 * [KeyStrokeTrie] and in the mapping tables, so equality and hashing decide what a user's mapping
 * matches. `JdkKeyStrokeParityTest` holds this to AWT's behaviour.
 *
 * The two forms are distinguished the way AWT distinguishes them, by which field is undefined:
 *
 * | form | [keyChar] | [keyCode] |
 * |---|---|---|
 * | typed character, e.g. `a` | the character | [VimKeyCodes.VK_UNDEFINED] |
 * | pressed key, e.g. `<Enter>` | [VimKeyCodes.CHAR_UNDEFINED] | the key code |
 *
 * So a typed `a` and a pressed `VK_A` are *not* equal, even though `'A'.code == VK_A`.
 */
class VimKeyStroke private constructor(
  val keyChar: Char,
  val keyCode: Int,
  val modifiers: Int,
  /**
   * True if this stroke describes a key being released rather than pressed or typed.
   *
   * Nothing in the engine sets or reads this, but AWT compares on it and modal input builds
   * strokes from key-release events, which then reach macro registers. Keeping the field means a
   * released `<Enter>` never silently compares equal to a pressed one.
   */
  val onKeyRelease: Boolean,
) {
  /**
   * Which kind of key event this stroke describes, mirroring `KeyStroke.getKeyEventType()`.
   *
   * Derived, not stored: a stroke with no key code is a typed character, and otherwise the
   * release flag decides.
   */
  val keyEventType: Int
    get() = when {
      keyCode == VimKeyCodes.VK_UNDEFINED -> VimKeyCodes.KEY_TYPED
      onKeyRelease -> VimKeyCodes.KEY_RELEASED
      else -> VimKeyCodes.KEY_PRESSED
    }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is VimKeyStroke) return false
    return keyChar == other.keyChar && keyCode == other.keyCode &&
      modifiers == other.modifiers && onKeyRelease == other.onKeyRelease
  }

  override fun hashCode(): Int {
    var result = keyChar.hashCode()
    result = 31 * result + keyCode
    result = 31 * result + modifiers
    result = 31 * result + if (onKeyRelease) 1 else 0
    return result
  }

  override fun toString(): String = buildString {
    append("VimKeyStroke(")
    if (keyChar == VimKeyCodes.CHAR_UNDEFINED) append("keyCode=").append(keyCode)
    else append("keyChar=").append(keyChar).append(" (").append(keyChar.code).append(")")
    if (modifiers != 0) append(", modifiers=").append(modifiers)
    if (onKeyRelease) append(", onKeyRelease")
    append(")")
  }

  companion object {
    /**
     * Pairs of (legacy mask, modern mask) that AWT keeps in lockstep. See [normalizeModifiers].
     */
    private val MODIFIER_PAIRS = intArrayOf(
      1, 64, // shift
      2, 128, // ctrl
      4, 256, // meta
      8, 512, // alt
      32, 8192, // alt graph
    )

    /** Button masks AWT passes through untouched. */
    private const val BUTTON_MASKS = 1024 or 2048 or 4096

    /**
     * Reproduces AWT's modifier normalisation.
     *
     * `AWTKeyStroke` stores *both* the legacy and the modern bit for every modifier present, so
     * `getKeyStroke(VK_A, CTRL_DOWN_MASK)` reports `modifiers == 130`, not 128 - that is 128 plus
     * the legacy `CTRL_MASK` of 2. Bits belonging to neither scheme are dropped.
     *
     * This matters because keystrokes are map keys: without it, a stroke built from `128` and one
     * built from `130` would be unequal here while being the same stroke to AWT, and a mapping
     * added under one would not be found under the other.
     */
    @JvmStatic
    fun normalizeModifiers(modifiers: Int): Int {
      var result = modifiers and BUTTON_MASKS
      var i = 0
      while (i < MODIFIER_PAIRS.size) {
        val legacy = MODIFIER_PAIRS[i]
        val modern = MODIFIER_PAIRS[i + 1]
        if (modifiers and (legacy or modern) != 0) result = result or legacy or modern
        i += 2
      }
      return result
    }

    /** A typed character, with no modifiers. Mirrors `KeyStroke.getKeyStroke(char)`. */
    @JvmStatic
    fun getKeyStroke(keyChar: Char): VimKeyStroke =
      VimKeyStroke(keyChar, VimKeyCodes.VK_UNDEFINED, 0, false)

    /** A typed character with modifiers. Mirrors `KeyStroke.getKeyStroke(Character, int)`. */
    @JvmStatic
    fun getKeyStroke(keyChar: Char, modifiers: Int): VimKeyStroke =
      VimKeyStroke(keyChar, VimKeyCodes.VK_UNDEFINED, normalizeModifiers(modifiers), false)

    /** A pressed key. Mirrors `KeyStroke.getKeyStroke(int, int)`. */
    @JvmStatic
    fun getKeyStroke(keyCode: Int, modifiers: Int): VimKeyStroke =
      VimKeyStroke(VimKeyCodes.CHAR_UNDEFINED, keyCode, normalizeModifiers(modifiers), false)

    /** Mirrors `KeyStroke.getKeyStroke(int, int, boolean)`. */
    @JvmStatic
    fun getKeyStroke(keyCode: Int, modifiers: Int, onKeyRelease: Boolean): VimKeyStroke =
      VimKeyStroke(VimKeyCodes.CHAR_UNDEFINED, keyCode, normalizeModifiers(modifiers), onKeyRelease)

    /**
     * The general form, used by the host when translating a real key event.
     * Mirrors `KeyStroke.getKeyStroke(char, int, int, boolean)`'s stored shape.
     */
    @JvmStatic
    fun getKeyStroke(keyChar: Char, keyCode: Int, modifiers: Int, onKeyRelease: Boolean): VimKeyStroke =
      VimKeyStroke(keyChar, keyCode, normalizeModifiers(modifiers), onKeyRelease)
  }
}
