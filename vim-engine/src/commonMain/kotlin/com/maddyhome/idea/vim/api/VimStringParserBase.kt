/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.annotations.Contract
import com.maddyhome.idea.vim.annotations.NonNls
import com.maddyhome.idea.vim.helper.toChars
import com.maddyhome.idea.vim.key.VimKeyCodes
import com.maddyhome.idea.vim.key.VimKeyStroke

abstract class VimStringParserBase : VimStringParser {
  override val plugKeyStroke: VimKeyStroke
    get() = parseKeys("<Plug>")[0]

  override val actionKeyStroke: VimKeyStroke
    get() = parseKeys("<Action>")[0]

  // todo what is the difference between this one and com.maddyhome.idea.vim.helper.EngineStringHelper#toPrintableCharacters
  override fun toPrintableString(keys: List<VimKeyStroke>): String {
    val builder = StringBuilder()
    for (key in keys) {
      val keyAsString = keyStrokeToString(key)
      builder.append(keyAsString)
    }
    return builder.toString()
  }

  private fun keyStrokeToString(key: VimKeyStroke): String {
    if (key.keyChar != VimKeyCodes.CHAR_UNDEFINED) {
      return key.keyChar.toString()
    } else if (key.modifiers and VimKeyCodes.CTRL_DOWN_MASK == VimKeyCodes.CTRL_DOWN_MASK) {
      return if (isControlCharacterKeyCode(key.keyCode)) {
        if (key.keyCode == 'J'.code) {
          // 'J' is a special case, keycode 10 is \n char
          0.toChar().toString()
        } else {
          (key.keyCode - 'A'.code + 1).toChar().toString()
        }
      } else {
        "^" + key.keyCode.toChar()
      }
    } else if (key.keyChar == VimKeyCodes.CHAR_UNDEFINED && key.keyCode == VimKeyCodes.VK_ENTER) {
      return "\n"
    }
    return key.keyCode.toChar().toString()
  }

  override fun toKeyNotation(keyStrokes: List<VimKeyStroke>): String {
    if (keyStrokes.isEmpty()) {
      return "<Nop>"
    }
    val builder = StringBuilder()
    for (key in keyStrokes) {
      builder.append(toKeyNotation(key))
    }
    return builder.toString()
  }

  override fun toKeyNotation(keyStroke: VimKeyStroke): String {
    val c = keyStroke.keyChar
    val keyCode = keyStroke.keyCode
    val modifiers = keyStroke.modifiers
    if (c != VimKeyCodes.CHAR_UNDEFINED && !isControlCharacter(c)) {
      return c.toString()
    }
    var prefix = ""
    if (modifiers and VimKeyCodes.META_DOWN_MASK != 0) {
      prefix += "M-"
    }
    if (modifiers and VimKeyCodes.ALT_DOWN_MASK != 0) {
      prefix += "A-"
    }
    if (modifiers and VimKeyCodes.CTRL_DOWN_MASK != 0) {
      prefix += "C-"
    }
    if (modifiers and VimKeyCodes.SHIFT_DOWN_MASK != 0) {
      prefix += "S-"
    }
    var name = getVimKeyValue(keyCode)
    if (name != null) {
      name = if (containsDisplayUppercaseKeyNames(name)) {
        name.uppercase()
      } else {
        capitalize(name)
      }
    }
    if (name == null) {
      val escape = toEscapeNotation(keyStroke)
      if (escape != null) {
        return escape
      }
      try {
        name = toChars(keyCode).concatToString()
      } catch (_: IllegalArgumentException) {
      }
    }
    return if (name != null) "<$prefix$name>" else "<<$keyStroke>>"
  }

  override fun parseKeys(string: String): List<VimKeyStroke> = buildList {
    val specialKeyBuilder = StringBuilder()
    var state = KeyParserState.INIT

    for (c in string) {
      when (state) {
        KeyParserState.INIT -> when (c) {
          '\\' -> state = KeyParserState.ESCAPE
          '<' -> {
            state = KeyParserState.SPECIAL
            specialKeyBuilder.clear()
          }

          else -> {
            val stroke: VimKeyStroke = if (c == '\t' || c == '\n') {
              VimKeyStroke.getKeyStroke(c.code, 0)
            } else if (isControlCharacter(c)) {
              VimKeyStroke.getKeyStroke(c.code + 'A'.code - 1, VimKeyCodes.CTRL_DOWN_MASK)
            } else {
              VimKeyStroke.getKeyStroke(c)
            }
            add(stroke)
          }
        }

        KeyParserState.ESCAPE -> {
          state = KeyParserState.INIT
          if (c != '\\') {
            add(VimKeyStroke.getKeyStroke('\\'))
          }
          add(VimKeyStroke.getKeyStroke(c))
        }

        KeyParserState.SPECIAL -> {
          if (c == '>') {
            state = KeyParserState.INIT
            val specialKeyName = specialKeyBuilder.toString()
            val lower = specialKeyName.lowercase()
            require("sid" != lower) { "<$specialKeyName> is not supported" }

            if ("leader" == lower) {
              addAll(getMapLeader())
            } else if ("nop" != lower) {
              val specialKey = parseSpecialKey(specialKeyName, 0)
              if (specialKey != null && specialKeyName.length > 1) {
                add(specialKey)
              } else {
                add(VimKeyStroke.getKeyStroke('<'))
                addAll(stringToKeys(specialKeyName))
                add(VimKeyStroke.getKeyStroke('>'))
              }
            }
          } else {
            // e.g. move '<-2<CR> - the first part does not belong to any special key
            if (c == '<') {
              add(VimKeyStroke.getKeyStroke('<'))
              addAll(stringToKeys(specialKeyBuilder.toString()))
              specialKeyBuilder.clear()
            } else {
              specialKeyBuilder.append(c)
            }
          }
        }
      }
    }

    if (state == KeyParserState.ESCAPE) {
      add(VimKeyStroke.getKeyStroke('\\'))
    } else if (state == KeyParserState.SPECIAL) {
      add(VimKeyStroke.getKeyStroke('<'))
      addAll(stringToKeys(specialKeyBuilder.toString()))
    }
  }

  private fun getMapLeader(): List<VimKeyStroke> {
    val mapLeader: Any? = injector.variableService.getGlobalVariableValue("mapleader")
    return if (mapLeader is VimString) {
      val v: String = mapLeader.value
      // Minimum length is 4 for the shortest special key format: \<X> (e.g., "\<a>")
      if (v.startsWith("\\<") && v.length >= 4 && v[v.length - 1] == '>') {
        val specialKey = parseSpecialKey(v.substring(2, v.length - 1), 0)
        if (specialKey != null) {
          listOf(specialKey)
        } else {
          stringToKeys(mapLeader.value)
        }
      } else {
        stringToKeys(mapLeader.value)
      }
    } else {
      stringToKeys("\\")
    }
  }

  override fun stringToKeys(string: @NonNls String): List<VimKeyStroke> {
    val res: MutableList<VimKeyStroke> = ArrayList()
    for (element in string) {
      if (isControlCharacter(element) && element.code != 10) {
        if (element.code == 0) {
          // J is a special case, it's keycode is 0 because keycode 10 is reserved by \n
          res.add(VimKeyStroke.getKeyStroke('J'.code, VimKeyCodes.CTRL_DOWN_MASK))
        } else if (element == '\t') {
          res.add(VimKeyStroke.getKeyStroke('\t'))
        } else {
          res.add(VimKeyStroke.getKeyStroke(element.code + 'A'.code - 1, VimKeyCodes.CTRL_DOWN_MASK))
        }
      } else {
        res.add(VimKeyStroke.getKeyStroke(element))
      }
    }
    return res
  }

  private fun isControlCharacter(c: Char): Boolean {
    return c < '\u0020'
  }

  private fun isControlCharacterKeyCode(code: Int): Boolean {
    // Ctrl-(A..Z [\]^_) are ASCII control characters
    return code >= 'A'.code && code <= '_'.code
  }

  @Suppress("SpellCheckingInspection")
  private fun getVimKeyValue(c: Int): @NonNls String? {
    return when (c) {
      VimKeyCodes.VK_ENTER -> "cr"
      VimKeyCodes.VK_INSERT -> "ins"
      VimKeyCodes.VK_HOME -> "home"
      VimKeyCodes.VK_END -> "end"
      VimKeyCodes.VK_PAGE_UP -> "pageup"
      VimKeyCodes.VK_PAGE_DOWN -> "pagedown"
      VimKeyCodes.VK_DELETE -> "del"
      VimKeyCodes.VK_ESCAPE -> "esc"
      VimKeyCodes.VK_BACK_SPACE -> "bs"
      VimKeyCodes.VK_TAB -> "tab"
      VimKeyCodes.VK_UP -> "up"
      VimKeyCodes.VK_DOWN -> "down"
      VimKeyCodes.VK_LEFT -> "left"
      VimKeyCodes.VK_RIGHT -> "right"
      VimKeyCodes.VK_F1 -> "f1"
      VimKeyCodes.VK_F2 -> "f2"
      VimKeyCodes.VK_F3 -> "f3"
      VimKeyCodes.VK_F4 -> "f4"
      VimKeyCodes.VK_F5 -> "f5"
      VimKeyCodes.VK_F6 -> "f6"
      VimKeyCodes.VK_F7 -> "f7"
      VimKeyCodes.VK_F8 -> "f8"
      VimKeyCodes.VK_F9 -> "f9"
      VimKeyCodes.VK_F10 -> "f10"
      VimKeyCodes.VK_F11 -> "f11"
      VimKeyCodes.VK_F12 -> "f12"
      VimKeyCodes.VK_F13 -> "f13"
      VimKeyCodes.VK_F14 -> "f14"
      VimKeyCodes.VK_F15 -> "f15"
      VimKeyCodes.VK_F16 -> "f16"
      VimKeyCodes.VK_F17 -> "f17"
      VimKeyCodes.VK_F18 -> "f18"
      VimKeyCodes.VK_F19 -> "f19"
      VimKeyCodes.VK_F20 -> "f20"
      VimKeyCodes.VK_F21 -> "f21"
      VimKeyCodes.VK_F22 -> "f22"
      VimKeyCodes.VK_F23 -> "f23"
      VimKeyCodes.VK_F24 -> "f24"
      VK_PLUG -> "plug"
      VK_ACTION -> "action"
      VimKeyCodes.VK_NUMPAD0 -> "k0"
      VimKeyCodes.VK_NUMPAD1 -> "k1"
      VimKeyCodes.VK_NUMPAD2 -> "k2"
      VimKeyCodes.VK_NUMPAD3 -> "k3"
      VimKeyCodes.VK_NUMPAD4 -> "k4"
      VimKeyCodes.VK_NUMPAD5 -> "k5"
      VimKeyCodes.VK_NUMPAD6 -> "k6"
      VimKeyCodes.VK_NUMPAD7 -> "k7"
      VimKeyCodes.VK_NUMPAD8 -> "k8"
      VimKeyCodes.VK_NUMPAD9 -> "k9"
      VimKeyCodes.VK_KP_DOWN -> "kdown"
      VimKeyCodes.VK_KP_UP -> "kup"
      VimKeyCodes.VK_KP_LEFT -> "kleft"
      VimKeyCodes.VK_KP_RIGHT -> "kright"
      VimKeyCodes.VK_UNDO -> "undo"
      else -> null
    }
  }

  private fun containsDisplayUppercaseKeyNames(lower: String): Boolean {
    return "cr" == lower || "bs" == lower
  }

  @Contract(pure = true)
  private fun capitalize(s: String): String {
    if (s.isEmpty()) return s
    if (s.length == 1) return s.uppercase()
    return if (s[0].isUpperCase()) s else s[0].uppercaseChar().toString() + s.substring(1)
  }

  private fun toEscapeNotation(key: VimKeyStroke): String? {
    val c = key.keyChar
    if (isControlCharacter(c)) {
      return "^" + (c.code + 'A'.code - 1).toChar()
    } else if (isControlKeyCode(key)) {
      return "^" + (key.keyCode + 'A'.code - 1).toChar()
    }
    return null
  }

  private fun isControlKeyCode(key: VimKeyStroke): Boolean {
    return key.keyChar == VimKeyCodes.CHAR_UNDEFINED && key.keyCode < 0x20 && key.modifiers == 0
  }

  override fun parseVimScriptString(string: String): String {
    val result = StringBuilder()
    var state = VimStringState.INIT
    var specialKeyBuilder: StringBuilder? = null
    var digitsLeft = 0
    var number = 0
    val vimStringWithForceEnd = string + 0.toChar()
    var i = 0
    while (i < vimStringWithForceEnd.length) {
      val c = vimStringWithForceEnd[i]
      when (state) {
        VimStringState.INIT -> if (c == '\\') {
          state = VimStringState.ESCAPE
        } else if (c.code == 0) {
          i = vimStringWithForceEnd.length
        } else {
          result.append(c)
        }

        VimStringState.ESCAPE -> {
          val octalToDigital = octalDigitToNumber(c)
          if (octalToDigital != null) {
            number = octalToDigital
            digitsLeft = 2
            state = VimStringState.OCTAL_NUMBER
          } else if (c.lowercaseChar() == 'x') {
            digitsLeft = 2
            state = VimStringState.HEX_NUMBER
          } else if (c == 'u') {
            digitsLeft = 4
            state = VimStringState.HEX_NUMBER
          } else if (c == 'U') {
            digitsLeft = 8
            state = VimStringState.HEX_NUMBER
          } else if (c == 'b') {
            result.append(8.toChar())
            state = VimStringState.INIT
          } else if (c == 'e') {
            result.append(27.toChar())
            state = VimStringState.INIT
          } else if (c == 'f') {
            result.append(12.toChar())
            state = VimStringState.INIT
          } else if (c == 'n') {
            result.append('\n')
            state = VimStringState.INIT
          } else if (c == 'r') {
            result.append('\r')
            state = VimStringState.INIT
          } else if (c == 't') {
            result.append('\t')
            state = VimStringState.INIT
          } else if (c == '\\') {
            result.append('\\')
            state = VimStringState.INIT
          } else if (c == '"') {
            result.append('"')
            state = VimStringState.INIT
          } else if (c == '<') {
            state = VimStringState.SPECIAL
            specialKeyBuilder = StringBuilder()
          } else if (c.code == 0) {
            i = vimStringWithForceEnd.length // force end of the string
          } else {
            result.append(c)
            state = VimStringState.INIT
          }
        }

        VimStringState.OCTAL_NUMBER -> {
          val value = octalDigitToNumber(c)
          if (value != null) {
            digitsLeft -= 1
            number = number * 8 + value
            if (digitsLeft == 0 || i == vimStringWithForceEnd.length - 1) {
              if (number != 0) {
                result.append(number.toChar())
              } else {
                i = vimStringWithForceEnd.length
              }
              number = 0
              state = VimStringState.INIT
            }
          } else {
            if (number != 0) {
              result.append(number.toChar())
            } else {
              i = vimStringWithForceEnd.length
            }
            number = 0
            digitsLeft = 0
            state = VimStringState.INIT
            i -= 1
          }
        }

        VimStringState.HEX_NUMBER -> {
          val `val` = hexDigitToNumber(c)
          if (`val` == null) {
            // if there was at least one number after '\', append number, otherwise - append letter after '\'
            if (vimStringWithForceEnd[i - 2] == '\\') {
              result.append(vimStringWithForceEnd[i - 1])
            } else {
              if (number != 0) {
                result.append(number.toChar())
              } else {
                i = vimStringWithForceEnd.length
              }
            }
            number = 0
            digitsLeft = 0
            state = VimStringState.INIT
            i -= 1
          } else {
            number = number * 16 + `val`
            digitsLeft -= 1
            if (digitsLeft == 0 || i == vimStringWithForceEnd.length - 1) {
              if (number != 0) {
                result.append(number.toChar())
              } else {
                i = vimStringWithForceEnd.length
              }
              number = 0
              state = VimStringState.INIT
            }
          }
        }

        VimStringState.SPECIAL -> {
          if (c.code == 0) {
            result.append(specialKeyBuilder)
          }
          if (c == '>') {
            val specialKey = parseSpecialKey(specialKeyBuilder.toString(), 0)
            if (specialKey != null) {
              var keyCode = specialKey.keyCode
              var useKeyCode = true
              if (specialKey.keyCode == 0) {
                keyCode = specialKey.keyChar.code
              } else if (specialKey.modifiers and VimKeyCodes.CTRL_DOWN_MASK == VimKeyCodes.CTRL_DOWN_MASK) {
                if (isControlCharacterKeyCode(specialKey.keyCode)) {
                  keyCode = if (specialKey.keyCode == 'J'.code) {
                    // 'J' is a special case, keycode 10 is \n char
                    0
                  } else {
                    specialKey.keyCode - 'A'.code + 1
                  }
                } else {
                  useKeyCode = false
                  result.append("\\<${specialKeyBuilder}>")
                }
              }
              if (useKeyCode) {
                result.append(keyCode.toChar())
              }
            } else {
              result.append("<").append(specialKeyBuilder).append(">")
            }
            specialKeyBuilder = StringBuilder()
            state = VimStringState.INIT
          } else if (c.code == 0) {
            result.append("<").append(specialKeyBuilder)
            state = VimStringState.INIT
          } else {
            specialKeyBuilder!!.append(c)
          }
        }
      }
      i += 1
    }
    return result.toString()
  }

  private enum class VimStringState {
    INIT, ESCAPE, OCTAL_NUMBER, HEX_NUMBER, SPECIAL
  }

  private fun octalDigitToNumber(c: Char): Int? {
    return if (c in '0'..'7') {
      c.code - '0'.code
    } else {
      null
    }
  }

  private fun hexDigitToNumber(c: Char): Int? {
    val lowerChar = c.lowercaseChar()
    if (lowerChar.isDigit()) {
      return lowerChar.code - '0'.code
    } else if (lowerChar in 'a'..'f') {
      return lowerChar.code - 'a'.code + 10
    }
    return null
  }

  // See https://vimdoc.sourceforge.net/htmldoc/intro.html#key-notation
  private fun parseSpecialKey(s: String, modifiers: Int): VimKeyStroke? {
    val lower = s.lowercase()
    val keyCode = getVimKeyName(lower)
    val typedChar = getVimTypedKeyName(lower)
    if (keyCode != null) {
      return VimKeyStroke.getKeyStroke(keyCode, modifiers)
    } else if (typedChar != null) {
      return getTypedOrPressedKeyStroke(typedChar, modifiers)
    } else if (lower.startsWith(CMD_PREFIX)) {
      return parseSpecialKey(s.substring(CMD_PREFIX.length), modifiers or VimKeyCodes.META_DOWN_MASK)
    } else if (lower.startsWith(META_PREFIX)) {
      // Meta and alt prefixes are the same thing. See the key notation of vim
      return parseSpecialKey(s.substring(META_PREFIX.length), modifiers or VimKeyCodes.ALT_DOWN_MASK)
    } else if (lower.startsWith(ALT_PREFIX)) {
      return parseSpecialKey(s.substring(ALT_PREFIX.length), modifiers or VimKeyCodes.ALT_DOWN_MASK)
    } else if (lower.startsWith(CTRL_PREFIX)) {
      return parseSpecialKey(s.substring(CTRL_PREFIX.length), modifiers or VimKeyCodes.CTRL_DOWN_MASK)
    } else if (lower.startsWith(SHIFT_PREFIX)) {
      return parseSpecialKey(s.substring(SHIFT_PREFIX.length), modifiers or VimKeyCodes.SHIFT_DOWN_MASK)
    } else if (s.length == 1) {
      return getTypedOrPressedKeyStroke(s[0], modifiers)
    }
    return null
  }

  @Suppress("SpellCheckingInspection")
  private fun getVimKeyName(lower: @NonNls String?): Int? {
    return when (lower) {
      "cr", "enter", "return" -> VimKeyCodes.VK_ENTER
      "ins", "insert" -> VimKeyCodes.VK_INSERT
      "home" -> VimKeyCodes.VK_HOME
      "end" -> VimKeyCodes.VK_END
      "pageup" -> VimKeyCodes.VK_PAGE_UP
      "pagedown" -> VimKeyCodes.VK_PAGE_DOWN
      "del", "delete" -> VimKeyCodes.VK_DELETE
      "esc" -> VimKeyCodes.VK_ESCAPE
      "bs", "backspace" -> VimKeyCodes.VK_BACK_SPACE
      "tab" -> VimKeyCodes.VK_TAB
      "up" -> VimKeyCodes.VK_UP
      "down" -> VimKeyCodes.VK_DOWN
      "left" -> VimKeyCodes.VK_LEFT
      "right" -> VimKeyCodes.VK_RIGHT
      "f1" -> VimKeyCodes.VK_F1
      "f2" -> VimKeyCodes.VK_F2
      "f3" -> VimKeyCodes.VK_F3
      "f4" -> VimKeyCodes.VK_F4
      "f5" -> VimKeyCodes.VK_F5
      "f6" -> VimKeyCodes.VK_F6
      "f7" -> VimKeyCodes.VK_F7
      "f8" -> VimKeyCodes.VK_F8
      "f9" -> VimKeyCodes.VK_F9
      "f10" -> VimKeyCodes.VK_F10
      "f11" -> VimKeyCodes.VK_F11
      "f12" -> VimKeyCodes.VK_F12
      "f13" -> VimKeyCodes.VK_F13
      "f14" -> VimKeyCodes.VK_F14
      "f15" -> VimKeyCodes.VK_F15
      "f16" -> VimKeyCodes.VK_F16
      "f17" -> VimKeyCodes.VK_F17
      "f18" -> VimKeyCodes.VK_F18
      "f19" -> VimKeyCodes.VK_F19
      "f20" -> VimKeyCodes.VK_F20
      "f21" -> VimKeyCodes.VK_F21
      "f22" -> VimKeyCodes.VK_F22
      "f23" -> VimKeyCodes.VK_F23
      "f24" -> VimKeyCodes.VK_F24
      "plug" -> VK_PLUG
      "action" -> VK_ACTION
      "k0" -> VimKeyCodes.VK_NUMPAD0
      "k1" -> VimKeyCodes.VK_NUMPAD1
      "k2" -> VimKeyCodes.VK_NUMPAD2
      "k3" -> VimKeyCodes.VK_NUMPAD3
      "k4" -> VimKeyCodes.VK_NUMPAD4
      "k5" -> VimKeyCodes.VK_NUMPAD5
      "k6" -> VimKeyCodes.VK_NUMPAD6
      "k7" -> VimKeyCodes.VK_NUMPAD7
      "k8" -> VimKeyCodes.VK_NUMPAD8
      "k9" -> VimKeyCodes.VK_NUMPAD9
      "khome" -> VimKeyCodes.VK_HOME
      "kend" -> VimKeyCodes.VK_END
      "kdown" -> VimKeyCodes.VK_KP_DOWN
      "kup" -> VimKeyCodes.VK_KP_UP
      "kleft" -> VimKeyCodes.VK_KP_LEFT
      "kright" -> VimKeyCodes.VK_KP_RIGHT
      "undo" -> VimKeyCodes.VK_UNDO
      else -> null
    }
  }

  @Suppress("SpellCheckingInspection")
  private fun getVimTypedKeyName(lower: String): Char? {
    return when (lower) {
      "space" -> ' '
      "bar" -> '|'
      "bslash" -> '\\'
      "lt" -> '<'
      else -> null
    }
  }

  private fun getTypedOrPressedKeyStroke(c: Char, modifiers: Int): VimKeyStroke {
    return if (modifiers == 0) {
      VimKeyStroke.getKeyStroke(c)
    } else if (modifiers == VimKeyCodes.SHIFT_DOWN_MASK && c.isLetter()) {
      VimKeyStroke.getKeyStroke(c.uppercaseChar())
    } else {
      VimKeyStroke.getKeyStroke(c.uppercaseChar().code, modifiers)
    }
  }

  private enum class KeyParserState {
    INIT, ESCAPE, SPECIAL
  }

  private companion object {
    private const val CMD_PREFIX = "d-"
    private const val META_PREFIX = "m-"
    private const val ALT_PREFIX = "a-"
    private const val CTRL_PREFIX = "c-"
    private const val SHIFT_PREFIX = "s-"
    private const val VK_PLUG = VimKeyCodes.CHAR_UNDEFINED.code - 1
    private const val VK_ACTION = VimKeyCodes.CHAR_UNDEFINED.code - 2
  }
}
