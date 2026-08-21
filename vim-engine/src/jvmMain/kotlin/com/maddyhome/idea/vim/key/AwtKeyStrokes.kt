/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.key

import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * The bridge between AWT keystrokes and the engine's neutral [VimKeyStroke].
 *
 * This is the only place the two representations meet. It lives in `jvmMain` on purpose: a JVM
 * host talks to Swing and needs the conversion, and no other host will have this file at all.
 *
 * Both directions are total and lossless - [VimKeyStroke] carries the same four fields AWT
 * compares on - so a round trip returns an equal stroke. `JdkKeyStrokeParityTest` checks that.
 */

/** The neutral equivalent of this AWT keystroke. */
fun KeyStroke.toVimKeyStroke(): VimKeyStroke =
  VimKeyStroke.getKeyStroke(keyChar, keyCode, modifiers, isOnKeyRelease)

/** The AWT equivalent of this neutral keystroke. */
fun VimKeyStroke.toAwtKeyStroke(): KeyStroke =
  if (keyChar == VimKeyCodes.CHAR_UNDEFINED) {
    KeyStroke.getKeyStroke(keyCode, modifiers, onKeyRelease)
  } else {
    // AWT has no (char, modifiers, onKeyRelease) factory, so go through the char form and let the
    // key code stay undefined - which is what a typed character has anyway.
    KeyStroke.getKeyStroke(Character.valueOf(keyChar), modifiers)
  }

/** The neutral keystroke for a real key event, mirroring `KeyStroke.getKeyStrokeForEvent`. */
fun vimKeyStrokeForEvent(event: KeyEvent): VimKeyStroke =
  KeyStroke.getKeyStrokeForEvent(event).toVimKeyStroke()
