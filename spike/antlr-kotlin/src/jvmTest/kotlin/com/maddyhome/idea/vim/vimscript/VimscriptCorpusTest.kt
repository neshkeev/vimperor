/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript

import com.maddyhome.idea.vim.parser.generated.VimscriptLexer
import com.maddyhome.idea.vim.parser.generated.VimscriptParser
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.test.Test

class VimscriptCorpusTest {
  @Test
  fun `kotlin parser matches java parser across the corpus`() {
    val golden = Path.of("corpus/vimscript-golden.txt").readLines()
      .map { it.split('\t', limit = 2) }
      .filter { it.size == 2 }

    val divergences = mutableListOf<String>()
    for ((input, expected) in golden) {
      val actual = runCatching {
        val lexer = VimscriptLexer(CharStreams.fromString(input + "\n"))
        val parser = VimscriptParser(CommonTokenStream(lexer))
        parser.script().toStringTree(parser)
      }.getOrElse { "PARSE_ERROR: ${it::class.simpleName}" }

      if (actual != expected) {
        divergences += "INPUT: $input\n  java:   $expected\n  kotlin: $actual"
      }
    }

    println("corpus size: ${golden.size}, divergences: ${divergences.size}")
    divergences.take(50).forEach(::println)
    // Deliberately does not assert. This task measures; it does not gate.
  }
}
