/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.parser

import com.maddyhome.idea.vim.helper.charCount
import com.maddyhome.idea.vim.helper.codePointAt
import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.IntStream
import org.antlr.v4.kotlinruntime.misc.Interval

/**
 * A [CharStream] over a string, indexed by codepoint.
 *
 * This exists because antlr-kotlin 1.0.13's own `StringCharStream` reports EOF instead of the
 * character when a surrogate pair is the **last** codepoint in the stream. Phase 0 traced that with
 * the bare `CharStreams.fromString(...).LA()` API, no grammar involved:
 *
 * | input | `size` | `LA(1)` |
 * |---|---|---|
 * | `"X"` where X is astral, at the end | 1 | -1, wrongly |
 * | `"Xy"` | 2 | the codepoint, correctly |
 *
 * The visible effect is that a Vim pattern ending in an emoji or a rare CJK character fails to
 * lex at all - `RegexLexer`'s wildcard rule sees EOF, no alternative matches, and the bail lexer
 * reports E383. The Java runtime handles it, so shipping around it would have been a regression,
 * not a limitation. `VimRegexParserTest.test wider unicode character` is the pin.
 *
 * Positions are codepoint indices, not `Char` indices, which is what ANTLR means by an index into a
 * `CharStream` and what makes the astral case work at all.
 */
class VimCharStream(private val text: String, private val name: String = IntStream.UNKNOWN_SOURCE_NAME) : CharStream {

  /** Offset in [text] of each codepoint, plus a final entry for the end, so `size` is one less. */
  private val offsets: IntArray = buildOffsets(text)

  private var position: Int = 0

  private fun buildOffsets(text: String): IntArray {
    val result = ArrayList<Int>(text.length + 1)
    var i = 0
    while (i < text.length) {
      result.add(i)
      i += charCount(codePointAt(text, i))
    }
    result.add(text.length)
    return result.toIntArray()
  }

  override fun size(): Int = offsets.size - 1

  override fun index(): Int = position

  override val sourceName: String
    get() = name

  override fun consume() {
    if (position >= size()) throw IllegalStateException("cannot consume EOF")
    position++
  }

  override fun LA(i: Int): Int {
    if (i == 0) return 0
    val target = if (i < 0) position + i else position + i - 1
    if (target < 0 || target >= size()) return IntStream.EOF
    return codePointAt(text, offsets[target])
  }

  override fun mark(): Int = -1

  override fun release(marker: Int) {}

  override fun seek(index: Int) {
    position = index.coerceIn(0, size())
  }

  override fun getText(interval: Interval): String {
    if (interval.a > interval.b) return ""
    val start = offsets[interval.a.coerceIn(0, size())]
    val stop = offsets[(interval.b + 1).coerceIn(0, size())]
    return text.substring(start, stop)
  }

  override fun toString(): String = text
}
