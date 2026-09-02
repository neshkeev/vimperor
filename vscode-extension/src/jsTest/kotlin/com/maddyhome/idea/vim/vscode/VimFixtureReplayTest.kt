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
    warmUp()
    val root = repositoryRoot()
    assertTrue(root != null, "could not find the repository root, so no fixtures could be read")

    val fixtures = VimFixtures.load(root!!)
    // An empty corpus would make this test pass while checking nothing, which is the failure mode
    // the sweeps had to be given a gate of their own for.
    assertTrue(
      fixtures.size > 900,
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
      "# ${fixtures.size - failing.size} of ${fixtures.size} harvested fixtures pass.\n" +
        "#\n# What was not harvested, and why:\n" +
        VimFixtures.skipped.entries.sortedByDescending { it.value }
          .joinToString("\n") { (reason, count) -> "#   ${count.toString().padStart(5)}  $reason" } +
        "\n\n" +
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
     * The text, the caret, and the selection where the fixture marks one, are compared. IdeaVim's
     * `doTest` also asserts the mode afterwards, and that is left out on purpose for now - a mode
     * mismatch on top of matching text is a different and much smaller bug than the text being
     * wrong, and mixing the two would make the baseline hard to read.
     */
    fun replay(fixture: VimFixture): String? {
      val start = Marked(fixture.before)
      val expected = Marked(fixture.after)
      val text = start.text
      val caretsAt = start.carets.ifEmpty { listOf(0) }
      val expectedText = expected.text
      val expectedCarets = expected.carets

      val fake = FakeEditor(text)
      val actualText: String
      val actualCarets: List<Int>
      val actualSelections: List<Pair<Int, Int>>
      try {
        val host = VimHost().also { it.start() }
        val editor = host.editorFor(fake)
        KeyHandler.getInstance().fullReset(editor)
        // Through VS Code's selections rather than by moving the engine's caret, because that is
        // the only way a second caret can arrive in a real window - the user alt-clicks, VS Code
        // reports a selection change, and the host rebuilds its carets from it.
        fake.selections = caretsAt.map { offset ->
          val position = fake.document.positionAt(offset)
          Selection(position, position)
        }.toTypedArray()
        fake.selection = fake.selections[0]
        editor.syncCaretsFromEditor()
        editor.flush()
        val handler = KeyHandler.getInstance()
        // The command itself is typed rather than parsed, which is what IdeaVim does and for the
        // reason it gives: `:nmap <Tab> ihello<Esc>` is a mapping whose text contains `<Esc>`, and
        // parsing it would press Escape on the command line instead of writing five characters
        // into it. Only the `:` and the Enter are keystrokes.
        for (command in fixture.setup) {
          val keys = injector.parser.parseKeys(":") + injector.parser.stringToKeys(command) +
            injector.parser.parseKeys("<CR>")
          for (stroke in keys) {
            handler.handleKey(editor, stroke, VsCodeExecutionContext, handler.keyHandlerState)
          }
        }
        for (stroke in injector.parser.parseKeys(fixture.keys)) {
          handler.handleKey(editor, stroke, VsCodeExecutionContext, handler.keyHandlerState)
        }
        editor.flush()
        actualText = fake.document.content
        // In document order, which is the order IdeaVim writes its markers in. The engine keeps its
        // carets in the order they were made, and a block Visual command makes them bottom-up.
        val carets = editor.carets().sortedBy { it.offset }
        actualCarets = carets.map { it.offset }
        actualSelections = carets.filter { it.hasSelection() }.map { it.selectionStart to it.selectionEnd }
      } catch (e: Throwable) {
        return "    threw ${e::class.simpleName}: ${e.message?.take(120)}"
      }

      if (withoutTrailingSpaces(actualText) != withoutTrailingSpaces(expectedText)) {
        return "    keys ${fixture.keys}${fixture.setup.joinToString("") { " after :" + it }}\n    expected ${show(expectedText)}\n    actual   ${show(actualText)}"
      }
      // The caret has to be compared in the same coordinates as the text, and the text is compared
      // with trailing spaces removed - so an offset counted in a line with two trailing spaces is
      // two larger than the same place counted without them.
      fun actualAt(offset: Int) = withoutTrailingSpaces(actualText, upTo = offset).length
      fun expectedAt(offset: Int) = withoutTrailingSpaces(expectedText, upTo = offset).length

      if (expectedCarets.isNotEmpty() &&
        actualCarets.map(::actualAt) != expectedCarets.map(::expectedAt)
      ) {
        return "    keys ${fixture.keys}${fixture.setup.joinToString("") { " after :" + it }}\n" +
          "    caret expected $expectedCarets, actual $actualCarets in ${show(actualText)}"
      }
      // Only when the fixture says where the selections are. A fixture without the markers is not
      // saying there is no selection - most of the corpus is Normal mode and never mentions one -
      // so a stale selection left behind by this host would not be caught here.
      if (expected.selections.isNotEmpty()) {
        val wanted = expected.selections.map { expectedAt(it.first) to expectedAt(it.second) }
        val got = actualSelections.map { actualAt(it.first) to actualAt(it.second) }
        if (wanted != got) {
          return "    keys ${fixture.keys}${fixture.setup.joinToString("") { " after :" + it }}\n" +
            "    selection expected ${expected.selections}, actual $actualSelections in ${show(actualText)}"
        }
      }
      return null
    }

    /**
     * A fixture's text with IdeaVim's markers taken out, and the offsets they were sitting at.
     *
     * They have to come out in one pass rather than one `replace` each: `<selection>Lorem<caret>`
     * puts the caret five characters into the text only once the selection marker in front of it
     * has already gone.
     */
    class Marked(marked: String) {
      val text: String

      /** Every caret the fixture marks, in the order they appear - which is document order. */
      val carets: List<Int>

      /** Every selection, likewise. A block Visual result has one per line. */
      val selections: List<Pair<Int, Int>>

      init {
        val builder = StringBuilder()
        val caretsAt = mutableListOf<Int>()
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        var index = 0
        while (index < marked.length) {
          val marker = MARKERS.firstOrNull { marked.startsWith(it, index) }
          when (marker) {
            null -> { builder.append(marked[index]); index++ }
            else -> {
              when (marker) {
                VimFixtures.CARET -> caretsAt += builder.length
                VimFixtures.SELECTION_START -> starts += builder.length
                else -> ends += builder.length
              }
              index += marker.length
            }
          }
        }
        text = builder.toString()
        carets = caretsAt
        selections = starts.zip(ends)
      }
    }

    val MARKERS = listOf(VimFixtures.CARET, VimFixtures.SELECTION_START, VimFixtures.SELECTION_END)

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

    /**
     * Builds the engine's command registry once, rather than inside the first fixture.
     *
     * It has to be called and not written as an initialiser: reading the registry needs `injector`,
     * and `injector` is only set once a host has started. As a companion property this ran while
     * the test class was being constructed, which passed only because some other class in the
     * module happened to have started a host first - and stopped passing the moment this test was
     * run on its own.
     */
    fun warmUp() {
      VimHost().also { it.start() }
      engineCommandProvider.getCommands()
    }
  }
}
