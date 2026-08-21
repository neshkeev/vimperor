/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.key

/**
 * Key codes and modifier masks, with AWT's numeric values reproduced verbatim.
 *
 * The values are not arbitrary: they are persisted in `.ideavimrc` mappings, macro registers and
 * saved shortcut owners, and they are compared against key codes the host reports. Changing one
 * would silently change what a user's mapping matches, so this table is generated from
 * `java.awt.event.KeyEvent` and `java.awt.event.InputEvent` rather than transcribed.
 *
 * The full VK set is included, not only the codes the engine names today, because key-name parsing
 * looks codes up dynamically and a partial table would fail by matching nothing rather than by
 * failing to compile.
 */
@Suppress("unused", "ktlint")
object VimKeyCodes {
  /** The value of [VimKeyStroke.keyChar] when the stroke identifies a key rather than a character. */
  const val CHAR_UNDEFINED: Char = '\uFFFF'

  // --- modifier masks -------------------------------------------------------------------------

  const val SHIFT_DOWN_MASK: Int = 64
  const val CTRL_DOWN_MASK: Int = 128
  const val META_DOWN_MASK: Int = 256
  const val ALT_DOWN_MASK: Int = 512
  const val ALT_GRAPH_DOWN_MASK: Int = 8192
  const val BUTTON1_DOWN_MASK: Int = 1024
  const val BUTTON2_DOWN_MASK: Int = 2048
  const val BUTTON3_DOWN_MASK: Int = 4096

  // --- key codes ------------------------------------------------------------------------------

  const val VK_0: Int = 48
  const val VK_1: Int = 49
  const val VK_2: Int = 50
  const val VK_3: Int = 51
  const val VK_4: Int = 52
  const val VK_5: Int = 53
  const val VK_6: Int = 54
  const val VK_7: Int = 55
  const val VK_8: Int = 56
  const val VK_9: Int = 57
  const val VK_A: Int = 65
  const val VK_ACCEPT: Int = 30
  const val VK_ADD: Int = 107
  const val VK_AGAIN: Int = 65481
  const val VK_ALL_CANDIDATES: Int = 256
  const val VK_ALPHANUMERIC: Int = 240
  const val VK_ALT: Int = 18
  const val VK_ALT_GRAPH: Int = 65406
  const val VK_AMPERSAND: Int = 150
  const val VK_ASTERISK: Int = 151
  const val VK_AT: Int = 512
  const val VK_B: Int = 66
  const val VK_BACK_QUOTE: Int = 192
  const val VK_BACK_SLASH: Int = 92
  const val VK_BACK_SPACE: Int = 8
  const val VK_BEGIN: Int = 65368
  const val VK_BRACELEFT: Int = 161
  const val VK_BRACERIGHT: Int = 162
  const val VK_C: Int = 67
  const val VK_CANCEL: Int = 3
  const val VK_CAPS_LOCK: Int = 20
  const val VK_CIRCUMFLEX: Int = 514
  const val VK_CLEAR: Int = 12
  const val VK_CLOSE_BRACKET: Int = 93
  const val VK_CODE_INPUT: Int = 258
  const val VK_COLON: Int = 513
  const val VK_COMMA: Int = 44
  const val VK_COMPOSE: Int = 65312
  const val VK_CONTEXT_MENU: Int = 525
  const val VK_CONTROL: Int = 17
  const val VK_CONVERT: Int = 28
  const val VK_COPY: Int = 65485
  const val VK_CUT: Int = 65489
  const val VK_D: Int = 68
  const val VK_DEAD_ABOVEDOT: Int = 134
  const val VK_DEAD_ABOVERING: Int = 136
  const val VK_DEAD_ACUTE: Int = 129
  const val VK_DEAD_BREVE: Int = 133
  const val VK_DEAD_CARON: Int = 138
  const val VK_DEAD_CEDILLA: Int = 139
  const val VK_DEAD_CIRCUMFLEX: Int = 130
  const val VK_DEAD_DIAERESIS: Int = 135
  const val VK_DEAD_DOUBLEACUTE: Int = 137
  const val VK_DEAD_GRAVE: Int = 128
  const val VK_DEAD_IOTA: Int = 141
  const val VK_DEAD_MACRON: Int = 132
  const val VK_DEAD_OGONEK: Int = 140
  const val VK_DEAD_SEMIVOICED_SOUND: Int = 143
  const val VK_DEAD_TILDE: Int = 131
  const val VK_DEAD_VOICED_SOUND: Int = 142
  const val VK_DECIMAL: Int = 110
  const val VK_DELETE: Int = 127
  const val VK_DIVIDE: Int = 111
  const val VK_DOLLAR: Int = 515
  const val VK_DOWN: Int = 40
  const val VK_E: Int = 69
  const val VK_END: Int = 35
  const val VK_ENTER: Int = 10
  const val VK_EQUALS: Int = 61
  const val VK_ESCAPE: Int = 27
  const val VK_EURO_SIGN: Int = 516
  const val VK_EXCLAMATION_MARK: Int = 517
  const val VK_F: Int = 70
  const val VK_F1: Int = 112
  const val VK_F10: Int = 121
  const val VK_F11: Int = 122
  const val VK_F12: Int = 123
  const val VK_F13: Int = 61440
  const val VK_F14: Int = 61441
  const val VK_F15: Int = 61442
  const val VK_F16: Int = 61443
  const val VK_F17: Int = 61444
  const val VK_F18: Int = 61445
  const val VK_F19: Int = 61446
  const val VK_F2: Int = 113
  const val VK_F20: Int = 61447
  const val VK_F21: Int = 61448
  const val VK_F22: Int = 61449
  const val VK_F23: Int = 61450
  const val VK_F24: Int = 61451
  const val VK_F3: Int = 114
  const val VK_F4: Int = 115
  const val VK_F5: Int = 116
  const val VK_F6: Int = 117
  const val VK_F7: Int = 118
  const val VK_F8: Int = 119
  const val VK_F9: Int = 120
  const val VK_FINAL: Int = 24
  const val VK_FIND: Int = 65488
  const val VK_FULL_WIDTH: Int = 243
  const val VK_G: Int = 71
  const val VK_GREATER: Int = 160
  const val VK_H: Int = 72
  const val VK_HALF_WIDTH: Int = 244
  const val VK_HELP: Int = 156
  const val VK_HIRAGANA: Int = 242
  const val VK_HOME: Int = 36
  const val VK_I: Int = 73
  const val VK_INPUT_METHOD_ON_OFF: Int = 263
  const val VK_INSERT: Int = 155
  const val VK_INVERTED_EXCLAMATION_MARK: Int = 518
  const val VK_J: Int = 74
  const val VK_JAPANESE_HIRAGANA: Int = 260
  const val VK_JAPANESE_KATAKANA: Int = 259
  const val VK_JAPANESE_ROMAN: Int = 261
  const val VK_K: Int = 75
  const val VK_KANA: Int = 21
  const val VK_KANA_LOCK: Int = 262
  const val VK_KANJI: Int = 25
  const val VK_KATAKANA: Int = 241
  const val VK_KP_DOWN: Int = 225
  const val VK_KP_LEFT: Int = 226
  const val VK_KP_RIGHT: Int = 227
  const val VK_KP_UP: Int = 224
  const val VK_L: Int = 76
  const val VK_LEFT: Int = 37
  const val VK_LEFT_PARENTHESIS: Int = 519
  const val VK_LESS: Int = 153
  const val VK_M: Int = 77
  const val VK_META: Int = 157
  const val VK_MINUS: Int = 45
  const val VK_MODECHANGE: Int = 31
  const val VK_MULTIPLY: Int = 106
  const val VK_N: Int = 78
  const val VK_NONCONVERT: Int = 29
  const val VK_NUMBER_SIGN: Int = 520
  const val VK_NUMPAD0: Int = 96
  const val VK_NUMPAD1: Int = 97
  const val VK_NUMPAD2: Int = 98
  const val VK_NUMPAD3: Int = 99
  const val VK_NUMPAD4: Int = 100
  const val VK_NUMPAD5: Int = 101
  const val VK_NUMPAD6: Int = 102
  const val VK_NUMPAD7: Int = 103
  const val VK_NUMPAD8: Int = 104
  const val VK_NUMPAD9: Int = 105
  const val VK_NUM_LOCK: Int = 144
  const val VK_O: Int = 79
  const val VK_OPEN_BRACKET: Int = 91
  const val VK_P: Int = 80
  const val VK_PAGE_DOWN: Int = 34
  const val VK_PAGE_UP: Int = 33
  const val VK_PASTE: Int = 65487
  const val VK_PAUSE: Int = 19
  const val VK_PERIOD: Int = 46
  const val VK_PLUS: Int = 521
  const val VK_PREVIOUS_CANDIDATE: Int = 257
  const val VK_PRINTSCREEN: Int = 154
  const val VK_PROPS: Int = 65482
  const val VK_Q: Int = 81
  const val VK_QUOTE: Int = 222
  const val VK_QUOTEDBL: Int = 152
  const val VK_R: Int = 82
  const val VK_RIGHT: Int = 39
  const val VK_RIGHT_PARENTHESIS: Int = 522
  const val VK_ROMAN_CHARACTERS: Int = 245
  const val VK_S: Int = 83
  const val VK_SCROLL_LOCK: Int = 145
  const val VK_SEMICOLON: Int = 59
  const val VK_SEPARATER: Int = 108
  const val VK_SEPARATOR: Int = 108
  const val VK_SHIFT: Int = 16
  const val VK_SLASH: Int = 47
  const val VK_SPACE: Int = 32
  const val VK_STOP: Int = 65480
  const val VK_SUBTRACT: Int = 109
  const val VK_T: Int = 84
  const val VK_TAB: Int = 9
  const val VK_U: Int = 85
  const val VK_UNDEFINED: Int = 0
  const val VK_UNDERSCORE: Int = 523
  const val VK_UNDO: Int = 65483
  const val VK_UP: Int = 38
  const val VK_V: Int = 86
  const val VK_W: Int = 87
  const val VK_WINDOWS: Int = 524
  const val VK_X: Int = 88
  const val VK_Y: Int = 89
  const val VK_Z: Int = 90
}
