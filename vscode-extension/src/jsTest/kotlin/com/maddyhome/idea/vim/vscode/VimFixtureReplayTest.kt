/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * IdeaVim's own expectations, pressed against this host.
 *
 * Every other test in this module was written by whoever wrote the code under it, which is the
 * weakness they share: they encode what this port's author believed Vim does. The four sweeps have
 * the same shape of blind spot from the other side - they find a service that is *missing* and are
 * blind to one that exists and is wrong. `:` and `/` passed every sweep while the second backspace
 * in a row threw.
 *
 * These fixtures come from somewhere else. They are IdeaVim's `doTest(keys, before, after)` calls,
 * read out of the IntelliJ module's test sources as data, and they encode what Vim actually does -
 * argued over against real Vim for twenty years by people who were not thinking about VS Code.
 *
 * The result is a baseline rather than a pass. Most of the corpus passes; the ones that do not are
 * listed in `known-fixture-failures.txt`, and the test fails if that list changes in either
 * direction. A new failure is a regression. A fixture that starts passing has to be removed from
 * the list, which is how the number goes down.
 */
class VimFixtureReplayTest {

  @Test
  fun `test the fixtures that this host does not yet match are the ones listed`() {
    val root = repositoryRoot()
    assertTrue(root != null, "could not find the repository root, so no fixtures could be read")

    val fixtures = VimFixtures.load(root!!)
    // An empty corpus would make this test pass while checking nothing, which is the failure mode
    // the sweeps had to be given a gate of their own for.
    assertTrue(
      fixtures.size > 280,
      "only ${fixtures.size} fixtures were harvested, so the extractor has probably broken. " +
        "Skipped: ${VimFixtures.skipped}",
    )

    val outcomes = fixtures.associateWith { replay(it) }
    val failing = outcomes.filterValues { it != null }.keys.map { it.source }.sorted()
    val known = knownFailures(root)

    // Every run writes what it found, so regenerating the baseline is a copy rather than a
    // transcription out of a failure message.
    writeTextTo(
      "$root/vscode-extension/build/fixture-failures.txt",
      outcomes.entries.filter { it.value != null }.sortedBy { it.key.source }
        .joinToString("\n") { (fixture, difference) -> fixture.source + "\n" + difference!!.prependIndent("# ") } +
        "\n",
    )

    // Several hundred fixtures have just run, most of them leaving the key handler somewhere other
    // than Normal mode. The next test in this module gets the same global handler.
    KeyHandler.getInstance().fullReset(VimHost().also { it.start() }.editorFor(FakeEditor("")))

    val newlyFailing = failing - known.toSet()
    val newlyPassing = known - failing.toSet()

    assertEquals(
      emptyList(),
      newlyFailing,
      "these fixtures used to match Vim and no longer do:\n" +
        newlyFailing.joinToString("\n") { source ->
          "  $source\n" + outcomes.entries.first { it.key.source == source }.value
        },
    )
    assertEquals(
      emptyList(),
      newlyPassing,
      "these fixtures pass now and must be taken out of known-fixture-failures.txt:\n" +
        newlyPassing.joinToString("\n") { "  $it" },
    )
  }

  private fun knownFailures(root: String): List<String> {
    val path = "$root/vscode-extension/src/jsTest/fixtures/known-fixture-failures.txt"
    if (!fileExists(path)) return emptyList()
    return readText(path).lines()
      .map { it.substringBefore('#').trim() }
      .filter { it.isNotEmpty() }
      .sorted()
  }

  private companion object {
    /**
     * Runs one fixture, and describes the difference if there is one.
     *
     * Keys go through the key handler rather than through [VimHost.key], which is what the sweeps
     * do: a fixture's keys are already a list of strokes, and turning them back into notation to
     * feed the host would be testing the notation round trip as much as the fixture.
     *
     * Only the text and the caret are compared. IdeaVim's `doTest` also asserts the mode afterwards,
     * and that is left out on purpose for now - a mode mismatch on top of matching text is a
     * different and much smaller bug than the text being wrong, and mixing the two would make the
     * baseline hard to read.
     */
    fun replay(fixture: VimFixture): String? {
      val caretAt = fixture.before.indexOf(VimFixtures.CARET)
      val text = fixture.before.replace(VimFixtures.CARET, "")
      val expectedText = fixture.after.replace(VimFixtures.CARET, "")
      val expectedCaret = fixture.after.indexOf(VimFixtures.CARET).takeIf { it >= 0 }

      val fake = FakeEditor(text)
      val actualText: String
      val actualCaret: Int
      try {
        val host = VimHost().also { it.start() }
        val editor = host.editorFor(fake)
        KeyHandler.getInstance().fullReset(editor)
        editor.primaryCaret().moveToOffsetNative(caretAt)
        editor.flush()
        val handler = KeyHandler.getInstance()
        for (command in fixture.setup) {
          for (stroke in injector.parser.parseKeys(":" + command + "<CR>")) {
            handler.handleKey(editor, stroke, VsCodeExecutionContext, handler.keyHandlerState)
          }
        }
        for (stroke in injector.parser.parseKeys(fixture.keys)) {
          handler.handleKey(editor, stroke, VsCodeExecutionContext, handler.keyHandlerState)
        }
        editor.flush()
        actualText = fake.document.content
        actualCaret = editor.primaryCaret().offset
      } catch (e: Throwable) {
        return "    threw ${e::class.simpleName}: ${e.message?.take(120)}"
      }

      if (withoutTrailingSpaces(actualText) != withoutTrailingSpaces(expectedText)) {
        return "    keys ${fixture.keys}${fixture.setup.joinToString("") { " after :" + it }}\n    expected ${show(expectedText)}\n    actual   ${show(actualText)}"
      }
      // The caret has to be compared in the same coordinates as the text, and the text is compared
      // with trailing spaces removed - so an offset counted in a line with two trailing spaces is
      // two larger than the same place counted without them.
      val comparableCaret = withoutTrailingSpaces(actualText, upTo = actualCaret).length
      if (expectedCaret != null && comparableCaret != withoutTrailingSpaces(expectedText, upTo = expectedCaret).length) {
        return "    keys ${fixture.keys}${fixture.setup.joinToString("") { " after :" + it }}\n" +
          "    caret expected $expectedCaret, actual $actualCaret in ${show(actualText)}"
      }
      return null
    }

    fun show(text: String): String = "\"" + text.replace("\n", "\\n").take(120) + "\""

    /**
     * Trailing spaces are not compared, because IdeaVim does not compare them either.
     *
     * Thirteen fixtures say so: `]}` and `[{` are motions, and their `before` has two spaces on an
     * otherwise empty line where their `after` has none. A motion cannot delete a space. IdeaVim's
     * own assertion is what makes those tests pass over there, so this matches it rather than
     * recording thirteen failures that are really one convention.
     *
     * The cost is real and worth writing down: a bug in this host that left trailing whitespace
     * behind would not be caught here.
     */
    fun withoutTrailingSpaces(text: String): String = withoutTrailingSpaces(text, text.length)

    /** The first [upTo] characters of [text], with each line's trailing spaces removed. */
    fun withoutTrailingSpaces(text: String, upTo: Int): String =
      text.take(upTo.coerceIn(0, text.length)).split("\n").let { lines ->
        lines.mapIndexed { index, line -> if (index == lines.lastIndex) line else line.trimEnd() }
          .joinToString("\n")
      }

    /** Keeps the engine's command registry warm; each fixture builds its own host around it. */
    @Suppress("unused")
    val commandCount: Int = engineCommandProvider.getCommands().size
  }
}
