/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.common.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `'hlsearch'`, and what the engine asks a host to paint.
 *
 * The base class hands over a pattern and the host does the searching, which is also how IntelliJ's
 * works. That split is right: *which* ranges to paint is a screen question, and only a host knows
 * which lines are worth searching.
 */
class SearchHighlightTest {

  /** Records what would be painted, so a test can read it without a VS Code window. */
  private class RecordingHighlighter : Highlighter {
    var matches: List<TextRange> = emptyList()
      private set
    var confirmations: Int = 0
      private set
    var cleared: Int = 0
      private set

    override fun showMatches(editor: VsCodeEditor, ranges: List<TextRange>) {
      matches = ranges
    }

    override fun showCurrentMatch(editor: VsCodeEditor, range: TextRange?) {}

    override fun showConfirmation(editor: VsCodeEditor, range: TextRange): () -> Unit {
      confirmations++
      return { confirmations-- }
    }

    override fun clear(editor: VsCodeEditor) {
      matches = emptyList()
      cleared++
    }

    override fun isShowingAnything(): Boolean = matches.isNotEmpty()

    /** The matched text, which reads better in a failure than a list of offsets. */
    fun matchedIn(text: String): List<String> = matches.map { text.substring(it.startOffset, it.endOffset) }
  }

  private class Session(val text: String) {
    val fake = FakeEditor(text)
    val highlighter = RecordingHighlighter()
    val host = VimHost(highlighter = highlighter).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
  }

  @Test
  fun `test a search highlights every match`() {
    val session = Session("one two one three one")
    session.type("/")
    session.type("one")
    session.key("<CR>")

    assertEquals(listOf("one", "one", "one"), session.highlighter.matchedIn(session.text))
  }

  @Test
  fun `test nohlsearch clears the highlights`() {
    val session = Session("one two one")
    session.type("/")
    session.type("one")
    session.key("<CR>")
    assertTrue(session.highlighter.isShowingAnything())

    session.type(":")
    session.type("nohlsearch")
    session.key("<CR>")

    assertFalse(session.highlighter.isShowingAnything(), "`:nohlsearch` should leave nothing painted")
  }

  @Test
  fun `test a pattern that matches nothing paints nothing`() {
    val session = Session("one two")
    session.type("/")
    session.type("absent")
    session.key("<CR>")

    assertEquals(emptyList(), session.highlighter.matches)
  }

  @Test
  fun `test ignorecase widens what is highlighted`() {
    val session = Session("One one ONE")
    session.type(":")
    session.type("set ignorecase")
    session.key("<CR>")
    session.type("/")
    session.type("one")
    session.key("<CR>")

    assertEquals(listOf("One", "one", "ONE"), session.highlighter.matchedIn(session.text))
  }

  @Test
  fun `test smartcase narrows it again when the pattern has a capital`() {
    // Vim's rule: `'ignorecase'` alone matches anything, but with `'smartcase'` a pattern that
    // contains an uppercase letter is taken literally. Both options are on, and the capital decides.
    val session = Session("One one ONE")
    session.type(":")
    session.type("set ignorecase smartcase")
    session.key("<CR>")
    session.type("/")
    session.type("One")
    session.key("<CR>")

    assertEquals(listOf("One"), session.highlighter.matchedIn(session.text))
  }
}
