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
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimFloat
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimList
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.datatypes.asVimInt
import com.maddyhome.idea.vim.vimscript.model.functions.BuiltinFunctionHandler

/**
 * `strpart({src}, {start} [, {len} [, {chars}]])` - a substring, counted in bytes.
 *
 * The awkward one of the family, and awkward in Vim too: `{start}` may be negative and `{len}` may
 * run past the end, and neither is an error - the result is simply clipped. That is deliberate on
 * Vim's part, because the function exists to be called with arithmetic that might go out of bounds.
 *
 * Counted in bytes unless `{chars}` is given, which is why it has a `{chars}` at all: a config
 * slicing a path is thinking in characters and a config talking to `byteidx()` is not.
 *
 * see "h strpart()"
 */
@VimscriptFunction(name = "strpart")
internal class StrPartFunctionHandler : BuiltinFunctionHandler<VimString>(minArity = 2, maxArity = 4) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString {
    val source = arguments.getString(0).value
    val byChars = (arguments.getNumberOrNull(3)?.value ?: 0) != 0
    val units = if (byChars) source.byCharacter() else source.byByte()

    val start = arguments.getNumber(1).value
    val length = arguments.getNumberOrNull(2)?.value ?: (units.size - start)
    // A negative start shortens the length by as much as it is negative, which is Vim's rule and
    // is what makes `strpart(s, -3, 5)` two characters rather than five.
    val from = start.coerceIn(0, units.size)
    val to = (start + length).coerceIn(from, units.size)
    return VimString(units.subList(from, to).joinToString(""))
  }
}

/**
 * `strcharpart({src}, {start} [, {len} [, {skipcc}]])` - `strpart()`, counted in characters.
 *
 * see "h strcharpart()"
 */
@VimscriptFunction(name = "strcharpart")
internal class StrCharPartFunctionHandler : BuiltinFunctionHandler<VimString>(minArity = 2, maxArity = 4) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString {
    val units = arguments.getString(0).value.byCharacter()
    val start = arguments.getNumber(1).value
    val length = arguments.getNumberOrNull(2)?.value ?: (units.size - start)
    val from = start.coerceIn(0, units.size)
    val to = (start + length).coerceIn(from, units.size)
    return VimString(units.subList(from, to).joinToString(""))
  }
}

/**
 * `strgetchar({str}, {index})` - the character at a character index, as a number.
 *
 * -1 past the end, which is Vim's answer and the reason this is not `str[i]`.
 *
 * see "h strgetchar()"
 */
@VimscriptFunction(name = "strgetchar")
internal class StrGetCharFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val characters = arguments.getString(0).value.byCharacter()
    val index = arguments.getNumber(1).value
    val character = characters.getOrNull(index) ?: return (-1).asVimInt()
    return character.codePointList().first().asVimInt()
  }
}

/**
 * `stridx({haystack}, {needle} [, {start}])` - where a substring is, or -1.
 *
 * Byte offsets, like everything else in Vim's string family that is not explicitly about
 * characters, so that the answer can be handed straight to `strpart()`.
 *
 * see "h stridx()"
 */
@VimscriptFunction(name = "stridx")
internal class StrIdxFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 2, maxArity = 3) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val haystack = arguments.getString(0).value
    val needle = arguments.getString(1).value
    val startByte = arguments.getNumberOrNull(2)?.value ?: 0
    val found = haystack.indexOf(needle, startIndex = haystack.charIndexOfByte(startByte))
    return if (found < 0) (-1).asVimInt() else haystack.byteIndexOfChar(found).asVimInt()
  }
}

/**
 * `strridx({haystack}, {needle} [, {start}])` - the *last* occurrence, or -1.
 *
 * `{start}` means the opposite of `stridx()`'s: the match must begin at or before it, because this
 * one searches backwards. Vim's asymmetry, kept.
 *
 * see "h strridx()"
 */
@VimscriptFunction(name = "strridx")
internal class StrRIdxFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 2, maxArity = 3) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val haystack = arguments.getString(0).value
    val needle = arguments.getString(1).value
    val lastByte = arguments.getNumberOrNull(2)?.value
    val from = if (lastByte == null) haystack.length else haystack.charIndexOfByte(lastByte)
    val found = haystack.lastIndexOf(needle, startIndex = from)
    return if (found < 0) (-1).asVimInt() else haystack.byteIndexOfChar(found).asVimInt()
  }
}

/**
 * `tr({src}, {fromstr}, {tostr})` - character-for-character replacement.
 *
 * `E475` when the two sets are different lengths, which is Vim's error and worth having: the whole
 * point is that they line up, and a silent truncation would corrupt text rather than report it.
 *
 * see "h tr()"
 */
@VimscriptFunction(name = "tr")
internal class TrFunctionHandler : BuiltinFunctionHandler<VimString>(arity = 3) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString {
    val source = arguments.getString(0).value
    val from = arguments.getString(1).value.byCharacter()
    val to = arguments.getString(2).value.byCharacter()
    if (from.size != to.size) {
      throw com.maddyhome.idea.vim.ex.exExceptionMessage("E475", arguments.getString(2).value)
    }

    val table = from.zip(to).toMap()
    return VimString(source.byCharacter().joinToString("") { table[it] ?: it })
  }
}

/**
 * `str2nr({string} [, {base} [, {quoted}]])` - a number out of the front of a string.
 *
 * Stops at the first character that is not a digit rather than failing, which is what makes it
 * usable on `"12abc"` and on the output of something that appended a unit. Zero when there is no
 * number at all.
 *
 * see "h str2nr()"
 */
@VimscriptFunction(name = "str2nr")
internal class Str2NrFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 1, maxArity = 3) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val base = arguments.getNumberOrNull(1)?.value ?: 10
    val quoted = (arguments.getNumberOrNull(2)?.value ?: 0) != 0
    var text = arguments.getString(0).value.trim()
    if (quoted) text = text.replace("'", "")

    var sign = 1
    if (text.startsWith("-")) {
      sign = -1
      text = text.drop(1)
    } else if (text.startsWith("+")) {
      text = text.drop(1)
    }

    // Vim reads the prefix when the base says to, and ignores it otherwise - `str2nr("0x10", 10)`
    // is 0, because reading stops at the `x`.
    text = when (base) {
      16 -> text.removePrefix("0x").removePrefix("0X")
      8 -> text.removePrefix("0o").removePrefix("0O")
      2 -> text.removePrefix("0b").removePrefix("0B")
      else -> text
    }

    val digits = text.takeWhile { it.digitToIntOrNull(base) != null }
    val value = digits.toIntOrNull(base) ?: 0
    return (sign * value).asVimInt()
  }
}

/**
 * `str2float({string})` - a float out of the front of a string.
 *
 * see "h str2float()"
 */
@VimscriptFunction(name = "str2float")
internal class Str2FloatFunctionHandler : BuiltinFunctionHandler<VimFloat>(minArity = 1, maxArity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimFloat {
    val text = arguments.getString(0).value.trim()
    // The longest prefix that parses, because Vim stops where the number does and this is the only
    // way to say that without writing a float grammar.
    for (end in text.length downTo 1) {
      text.substring(0, end).toDoubleOrNull()?.let { return VimFloat(it) }
    }
    return VimFloat(0.0)
  }
}

/**
 * `str2list({string} [, {utf8}])` - the string as a list of character numbers.
 *
 * see "h str2list()"
 */
@VimscriptFunction(name = "str2list")
internal class Str2ListFunctionHandler : BuiltinFunctionHandler<VimList>(minArity = 1, maxArity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimList = VimList(
    arguments.getString(0).value.codePointList()
      .map { it.asVimInt() as VimDataType }
      .toMutableList(),
  )
}

/**
 * `list2str({list} [, {utf8}])` - the other direction.
 *
 * see "h list2str()"
 */
@VimscriptFunction(name = "list2str")
internal class List2StrFunctionHandler : BuiltinFunctionHandler<VimString>(minArity = 1, maxArity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString {
    val list = arguments[0] as? VimList ?: throw com.maddyhome.idea.vim.ex.exExceptionMessage("E686", "list2str()")
    return VimString(
      buildString {
        for (element in list.values) {
          appendCodePoint(element.toVimNumber().value)
        }
      },
    )
  }
}

/**
 * The string as a list of *characters*, where a character is a code point rather than a UTF-16 unit.
 *
 * Everything in this file that counts, slices or maps has to agree about what a character is, and
 * `String.length` does not: an emoji is one character and two units, and slicing between them
 * produces text no editor can render.
 */
internal fun String.byCharacter(): List<String> {
  val result = mutableListOf<String>()
  var index = 0
  while (index < length) {
    val high = this[index]
    val width = if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) 2 else 1
    result.add(substring(index, index + width))
    index += width
  }
  return result
}

/** The string as a list of one-byte pieces, for the functions Vim counts in bytes. */
private fun String.byByte(): List<String> = byCharacter().flatMap { character ->
  // A multi-byte character cannot be split into pieces that are still strings, so it stays whole
  // and occupies its first byte. Slicing a string in the middle of a character is not something
  // Vim does either - it clamps to a character boundary.
  val bytes = character.utf8Size()
  List(bytes) { position -> if (position == 0) character else "" }
}

private fun String.utf8Size(): Int = codePointList().sumOf {
  when {
    it < 0x80 -> 1
    it < 0x800 -> 2
    it < 0x10000 -> 3
    else -> 4
  }
}

/**
 * The code points, in order.
 *
 * Not `codePoints()`, which is a name `java.lang.CharSequence` already has: on the JVM target that
 * one wins and hands back an `IntStream`, and the whole file stops compiling in a way that says
 * nothing about why.
 */
internal fun String.codePointList(): List<Int> = byCharacter().map { piece ->
  val first = piece[0]
  if (piece.length == 2) 0x10000 + ((first.code - 0xD800) shl 10) + (piece[1].code - 0xDC00) else first.code
}

/** The UTF-16 index of the character that starts at [byte], clamped into the string. */
private fun String.charIndexOfByte(byte: Int): Int {
  if (byte <= 0) return 0
  var bytes = 0
  var index = 0
  for (piece in byCharacter()) {
    if (bytes >= byte) return index
    bytes += piece.utf8Size()
    index += piece.length
  }
  return length
}

/** The byte offset of the character at UTF-16 index [charIndex]. */
private fun String.byteIndexOfChar(charIndex: Int): Int {
  var bytes = 0
  var index = 0
  for (piece in byCharacter()) {
    if (index >= charIndex) return bytes
    bytes += piece.utf8Size()
    index += piece.length
  }
  return bytes
}

private fun StringBuilder.appendCodePoint(code: Int) {
  if (code < 0x10000) {
    append(code.toChar())
  } else {
    val offset = code - 0x10000
    append((0xD800 + (offset shr 10)).toChar())
    append((0xDC00 + (offset and 0x3FF)).toChar())
  }
}
