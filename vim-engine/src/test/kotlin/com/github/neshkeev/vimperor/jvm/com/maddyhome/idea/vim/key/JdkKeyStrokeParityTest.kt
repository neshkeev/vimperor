/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.key

import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds [VimKeyStroke] and [VimKeyCodes] to the behaviour of `javax.swing.KeyStroke`, which they
 * replace.
 *
 * Keystrokes are map keys in the mapping tables, and their numeric values reach `.ideavimrc`,
 * macro registers and saved shortcut owners. A divergence here would not crash: it would silently
 * change which mapping a key matches. So these assert against AWT itself rather than against
 * transcribed expectations, while AWT is still on the classpath to be asked.
 */
class JdkKeyStrokeParityTest {

  private fun assertSameStroke(expected: KeyStroke, actual: VimKeyStroke, label: String) {
    assertEquals(expected.keyChar, actual.keyChar, "keyChar for " + label)
    assertEquals(expected.keyCode, actual.keyCode, "keyCode for " + label)
    assertEquals(expected.modifiers, actual.modifiers, "modifiers for " + label)
  }

  @Test
  fun `test modifier normalisation matches AWT across the whole 16-bit space`() {
    // AWT stores both the legacy and the modern bit for each modifier, so CTRL_DOWN_MASK (128)
    // is stored as 130. Exhaustive rather than sampled, because this decides map-key equality.
    for (modifiers in 0..0xFFFF) {
      val expected = KeyStroke.getKeyStroke(KeyEvent.VK_A, modifiers).modifiers
      assertEquals(expected, VimKeyStroke.normalizeModifiers(modifiers), "modifiers=" + modifiers)
    }
  }

  @Test
  fun `test typed-character strokes match AWT`() {
    val chars = (0..0x2FF).map { it.toChar() } + listOf('\uFFFE', VimKeyCodes.CHAR_UNDEFINED)
    for (c in chars) {
      assertSameStroke(KeyStroke.getKeyStroke(c), VimKeyStroke.getKeyStroke(c), "char " + c.code)
    }
  }

  @Test
  fun `test typed-character strokes with modifiers match AWT`() {
    val masks = listOf(0, 1, 2, 64, 128, 256, 512, 128 or 64, 0xFFFF)
    for (c in listOf('a', 'A', 'z', '0', ' ')) {
      for (m in masks) {
        assertSameStroke(
          KeyStroke.getKeyStroke(java.lang.Character.valueOf(c), m),
          VimKeyStroke.getKeyStroke(c, m),
          "char " + c + " mods " + m,
        )
      }
    }
  }

  @Test
  fun `test pressed-key strokes match AWT for every VK constant this build knows`() {
    val masks = listOf(0, 64, 128, 256, 512, 130, 128 or 512)
    for (field in KeyEvent::class.java.fields) {
      if (!field.name.startsWith("VK_") || field.type != Int::class.javaPrimitiveType) continue
      val code = field.getInt(null)
      for (m in masks) {
        assertSameStroke(
          KeyStroke.getKeyStroke(code, m),
          VimKeyStroke.getKeyStroke(code, m),
          field.name + " mods " + m,
        )
      }
    }
  }

  @Test
  fun `test VimKeyCodes values match the AWT constants they mirror`() {
    var checked = 0
    for (field in KeyEvent::class.java.fields) {
      if (!field.name.startsWith("VK_") || field.type != Int::class.javaPrimitiveType) continue
      val ours = VimKeyCodes::class.java.getDeclaredField(field.name)
      ours.isAccessible = true
      assertEquals(field.getInt(null), ours.getInt(VimKeyCodes), field.name)
      checked++
    }
    assertTrue(checked > 180, "expected the full VK table, checked only " + checked)
    assertEquals(KeyEvent.CHAR_UNDEFINED, VimKeyCodes.CHAR_UNDEFINED)
    assertEquals(java.awt.event.InputEvent.CTRL_DOWN_MASK, VimKeyCodes.CTRL_DOWN_MASK)
    assertEquals(java.awt.event.InputEvent.SHIFT_DOWN_MASK, VimKeyCodes.SHIFT_DOWN_MASK)
    assertEquals(java.awt.event.InputEvent.ALT_DOWN_MASK, VimKeyCodes.ALT_DOWN_MASK)
    assertEquals(java.awt.event.InputEvent.META_DOWN_MASK, VimKeyCodes.META_DOWN_MASK)
  }

  @Test
  fun `test equality agrees with AWT pairwise`() {
    // Includes the trap: a typed 'A' and a pressed VK_A share a numeric value but are distinct.
    val awt = mutableListOf<KeyStroke>()
    val vim = mutableListOf<VimKeyStroke>()
    for (c in listOf('a', 'A', 'z', '0')) {
      awt.add(KeyStroke.getKeyStroke(c))
      vim.add(VimKeyStroke.getKeyStroke(c))
    }
    for (code in listOf(KeyEvent.VK_A, KeyEvent.VK_Z, KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE, 65)) {
      for (m in listOf(0, 128, 130, 64)) {
        awt.add(KeyStroke.getKeyStroke(code, m))
        vim.add(VimKeyStroke.getKeyStroke(code, m))
      }
    }
    for (i in awt.indices) {
      for (j in awt.indices) {
        assertEquals(
          awt[i] == awt[j],
          vim[i] == vim[j],
          "equality disagreed for " + awt[i] + " vs " + awt[j],
        )
      }
    }
  }

  @Test
  fun `test equal strokes hash equally`() {
    // The hash values need not match AWT's - nothing persists them - but equal objects must agree.
    for (code in listOf(KeyEvent.VK_A, KeyEvent.VK_ENTER, KeyEvent.VK_F12)) {
      for (m in listOf(0, 128, 130, 2)) {
        val a = VimKeyStroke.getKeyStroke(code, m)
        val b = VimKeyStroke.getKeyStroke(code, m)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode(), "hash for code " + code + " mods " + m)
      }
    }
    // 128 and 130 denote the same stroke once normalised, so they must land in the same bucket.
    assertEquals(
      VimKeyStroke.getKeyStroke(KeyEvent.VK_A, 128).hashCode(),
      VimKeyStroke.getKeyStroke(KeyEvent.VK_A, 130).hashCode(),
    )
    assertEquals(VimKeyStroke.getKeyStroke(KeyEvent.VK_A, 128), VimKeyStroke.getKeyStroke(KeyEvent.VK_A, 2))
  }

  @Test
  fun `test a typed character is never equal to the pressed key of the same value`() {
    assertTrue(KeyStroke.getKeyStroke('A') != KeyStroke.getKeyStroke(KeyEvent.VK_A, 0))
    assertTrue(VimKeyStroke.getKeyStroke('A') != VimKeyStroke.getKeyStroke(KeyEvent.VK_A, 0))
  }

  @Test
  fun `test strokes work as map keys the way AWT strokes do`() {
    val awtMap = HashMap<KeyStroke, String>()
    val vimMap = HashMap<VimKeyStroke, String>()
    awtMap[KeyStroke.getKeyStroke(KeyEvent.VK_A, 128)] = "ctrl-a"
    vimMap[VimKeyStroke.getKeyStroke(KeyEvent.VK_A, 128)] = "ctrl-a"
    // Looked up with the legacy bit instead of the modern one.
    assertEquals(awtMap[KeyStroke.getKeyStroke(KeyEvent.VK_A, 2)], vimMap[VimKeyStroke.getKeyStroke(KeyEvent.VK_A, 2)])
    assertEquals("ctrl-a", vimMap[VimKeyStroke.getKeyStroke(KeyEvent.VK_A, 2)])
    assertEquals(awtMap[KeyStroke.getKeyStroke('a')], vimMap[VimKeyStroke.getKeyStroke('a')])
  }

  @Test
  fun `test onKeyRelease is preserved and distinguishes strokes, as in AWT`() {
    // Modal input builds strokes from KEY_RELEASED events, and those reach macro registers, so a
    // released key must not compare equal to a pressed one.
    for (code in listOf(KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE, KeyEvent.VK_A)) {
      for (m in listOf(0, 128)) {
        val pressed = KeyStroke.getKeyStroke(code, m, false)
        val released = KeyStroke.getKeyStroke(code, m, true)
        val vimPressed = VimKeyStroke.getKeyStroke(code, m, false)
        val vimReleased = VimKeyStroke.getKeyStroke(code, m, true)
        assertEquals(pressed.isOnKeyRelease, vimPressed.onKeyRelease)
        assertEquals(released.isOnKeyRelease, vimReleased.onKeyRelease)
        assertEquals(released.modifiers, vimReleased.modifiers)
        assertEquals(pressed == released, vimPressed == vimReleased, "release-flag equality for " + code)
        assertTrue(vimPressed != vimReleased)
      }
    }
  }

  @Test
  fun `test converting to AWT and back returns an equal stroke`() {
    val strokes = mutableListOf<VimKeyStroke>()
    for (c in listOf('a', 'A', '0', ' ', '@')) strokes.add(VimKeyStroke.getKeyStroke(c))
    for (code in listOf(KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE, KeyEvent.VK_A, KeyEvent.VK_F5)) {
      for (m in listOf(0, 64, 128, 130, 512)) {
        strokes.add(VimKeyStroke.getKeyStroke(code, m, false))
        strokes.add(VimKeyStroke.getKeyStroke(code, m, true))
      }
    }
    for (stroke in strokes) {
      assertEquals(stroke, stroke.toAwtKeyStroke().toVimKeyStroke(), "round trip of " + stroke)
    }
  }

  @Test
  fun `test converting an AWT stroke to neutral and back returns an equal stroke`() {
    val strokes = mutableListOf<KeyStroke>()
    for (c in listOf('a', 'A', '0', ' ')) strokes.add(KeyStroke.getKeyStroke(c))
    for (code in listOf(KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE, KeyEvent.VK_A)) {
      for (m in listOf(0, 64, 128, 512)) {
        strokes.add(KeyStroke.getKeyStroke(code, m, false))
        strokes.add(KeyStroke.getKeyStroke(code, m, true))
      }
    }
    for (stroke in strokes) {
      assertEquals(stroke, stroke.toVimKeyStroke().toAwtKeyStroke(), "round trip of " + stroke)
    }
  }

  @Test
  fun `test keyEventType matches AWT`() {
    assertEquals(KeyEvent.KEY_TYPED, VimKeyCodes.KEY_TYPED)
    assertEquals(KeyEvent.KEY_PRESSED, VimKeyCodes.KEY_PRESSED)
    assertEquals(KeyEvent.KEY_RELEASED, VimKeyCodes.KEY_RELEASED)
    for (c in listOf('a', 'A', ' ')) {
      assertEquals(KeyStroke.getKeyStroke(c).keyEventType, VimKeyStroke.getKeyStroke(c).keyEventType)
    }
    for (code in listOf(KeyEvent.VK_A, KeyEvent.VK_ENTER, KeyEvent.VK_F1)) {
      for (release in listOf(false, true)) {
        assertEquals(
          KeyStroke.getKeyStroke(code, 0, release).keyEventType,
          VimKeyStroke.getKeyStroke(code, 0, release).keyEventType,
          "code " + code + " release " + release,
        )
      }
    }
  }
}
