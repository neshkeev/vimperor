/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

import com.maddyhome.idea.vim.parser.generated.RegexLexer
import com.maddyhome.idea.vim.parser.generated.RegexParser
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import kotlin.test.Test
import kotlin.test.assertNotNull

class GeneratedParserSmokeTest {
  @Test
  fun `generated regex parser parses a literal pattern`() {
    val lexer = RegexLexer(CharStreams.fromString("abc"))
    val parser = RegexParser(CommonTokenStream(lexer))
    assertNotNull(parser.pattern())
  }
}
