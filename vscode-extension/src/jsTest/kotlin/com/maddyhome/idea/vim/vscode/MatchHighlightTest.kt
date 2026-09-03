/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimMatchHighlighter
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:match` following the text as it is *typed*.
 *
 * The engine tests cover the patterns, the channels and the errors, and they edit through `:s`
 * because a headless host has no key loop. This one presses keys, which is the half they cannot
 * reach: the repaint after a typed character comes from `KeyHandler`, and a standing highlight that
 * only followed edits made by ex commands would be exactly as wrong as one that followed none.
 *
 * The painting itself is decorations and belongs to VS Code; what is checked here is which ranges
 * were handed over, which is the part this fork wrote.
 */
class MatchHighlightTest {

  /** What was painted, per channel, so a test can read back the ranges rather than the colours. */
  private class RecordingHighlighter : VimMatchHighlighter {
    val shown: MutableMap<Int, List<TextRange>> = mutableMapOf()

    override fun showMatches(editor: VimEditor, channel: Int, group: String, ranges: List<TextRange>) {
      shown[channel] = ranges
    }

    override fun clearMatches(editor: VimEditor, channel: Int) {
      shown.remove(channel)
    }
  }

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val highlighter = RecordingHighlighter()
    val host = VimHost(
      sink = object : MessageSink {
        override fun message(text: String?) {}
        override fun error(text: String?) {}
        override fun status(text: String?) {}
      },
      matchHighlighter = highlighter,
    ).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)

    fun run(command: String) {
      key("<Esc>")
      type(":")
      type(command)
      key("<CR>")
    }

    /** The text of what is lit on [channel], which is what a reader would see highlighted. */
    fun litOn(channel: Int): List<String> =
      highlighter.shown[channel]?.map { fake.document.content.substring(it.startOffset, it.endOffset) }.orEmpty()
  }

  @Test
  fun `test a typed match lights up every occurrence`() {
    val session = Session("one two one")
    session.run("match Search /one/")

    assertEquals(listOf("one", "one"), session.litOn(1))
  }

  /**
   * The repaint after a keystroke, which is the whole reason `:match` differs from a search.
   *
   * Two characters are typed in front of the first match; both matches have to still be lit, and
   * the first has to have moved. An implementation that painted once when the command ran would
   * pass every other test here and fail this one.
   */
  @Test
  fun `test the highlight follows text that is typed in front of it`() {
    val session = Session("one two one")
    session.run("match Search /one/")
    assertEquals(listOf(0, 8), session.highlighter.shown[1]?.map { it.startOffset })

    session.key("<Esc>")
    session.type("gg")
    session.type("i")
    session.type("xx")
    session.key("<Esc>")

    assertEquals("xxone two one", session.fake.document.content)
    assertEquals(listOf("one", "one"), session.litOn(1), "both are still lit")
    assertEquals(listOf(2, 10), session.highlighter.shown[1]?.map { it.startOffset }, "and both moved")
  }

  @Test
  fun `test text typed until it matches becomes lit`() {
    val session = Session("two")
    session.run("match Search /one/")
    assertEquals(emptyList(), session.litOn(1))

    session.key("<Esc>")
    session.type("A")
    session.type(" one")
    session.key("<Esc>")

    assertEquals(listOf("one"), session.litOn(1))
  }

  @Test
  fun `test match none stops the repainting`() {
    val session = Session("one two")
    session.run("match Search /one/")
    session.run("match none")

    session.key("<Esc>")
    session.type("A")
    session.type(" one")
    session.key("<Esc>")

    assertTrue(1 !in session.highlighter.shown, "nothing should be painted after it was put out")
  }

  /** VS Code's own highlighter is the one a real window gets; this checks it can be built at all. */
  @Test
  fun `test the VS Code highlighter maps Vim's groups to theme colours`() {
    assertEquals(
      VsCodeThemeColors.FIND_MATCH_HIGHLIGHT,
      VsCodeThemeColors.VIM_HIGHLIGHT_GROUPS["Search"],
    )
    assertTrue(
      "ErrorMsg" in VsCodeThemeColors.VIM_HIGHLIGHT_GROUPS,
      "the groups a `:match` in a config reaches for should have a colour",
    )
    assertTrue(
      VsCodeThemeColors.VIM_HIGHLIGHT_GROUPS["NoSuchGroup"] == null,
      "an unknown group falls back rather than being invented",
    )
  }
}
