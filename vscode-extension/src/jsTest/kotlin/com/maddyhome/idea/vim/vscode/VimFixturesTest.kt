/*
 * Copyright 2026 Nikita Eshkeev
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
  /** `exCommand` and `searchCommand` are string building on `VimTestCase` and nothing else. */
  @Test
  fun `test the ex command helpers are expanded`() {
    val fixtures = harvest(
      """
      class SampleTest {
        fun `test copy`() {
          doTest(exCommand("copy ."), "${'$'}{c}one", "one\n${'$'}{c}one")
        }
        fun `test search`() {
          doTest(searchCommand("/two"), "${'$'}{c}one two", "one ${'$'}{c}two")
        }
      }
      """.trimIndent(),
    )

    assertEquals(2, fixtures.size, "expected two fixtures, got ${fixtures.map { it.source }}")
    assertEquals(":copy .<CR>", fixtures.first { it.source.endsWith("test copy") }.keys)
    assertEquals("/two<CR>", fixtures.first { it.source.endsWith("test search") }.keys)
  }

  /**
   * A comment between the arguments has to go before they are split.
   *
   * Thirty-odd fixtures carry one, and several contain a comma - which is what splits the arguments
   * from each other, so leaving the comment in tears the call into the wrong pieces rather than
   * merely putting noise in front of an argument.
   */
  @Test
  fun `test a comment between the arguments is removed before they are split`() {
    val fixtures = harvest(
      """
      class SampleTest {
        fun `test commented`() {
          doTest(
            "dw",
            // `.` and `%` are read-only, so this deletes nothing
            "${'$'}{c}one two",
            "${'$'}{c}two",
          )
        }
      }
      """.trimIndent(),
    )

    assertEquals(1, fixtures.size, "expected one fixture, got ${fixtures.map { it.source }}")
    assertEquals("<caret>one two", fixtures[0].before)
  }

  /** The selection markers are kept as markers; the replay reads the offsets back out of them. */
  @Test
  fun `test the selection markers are harvested`() {
    val fixtures = harvest(
      """
      class SampleTest {
        fun `test visual`() {
          doTest("vl", "${'$'}{c}one", "${'$'}{s}o${'$'}{c}n${'$'}{se}e")
        }
      }
      """.trimIndent(),
    )

    assertEquals(1, fixtures.size, "expected one fixture, got ${fixtures.map { it.source }}")
    assertEquals("<selection>o<caret>n</selection>e", fixtures[0].after)
  }

  /**
   * Block Visual marks a selection on every line, and all of them are kept.
   *
   * This used to be refused, on the grounds that comparing the first pair as though it were the
   * whole selection is a wrong answer rather than a missing one. That was true of a harness that
   * compared one selection; the answer was to compare all of them rather than to look away. Block
   * Visual and multiple cursors are the same shape - N carets, each with its own selection - and
   * multiple cursors are the feature VS Code is best known for.
   */
  @Test
  fun `test every selection in a result is kept`() {
    val fixtures = harvest(
      """
      class SampleTest {
        fun `test block`() {
          doTest("<C-V>jl", "${'$'}{c}one\ntwo", "${'$'}{s}o${'$'}{se}ne\n${'$'}{s}t${'$'}{c}${'$'}{se}wo")
        }
      }
      """.trimIndent(),
    )

    assertEquals(1, fixtures.size, "the fixture should have been harvested rather than refused")
    assertEquals(
      "<selection>o</selection>ne\n<selection>t<caret></selection>wo",
      fixtures[0].after,
    )
  }

  /** More than one caret in the input is kept too, and set through VS Code's own selections. */
  @Test
  fun `test more than one caret in the input is kept`() {
    val fixtures = harvest(
      """
      class SampleTest {
        fun `test two carets`() {
          doTest("x", "${'$'}{c}one\n${'$'}{c}two", "ne\nwo")
        }
      }
      """.trimIndent(),
    )

    assertEquals(1, fixtures.size, "a multi-caret fixture should be harvested")
    assertEquals("<caret>one\n<caret>two", fixtures[0].before)
  }
  /**
   * `trimMargin` is what 318 of the fixtures are written with, and it is not `trimIndent`.
   *
   * The difference shows on a line that does not carry the margin prefix: `trimIndent` would take
   * the common indent off it anyway, `trimMargin` leaves it exactly as it was. That is why IdeaVim
   * reaches for it when the text under test has indentation of its own.
   */
  @Test
  fun `test a margin string is trimmed the way Kotlin trims it`() {
    val fixtures = harvest(
      "class SampleTest {\n" +
        "  fun `test margin`() {\n" +
        "    doTest(\n" +
        "      \"j\",\n" +
        "      \"\"\"\n" +
        "        |${'$'}{c}one\n" +
        "        |    two\n" +
        "      \"\"\".trimMargin(),\n" +
        "      \"\"\"\n" +
        "        |one\n" +
        "        |    ${'$'}{c}two\n" +
        "      \"\"\".trimMargin(),\n" +
        "    )\n" +
        "  }\n" +
        "}\n",
    )

    assertEquals(1, fixtures.size, "expected one fixture, got ${fixtures.map { it.source }}")
    assertEquals("<caret>one\n    two", fixtures[0].before)
  }

  /** `"<C-H>".repeat(8)`, which is how the backspace fixtures say "eight times". */
  @Test
  fun `test repeat is applied`() {
    val fixtures = harvest(
      """
      class SampleTest {
        fun `test repeated`() {
          doTest(listOf("R", "ab", "<C-H>".repeat(2)), "${'$'}{c}one", "${'$'}{c}one")
        }
      }
      """.trimIndent(),
    )

    assertEquals(1, fixtures.size, "expected one fixture, got ${fixtures.map { it.source }}")
    assertEquals("Rab<C-H><C-H>", fixtures[0].keys)
  }

  /**
   * `"a" + "b"`, which the tag-object fixtures are written with.
   *
   * They need `\n` escapes on every line, and a raw string cannot carry an escape - so they are one
   * ordinary literal per line of the document, added together.
   */
  @Test
  fun `test concatenated literals are joined`() {
    val fixtures = harvest(
      """
      class SampleTest {
        fun `test concatenated`() {
          doTest("dat", "<a>\n" + "  ${'$'}{c}<b/>\n" + "</a>\n", "${'$'}{c}\n")
        }
      }
      """.trimIndent(),
    )

    assertEquals(1, fixtures.size, "expected one fixture, got ${fixtures.map { it.source }}")
    assertEquals("<a>\n  <caret><b/>\n</a>\n", fixtures[0].before)
  }
}
