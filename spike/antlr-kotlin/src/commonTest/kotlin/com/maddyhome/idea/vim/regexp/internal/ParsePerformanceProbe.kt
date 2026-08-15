/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.regexp.internal

import com.maddyhome.idea.vim.regexp.parser.VimRegexParser
import kotlin.test.Test
import kotlin.time.measureTime

/**
 * Rough throughput probe for Task 5 of the antlr-kotlin gate spike. Kept in its own file,
 * separate from [VimRegexParserTest], so it does not perturb that file's already-reported
 * "64 tests" count. Not a benchmark: a single un-warmed-up timing per target, meant only to
 * give an order-of-magnitude JS-vs-JVM comparison relevant to search-as-you-type recompiling
 * a pattern on every keystroke.
 */
class ParsePerformanceProbe {
  @Test
  fun `parse a representative pattern 1000 times`() {
    val pattern = "\\(foo\\|bar\\)\\{2,5}[a-z]*\\$"
    val elapsed = measureTime {
      repeat(1000) { VimRegexParser.parse(pattern) }
    }
    println("1000 parses took $elapsed")
  }
}
