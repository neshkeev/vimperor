/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.functions.handlers.stringFunctions

import com.intellij.vim.annotations.VimscriptFunction
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimFloat
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.functions.VariadicFunctionHandler
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * `printf({fmt}, ...)` - the one function a status line, a message or a table is built with.
 *
 * Written out by hand, because it has to be: `commonMain` cannot use `String.format`, which is a
 * JVM method and would compile for one target and break the other. That is the same wall the float
 * formatting in this engine hit, and it is met the same way - with the specification implemented
 * rather than delegated.
 *
 * What is here is C's `printf` as Vim documents it: the flags `-`, `+`, ` `, `0` and `#`, a width,
 * a precision, `*` to take either from an argument, and the conversions `d`, `i`, `u`, `b`, `B`,
 * `o`, `x`, `X`, `c`, `s`, `S`, `f`, `F`, `e`, `E`, `g`, `G` and `%`. Positional arguments -
 * Vim's `%1$s` - are here too, because a translated message is the reason they exist.
 *
 * `E766` when there are not enough arguments and `E767` when there are too many, which are Vim's
 * errors. Getting those right matters more than it looks: a `printf` in a status line runs on every
 * redraw, and one that silently printed a stray `%s` would be a puzzle rather than a mistake.
 *
 * see "h printf()"
 */
@VimscriptFunction(name = "printf")
internal class PrintfFunctionHandler : VariadicFunctionHandler<VimString>(minArity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString {
    val format = arguments.getString(0).value
    val values = (1 until arguments.size).map { arguments[it] }
    return VimString(format(format, values))
  }

  private fun format(format: String, values: List<VimDataType>): String {
    val out = StringBuilder()
    var next = 0
    var index = 0
    var usedPositional = false

    fun take(explicit: Int?): VimDataType {
      if (explicit != null) {
        usedPositional = true
        return values.getOrNull(explicit - 1) ?: throw exExceptionMessage("E766")
      }
      return values.getOrNull(next++) ?: throw exExceptionMessage("E766")
    }

    while (index < format.length) {
      val character = format[index]
      if (character != '%') {
        out.append(character)
        index++
        continue
      }

      index++
      if (index >= format.length) break
      if (format[index] == '%') {
        out.append('%')
        index++
        continue
      }

      // `%2$s` - the argument number, which is only an argument number if a `$` follows it. A bare
      // `%2d` is a width, so the digits have to be read speculatively and given back if not.
      var position: Int? = null
      val digitsStart = index
      while (index < format.length && format[index].isDigit()) index++
      if (index < format.length && format[index] == '$' && index > digitsStart) {
        position = format.substring(digitsStart, index).toInt()
        index++
      } else {
        index = digitsStart
      }

      var leftAlign = false
      var showSign = false
      var spaceSign = false
      var zeroPad = false
      var alternate = false
      loop@ while (index < format.length) {
        when (format[index]) {
          '-' -> leftAlign = true
          '+' -> showSign = true
          ' ' -> spaceSign = true
          '0' -> zeroPad = true
          '#' -> alternate = true
          else -> break@loop
        }
        index++
      }

      var width = 0
      if (index < format.length && format[index] == '*') {
        width = take(null).toVimNumber().value
        if (width < 0) {
          leftAlign = true
          width = -width
        }
        index++
      } else {
        while (index < format.length && format[index].isDigit()) {
          width = width * 10 + (format[index] - '0')
          index++
        }
      }

      var precision: Int? = null
      if (index < format.length && format[index] == '.') {
        index++
        precision = if (index < format.length && format[index] == '*') {
          index++
          take(null).toVimNumber().value
        } else {
          var value = 0
          while (index < format.length && format[index].isDigit()) {
            value = value * 10 + (format[index] - '0')
            index++
          }
          value
        }
      }

      // Length modifiers - `%ld`, `%lld`, `%hd`. Vim accepts and ignores them; every number here is
      // the same width, so there is nothing for them to say.
      while (index < format.length && format[index] in "hlLqjzt") index++
      if (index >= format.length) break

      val conversion = format[index]
      index++
      val body = when (conversion) {
        'd', 'i' -> integer(take(position).toVimNumber().value.toLong(), 10, false, showSign, spaceSign, alternate)
        'u' -> integer(take(position).toVimNumber().value.toLong(), 10, false, false, false, false)
        'b', 'B' -> integer(take(position).toVimNumber().value.toLong(), 2, conversion == 'B', false, false, alternate)
        'o' -> integer(take(position).toVimNumber().value.toLong(), 8, false, false, false, alternate)
        'x' -> integer(take(position).toVimNumber().value.toLong(), 16, false, false, false, alternate)
        'X' -> integer(take(position).toVimNumber().value.toLong(), 16, true, false, false, alternate)
        'c' -> take(position).let { value ->
          if (value is VimString) value.value.take(1) else value.toVimNumber().value.toChar().toString()
        }

        's' -> take(position).toOutputString().let { if (precision != null) it.take(precision) else it }
        'S' -> take(position).toOutputString()
        'f', 'F' -> fixed(asDouble(take(position)), precision ?: 6, showSign, spaceSign)
        'e', 'E' -> scientific(asDouble(take(position)), precision ?: 6, conversion == 'E', showSign, spaceSign)
        'g', 'G' -> general(asDouble(take(position)), precision ?: 6, conversion == 'G', showSign, spaceSign)
        else -> throw exExceptionMessage("E767")
      }

      out.append(pad(body, width, leftAlign, zeroPad && !leftAlign && conversion !in "sS"))
    }

    // Vim complains about arguments nobody asked for. Positional formats are exempt, because a
    // format that names `%2$s` and not `%1$s` has left one over on purpose.
    if (!usedPositional && next < values.size) throw exExceptionMessage("E767")
    return out.toString()
  }

  private fun asDouble(value: VimDataType): Double =
    if (value is VimFloat) value.value else value.toVimNumber().value.toDouble()

  private fun integer(
    value: Long,
    base: Int,
    uppercase: Boolean,
    showSign: Boolean,
    spaceSign: Boolean,
    alternate: Boolean,
  ): String {
    val digits = abs(value).toString(base).let { if (uppercase) it.uppercase() else it }
    val prefix = when {
      value < 0 -> "-"
      showSign -> "+"
      spaceSign -> " "
      else -> ""
    }
    val marker = if (!alternate || value == 0L) {
      ""
    } else {
      when (base) {
        16 -> if (uppercase) "0X" else "0x"
        8 -> "0"
        2 -> if (uppercase) "0B" else "0b"
        else -> ""
      }
    }
    return prefix + marker + digits
  }

  /**
   * A fixed-point number, rounded half-away-from-zero, without `String.format`.
   *
   * Scaling by a power of ten and rounding the result is the whole of it, and the reason it is
   * written as a string afterwards rather than divided back is that dividing reintroduces exactly
   * the floating-point error the rounding just removed.
   */
  private fun fixed(value: Double, precision: Int, showSign: Boolean, spaceSign: Boolean): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"

    val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
    var scale = 1.0
    repeat(precision) { scale *= 10 }
    val scaled = (abs(value) * scale).roundToLong()
    val digits = scaled.toString().padStart(precision + 1, '0')
    val whole = digits.dropLast(precision).ifEmpty { "0" }
    val fraction = if (precision == 0) "" else "." + digits.takeLast(precision)

    val prefix = when {
      negative -> "-"
      showSign -> "+"
      spaceSign -> " "
      else -> ""
    }
    return prefix + whole + fraction
  }

  private fun scientific(
    value: Double,
    precision: Int,
    uppercase: Boolean,
    showSign: Boolean,
    spaceSign: Boolean,
  ): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"

    var mantissa = abs(value)
    var exponent = 0
    if (mantissa != 0.0) {
      while (mantissa >= 10.0) {
        mantissa /= 10.0
        exponent++
      }
      while (mantissa < 1.0) {
        mantissa *= 10.0
        exponent--
      }
    }

    val negative = value < 0
    val body = fixed(mantissa, precision, showSign = false, spaceSign = false)
    val marker = if (uppercase) "E" else "e"
    val sign = if (exponent < 0) "-" else "+"
    val prefix = when {
      negative -> "-"
      showSign -> "+"
      spaceSign -> " "
      else -> ""
    }
    return prefix + body + marker + sign + abs(exponent).toString().padStart(2, '0')
  }

  /**
   * `%g`, which is C's "whichever of `%e` and `%f` is shorter", with the trailing zeros removed.
   *
   * The exponent rule is C's: scientific when the exponent is below -4 or at least the precision,
   * fixed otherwise, and a precision of zero means one.
   */
  private fun general(
    value: Double,
    precision: Int,
    uppercase: Boolean,
    showSign: Boolean,
    spaceSign: Boolean,
  ): String {
    val significant = if (precision == 0) 1 else precision
    var exponent = 0
    var magnitude = abs(value)
    if (magnitude != 0.0) {
      while (magnitude >= 10.0) {
        magnitude /= 10.0
        exponent++
      }
      while (magnitude < 1.0) {
        magnitude *= 10.0
        exponent--
      }
    }

    val text = if (exponent < -4 || exponent >= significant) {
      scientific(value, significant - 1, uppercase, showSign, spaceSign)
    } else {
      fixed(value, (significant - 1 - exponent).coerceAtLeast(0), showSign, spaceSign)
    }
    return trimTrailingZeros(text, uppercase)
  }

  private fun trimTrailingZeros(text: String, uppercase: Boolean): String {
    val marker = if (uppercase) "E" else "e"
    val at = text.indexOf(marker)
    val body = if (at < 0) text else text.substring(0, at)
    val tail = if (at < 0) "" else text.substring(at)
    if ('.' !in body) return body + tail
    return body.trimEnd('0').trimEnd('.') + tail
  }

  private fun pad(text: String, width: Int, leftAlign: Boolean, zeroPad: Boolean): String {
    if (text.length >= width) return text
    if (leftAlign) return text.padEnd(width)
    if (!zeroPad) return text.padStart(width)
    // Zero padding goes *after* the sign, so `%+06.1f` of 1.5 is `+001.5` and not `00+1.5`.
    val signLength = if (text.firstOrNull() in listOf('-', '+', ' ')) 1 else 0
    return text.take(signLength) + text.drop(signLength).padStart(width - signLength, '0')
  }
}
