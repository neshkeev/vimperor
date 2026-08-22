/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript

import com.maddyhome.idea.vim.parser.VimCharStream
import com.maddyhome.idea.vim.parser.generated.VimscriptLexer
import com.maddyhome.idea.vim.parser.generated.VimscriptParser
import org.antlr.v4.kotlinruntime.CommonTokenStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parses 1,865 commands taken from IdeaVim's own test suite and compares each parse tree against
 * what the **Java** ANTLR parser produced for it.
 *
 * That parser was deleted when the grammars moved to antlr-kotlin, so `corpus/vimscript-golden.txt`
 * is the only surviving record of its behaviour, and this is the only check that the migration did
 * not change how Vimscript parses. Phase 0 ran this comparison on the JVM only and left extending
 * it to JS as caveat 7; running it here closes that, and it is the broadest behavioural evidence
 * the JS target has.
 *
 * The two targets share the generated parser source, so this is not looking for grammar
 * divergence - it is looking for the places where Kotlin/JS and Kotlin/JVM can still differ
 * underneath it: the ANTLR runtime's platform code, character handling, and the ATN interpreter's
 * arithmetic.
 */
class VimscriptCorpusTest {

  private fun parseTree(input: String): String {
    val lexer = VimscriptLexer(VimCharStream(input + "\n"))
    val parser = VimscriptParser(CommonTokenStream(lexer))
    return parser.script().toStringTree(parser)
  }

  @Test
  fun `test every corpus command parses the way the Java parser parsed it`() {
    assertTrue(VIMSCRIPT_CORPUS.size > 1800, "expected the full corpus, got ${VIMSCRIPT_CORPUS.size}")

    val divergences = mutableListOf<String>()
    for ((input, expected) in VIMSCRIPT_CORPUS) {
      val actual = try {
        parseTree(input)
      } catch (e: Throwable) {
        "threw ${e::class.simpleName}: ${e.message}"
      }
      if (actual != expected) {
        divergences.add("input: $input\n  java:   $expected\n  kotlin: $actual")
      }
    }
    assertEquals(emptyList(), divergences.take(20), "${divergences.size} commands parse differently")
  }
}
