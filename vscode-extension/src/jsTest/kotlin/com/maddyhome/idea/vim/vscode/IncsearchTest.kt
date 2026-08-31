/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.common.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `'incsearch'` - what is painted while a search is still being typed.
 *
 * This port had written incsearch off, on the grounds that the preview needs the pattern as typed so
 * far and that arrives on the command line rather than through the search group. Both halves were
 * true and the conclusion was wrong: the command line here *is* this host's, so the pattern as typed
 * so far is a string it already has. The reasoning was about IntelliJ's command line, which is a
 * text field with a document listener, and it was never re-examined after this host stopped having
 * one.
 */
class IncsearchTest {

  private class RecordingHighlighter : Highlighter {
    var matches: List<TextRange> = emptyList()
      private set
    var current: TextRange? = null
      private set

    override fun showMatches(editor: VsCodeEditor, ranges: List<TextRange>) { matches = ranges }
    override fun showCurrentMatch(editor: VsCodeEditor, range: TextRange?) { current = range }
    override fun showConfirmation(editor: VsCodeEditor, range: TextRange): () -> Unit = {}
    override fun clear(editor: VsCodeEditor) { matches = emptyList() }
    override fun isShowingAnything(): Boolean = matches.isNotEmpty()

    fun matchedIn(text: String): List<String> = matches.map { text.substring(it.startOffset, it.endOffset) }
    fun currentIn(text: String): String? = current?.let { text.substring(it.startOffset, it.endOffset) }
  }

  private class Session(val text: String, incsearch: Boolean = true) {
    val fake = FakeEditor(text)
    val highlighter = RecordingHighlighter()
    val host = VimHost(highlighter = highlighter).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      if (incsearch) type(":set incsearch\n".dropLast(1))
      if (incsearch) key("<CR>")
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    /** Where the view is, so that the match being previewed can be seen to have been scrolled to. */
    val topLine: Int get() = fake.topLine
  }

  @Test
  fun `test matches are painted while the pattern is still being typed`() {
    val session = Session("one two one three")
    session.type("/")
    session.type("on")

    assertEquals(listOf("on", "on"), session.highlighter.matchedIn(session.text))
  }

  @Test
  fun `test the match the search would land on is the current one`() {
    val session = Session("one two one three")
    session.type("/")
    session.type("one")

    assertEquals("one", session.highlighter.currentIn(session.text))
    assertEquals(8, session.highlighter.current?.startOffset, "the caret is at 0, so the next match is the second one")
  }

  @Test
  fun `test a backwards search previews the match behind the caret`() {
    // `www` puts the caret on "three", at offset 12, with matches at 0, 8 and 18.
    val session = Session("one two one three one")
    session.type("www")
    session.type("?")
    session.type("one")

    assertEquals(8, session.highlighter.current?.startOffset)
  }

  @Test
  fun `test the preview follows the pattern as it grows`() {
    val session = Session("alpha alps al")
    session.type("/")
    session.type("al")
    assertEquals(3, session.highlighter.matches.size)
    session.type("p")
    assertEquals(2, session.highlighter.matches.size)
    session.type("h")
    assertEquals(1, session.highlighter.matches.size)
  }

  @Test
  fun `test deleting the whole pattern clears the preview`() {
    val session = Session("one two one")
    session.type("/")
    session.type("one")
    assertTrue(session.highlighter.matches.isNotEmpty())

    session.key("<BS>")
    session.key("<BS>")
    session.key("<BS>")

    assertEquals(emptyList(), session.highlighter.matches)
  }

  /** A half-typed regex is not a regex. `\(` on its way to `\(foo\)` must not throw or complain. */
  @Test
  fun `test a pattern that is not yet valid paints nothing and says nothing`() {
    val session = Session("one two one")
    session.type("/")
    session.type("\\(on")

    assertEquals(emptyList(), session.highlighter.matches)
  }

  /** `/foo/e` searches for `foo`; the offset is not part of the pattern. */
  @Test
  fun `test a search offset is not part of the previewed pattern`() {
    val session = Session("one two one")
    session.type("/")
    session.type("one/e")

    assertEquals(listOf("one", "one"), session.highlighter.matchedIn(session.text))
  }

  @Test
  fun `test the preview scrolls the match into view`() {
    val session = Session((0 until 40).joinToString("\n") { if (it == 30) "needle" else "line $it" })
    assertEquals(0, session.topLine)

    session.type("/")
    session.type("needle")

    assertTrue(session.topLine > 20, "the view should have followed the match, top line is ${session.topLine}")
  }

  @Test
  fun `test nothing is painted while incsearch is off`() {
    val session = Session("one two one", incsearch = false)
    session.type("/")
    session.type("one")

    assertEquals(emptyList(), session.highlighter.matches)
  }

  /**
   * Cancelling puts back what was on screen before, which with `'nohlsearch'` is nothing. The
   * preview is not a search result and must not outlive the prompt that produced it.
   */
  @Test
  fun `test cancelling the search takes the preview away`() {
    val session = Session("one two one")
    session.type("/")
    session.type("one")
    assertTrue(session.highlighter.matches.isNotEmpty())

    session.key("<Esc>")

    assertEquals(emptyList(), session.highlighter.matches)
    assertNull(session.highlighter.current)
  }

  /** And accepting it leaves the search group's own highlights, not the preview's. */
  @Test
  fun `test accepting the search leaves hlsearch in charge`() {
    val session = Session("one two one")
    session.type(":set hlsearch")
    session.key("<CR>")
    session.type("/")
    session.type("one")
    session.key("<CR>")

    assertEquals(listOf("one", "one"), session.highlighter.matchedIn(session.text))
    assertNull(session.highlighter.current, "the preview's current match belongs to the prompt")
  }

  /** A `:` prompt is not a search, and a `:s` preview is a different feature Vim does not have. */
  @Test
  fun `test a colon command line paints nothing`() {
    val session = Session("one two one")
    session.type(":")
    session.type("s/one/two/g")

    assertEquals(emptyList(), session.highlighter.matches)
  }
}
