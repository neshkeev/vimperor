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
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.datatypes.asVimInt
import com.maddyhome.idea.vim.vimscript.model.functions.BuiltinFunctionHandler

/**
 * The four ways Vim measures a string, which are four different numbers for the same text.
 *
 * They exist because Vim is a terminal editor and the three questions it has to answer about a
 * string are genuinely different: how much memory, how many characters, how much screen. For
 * `"héllo"` the answers are 6, 5 and 5; for a line of CJK, 3 bytes, 1 character and 2 columns per
 * glyph. A config that pads a status line uses the third, one that walks a string uses the second,
 * and one that talks to `byteidx()` uses the first.
 *
 * Kotlin strings are UTF-16, so *none* of the three is the string's `length` and each is computed:
 * bytes by encoding, characters by counting code points rather than units, columns by asking
 * whether each code point is one of the East Asian wide ranges.
 */
private fun utf8Length(text: String): Int {
  var total = 0
  var index = 0
  while (index < text.length) {
    val code = text.codePointAtSafe(index)
    total += when {
      code < 0x80 -> 1
      code < 0x800 -> 2
      code < 0x10000 -> 3
      else -> 4
    }
    index += if (code >= 0x10000) 2 else 1
  }
  return total
}

/** Code points rather than UTF-16 units, so an emoji is one character and not two. */
internal fun characterCount(text: String): Int {
  var count = 0
  var index = 0
  while (index < text.length) {
    val code = text.codePointAtSafe(index)
    index += if (code >= 0x10000) 2 else 1
    count++
  }
  return count
}

/**
 * The screen columns [text] occupies.
 *
 * Two per character in the East Asian wide and fullwidth ranges, one otherwise, which is the rule
 * every terminal implements and the one Vim's own `strwidth()` follows. The ranges are the wide
 * ones from Unicode's East Asian Width property; combining marks are counted as one rather than
 * zero, which is a simplification and the only one here - a config that pads a status line built
 * from decomposed text would be one column out, and no config does that.
 */
internal fun displayWidth(text: String): Int {
  var total = 0
  var index = 0
  while (index < text.length) {
    val code = text.codePointAtSafe(index)
    total += if (isWide(code)) 2 else 1
    index += if (code >= 0x10000) 2 else 1
  }
  return total
}

private fun isWide(code: Int): Boolean = code in 0x1100..0x115F || // Hangul Jamo
  code in 0x2E80..0x303E || // CJK radicals, Kangxi, CJK symbols
  code in 0x3041..0x33FF || // Hiragana through CJK compatibility
  code in 0x3400..0x4DBF || // CJK extension A
  code in 0x4E00..0x9FFF || // CJK unified ideographs
  code in 0xA000..0xA4CF || // Yi
  code in 0xAC00..0xD7A3 || // Hangul syllables
  code in 0xF900..0xFAFF || // CJK compatibility ideographs
  code in 0xFE30..0xFE6F || // CJK compatibility forms
  code in 0xFF00..0xFF60 || // Fullwidth forms
  code in 0xFFE0..0xFFE6 ||
  code in 0x1F300..0x1F64F || // Emoji, which terminals draw double-width
  code in 0x1F900..0x1F9FF ||
  code in 0x20000..0x3FFFD // CJK extensions B and beyond

/** `String.codePointAt` is a JVM method; this is the multiplatform half of it. */
private fun String.codePointAtSafe(index: Int): Int {
  val first = this[index]
  if (first.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
    return 0x10000 + ((first.code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00)
  }
  return first.code
}

/**
 * `strlen({expr})` - the number of *bytes*, which is what Vim means by the length of a string.
 *
 * see "h strlen()"
 */
@VimscriptFunction(name = "strlen")
internal class StrLenFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = utf8Length(arguments.getString(0).value).asVimInt()
}

/**
 * `strchars({expr} [, {skipcc}])` - the number of characters.
 *
 * `skipcc` asks for combining characters to be left out of the count. They are counted either way
 * here, because telling one apart needs Unicode's combining-class table and the difference only
 * shows in text that has been decomposed.
 *
 * see "h strchars()"
 */
@VimscriptFunction(name = "strchars")
internal class StrCharsFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 1, maxArity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = characterCount(arguments.getString(0).value).asVimInt()
}

/** `strcharlen({expr})` - `strchars()` with `skipcc` always on. See "h strcharlen()". */
@VimscriptFunction(name = "strcharlen")
internal class StrCharLenFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = characterCount(arguments.getString(0).value).asVimInt()
}

/**
 * `strwidth({expr})` - the number of screen columns, ignoring tabs.
 *
 * see "h strwidth()"
 */
@VimscriptFunction(name = "strwidth")
internal class StrWidthFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = displayWidth(arguments.getString(0).value).asVimInt()
}

/**
 * `strdisplaywidth({expr} [, {col}])` - screen columns, with tabs expanded.
 *
 * The difference from `strwidth()` is the tab, and the second argument is why: a tab's width
 * depends on where the string starts, so the column has to be known to answer at all.
 *
 * see "h strdisplaywidth()"
 */
@VimscriptFunction(name = "strdisplaywidth")
internal class StrDisplayWidthFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 1, maxArity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val text = arguments.getString(0).value
    val startColumn = arguments.getNumberOrNull(1)?.value ?: 0
    // Read the way `:retab` reads it, which is the only other place that has to expand a tab.
    val tabStop = injector.optionGroup.getOption("tabstop")
      ?.let { (injector.optionGroup.getOptionValue(it, OptionAccessScope.EFFECTIVE(editor)) as? VimInt)?.value }
      ?.takeIf { it > 0 }
      ?: 8

    var column = startColumn
    for (piece in text.split('\t').withIndex()) {
      column += displayWidth(piece.value)
      if (piece.index < text.count { it == '\t' }) {
        column += tabStop - (column % tabStop)
      }
    }
    return (column - startColumn).asVimInt()
  }
}

/**
 * `strtrans({expr})` - the string as Vim would *display* it, with unprintables made visible.
 *
 * A control character becomes `^A`, a `<Del>` becomes `^?`, and anything else unprintable becomes
 * `<xx>`. What it is for is putting a string in a message without the string being able to move the
 * caret or ring the bell.
 *
 * see "h strtrans()"
 */
@VimscriptFunction(name = "strtrans")
internal class StrTransFunctionHandler : BuiltinFunctionHandler<VimString>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString = VimString(
    buildString {
      for (character in arguments.getString(0).value) {
        when {
          character.code in 0x00..0x1F -> append('^').append(('@' + character.code))
          character.code == 0x7F -> append("^?")
          character.code in 0x80..0x9F -> append("<").append(character.code.toString(16)).append(">")
          else -> append(character)
        }
      }
    },
  )
}
