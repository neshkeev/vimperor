/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The extractor, on source it can be checked against.
 *
 * [VimFixtureReplayTest] asserts that more than 280 fixtures were harvested, which catches an
 * extractor that has stopped working and not one that has started reading a `doTest` slightly
 * wrong. A corpus of the wrong triples would still be 300 of them, and every failure it produced
 * would be blamed on the host. So the parsing is tested on text with a known answer.
 */
class VimFixturesTest {

  private fun harvest(source: String): List<VimFixture> {
    val root = temporaryDirectory()
    writeFile("$root/src/test/Sample.kt", source)
    return VimFixtures.load(root)
  }

  @Test
  fun `test a plain doTest is read as its three strings`() {
    val fixtures = harvest(
      """
      class SampleTest {
        fun `test something`() {
          doTest("dw", "${'$'}{c}one two", "${'$'}{c}two")
        }
      }
      """.trimIndent(),
    )

    assertEquals(1, fixtures.size, "expected one fixture, got ${fixtures.map { it.source }}")
    assertEquals("dw", fixtures[0].keys)
    assertEquals("<caret>one two", fixtures[0].before)
    assertEquals("<caret>two", fixtures[0].after)
  }

  /** Raw strings with `trimIndent`, which is how most of the corpus is written. */
  @Test
  fun `test a raw string is trimmed the way Kotlin trims it`() {
    val fixtures = harvest(
      "class SampleTest {\n" +
        "  fun `test raw`() {\n" +
        "    doTest(\n" +
        "      \"j\",\n" +
        "      \"\"\"\n" +
        "          ${'$'}{c}one\n" +
        "          two\n" +
        "      \"\"\".trimIndent(),\n" +
        "      \"\"\"\n" +
        "          one\n" +
        "          ${'$'}{c}two\n" +
        "      \"\"\".trimIndent(),\n" +
        "    )\n" +
        "  }\n" +
        "}\n",
    )

    assertEquals(1, fixtures.size, "expected one fixture, got ${fixtures.map { it.source }}")
    assertEquals("<caret>one\ntwo", fixtures[0].before)
    assertEquals("one\n<caret>two", fixtures[0].after)
  }

  /** `listOf("a", "b")` is the other keys overload, and the two are joined with nothing between. */
  @Test
  fun `test a list of keys is joined`() {
    val fixtures = harvest(
      """
      class SampleTest {
        fun `test list`() {
          doTest(listOf("d", "w"), "${'$'}{c}one two", "${'$'}{c}two")
        }
      }
      """.trimIndent(),
    )

    assertEquals(listOf("dw"), fixtures.map { it.keys })
  }

  /** A trailing lambda of `enterCommand`s is the setup, and it is kept rather than thrown away. */
  @Test
  fun `test the ex commands a test runs first are harvested with it`() {
    val fixtures = harvest(
      """
      class SampleTest {
        fun `test with setup`() {
          doTest("l", "${'$'}{c}one", "o${'$'}{c}ne") {
            enterCommand("set whichwrap=h,l")
          }
        }
      }
      """.trimIndent(),
    )

    assertEquals(listOf(listOf("set whichwrap=h,l")), fixtures.map { it.setup })
  }

  /** Anything else in that lambda is setup this cannot repeat, and the fixture is refused. */
  @Test
  fun `test a test that sets up something else is refused`() {
    val fixtures = harvest(
      """
      class SampleTest {
        fun `test with unrepeatable setup`() {
          doTest("l", "${'$'}{c}one", "o${'$'}{c}ne") {
            setRegister('a', "hello")
          }
        }
      }
      """.trimIndent(),
    )

    assertEquals(emptyList(), fixtures)
    assertTrue(VimFixtures.skipped.containsKey("the test sets something up this cannot repeat"))
  }

  /**
   * Interpolation other than the caret means the string depends on something only the compiler
   * knows. Refusing it matters more than harvesting it: a fixture read as the literal text `${'$'}s`
   * would fail against a correct host and be recorded as a bug in it.
   */
  @Test
  fun `test a string with other interpolation is refused`() {
    val fixtures = harvest(
      """
      class SampleTest {
        fun `test interpolated`() {
          doTest("l", "${'$'}{c}one ${'$'}{selection}", "o${'$'}{c}ne")
        }
      }
      """.trimIndent(),
    )

    assertEquals(emptyList(), fixtures)
  }

  /** A file with its own `doTest` means something else by the name, and none of it is harvested. */
  @Test
  fun `test a file that redefines doTest is skipped whole`() {
    val fixtures = harvest(
      """
      class SampleTest {
        fun `test something`() {
          doTest("g/found/d", "${'$'}{c}one two", "${'$'}{c}two")
        }
        private fun doTest(command: String, before: String, after: String) {}
      }
      """.trimIndent(),
    )

    assertEquals(emptyList(), fixtures)
  }
}
