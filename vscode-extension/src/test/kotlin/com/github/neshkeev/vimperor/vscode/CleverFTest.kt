/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.extension.ExtensionBean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `clever-f`, the twenty-ninth bundled extension and the last of IdeaVim's thirty.
 *
 * The extension is one sentence - `f` pressed again repeats its own search - and almost every test here is a pair:
 * what the key does the first time, and what the *same* key does the second. The other half is operator-pending
 * mode, which is where this extension was held up for two passes and where the interesting assertions are:
 * `dfa` has to be inclusive and `dFa` has to not be, and the character arrives a keystroke after `d` was typed.
 */
class CleverFTest {

  private class Session(text: String, setup: List<String> = emptyList()) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      setup.forEach { script(it) }
      injector.extensionLoader.enableExtension(
        ExtensionBean("clever-f", VsCodeExtensions.PLUGIN_ID, "init", ""),
      )
      fake.decorations.clear()
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    fun script(line: String) =
      injector.vimscriptExecutor.execute(line, host.editorFor(fake), VsCodeExecutionContext, skipHistory = true)

    val content: String get() = fake.document.content
    val caret: Int get() = host.editorFor(fake).primaryCaret().offset
    val selection: Pair<Int, Int>
      get() = host.editorFor(fake).primaryCaret().let { it.selectionStart to it.selectionEnd }

    /** Every offset a decoration is currently painting, which is where the candidate marks show up. */
    fun highlighted(): List<Int> =
      fake.decorations.values.flatten().map { it.start.character }.sorted()
  }

  // ---- it is on at all -------------------------------------------------------------------------

  @Test
  fun `test it is bundled and can be turned on`() {
    Session("one")

    assertTrue(injector.extensionLoader.getEnabledExtensions().any { it.extensionName == "clever-f" })
  }

  // ---- the one sentence ------------------------------------------------------------------------

  @Test
  fun `test f jumps to the character as it always did`() {
    val session = Session("one a two a three\n")

    session.type("fa")

    assertEquals(4, session.caret)
  }

  @Test
  fun `test f again repeats the search instead of asking`() {
    val session = Session("one a two a three\n")

    session.type("fa")
    session.type("f")

    assertEquals(10, session.caret, "the second f needs no character")
  }

  @Test
  fun `test F after f searches backwards`() {
    val session = Session("one a two a three\n")

    session.type("fa")
    session.type("f")
    session.type("F")

    assertEquals(4, session.caret)
  }

  /** The cursor moved, so the search is over and `f` is a question again. */
  @Test
  fun `test a cursor move between the two makes f ask again`() {
    val session = Session("one a two a three\n")

    session.type("fa")
    session.type("l")
    session.type("ft")

    assertEquals(6, session.caret, "f after a move searched for the t it was given")
  }

  @Test
  fun `test t stops in front and repeats past it`() {
    val session = Session("one a two a three\n")

    session.type("ta")

    assertEquals(3, session.caret)

    session.type("t")

    assertEquals(9, session.caret, "a repeat has to skip the character it is already in front of")
  }

  @Test
  fun `test a count takes the nth match`() {
    val session = Session("one a two a three a\n")

    session.type("2fa")

    assertEquals(10, session.caret)
  }

  /** clever-f crosses lines where Vim's own `f` does not. */
  @Test
  fun `test a search crosses lines by default`() {
    val session = Session("one two\nthree a four\n")

    session.type("fa")

    assertEquals(14, session.caret)
  }

  @Test
  fun `test across_no_line keeps the search on the line`() {
    val session = Session("one two\nthree a four\n", listOf("let g:clever_f_across_no_line = 1"))

    session.type("fa")

    assertEquals(0, session.caret, "nothing on this line to find")
  }

  // ---- operator-pending mode -------------------------------------------------------------------

  @Test
  fun `test df takes the character with it`() {
    val session = Session("one a two a\n")

    session.type("dfa")

    assertEquals(" two a\n", session.content)
  }

  @Test
  fun `test dt stops in front of the character`() {
    val session = Session("one a two\n")

    session.type("dta")

    assertEquals("a two\n", session.content)
  }

  @Test
  fun `test dF backwards leaves the character the cursor was on`() {
    val session = Session("one a two\n")

    session.type("$")
    session.type("dFa")

    assertEquals("one o\n", session.content)
  }

  @Test
  fun `test df with a count`() {
    val session = Session("one a two a three\n")

    session.type("d2fa")

    assertEquals(" three\n", session.content)
  }

  /**
   * Each mode keeps its own search - clever-f's `s:previous_*[mode]` dictionaries - and
   * operator-pending is one of the three. So a search made in Normal mode is not a search `d` can repeat, and `df`
   * after `fa` asks for its character like any first search.
   *
   * This test was written the other way round first, asserting a repeat, and the port was right.
   */
  @Test
  fun `test the operator has its own search, so df after f still asks`() {
    val session = Session("one a two a three\n")

    session.type("fa")
    session.type("df")

    assertEquals("one a two a three\n", session.content, "still waiting for a character")

    session.type("a")

    assertEquals("one  three\n", session.content)
  }

  @Test
  fun `test escape at the prompt leaves the operator with nothing pending`() {
    val session = Session("one a two\n")

    session.type("d")
    session.key("<Esc>")
    session.type("w")

    assertEquals("one a two\n", session.content, "the w must be a motion, not a d's argument")
    assertEquals(4, session.caret)
  }

  /**
   * `.` is a keystroke replay here - see `VimRedoBuffer` - so a `dfa` comes back as the keys `d`, `f`, `a`, and the
   * `f` reads its character out of `Extension`'s record rather than from the user. That is `readKeys`'
   * `inRepeatMode` branch, and this is the test that it is reached.
   */
  @Test
  fun `test dot repeats a df`() {
    val session = Session("one a two a three a\n")

    session.type("dfa")
    session.type(".")

    assertEquals(" three a\n", session.content)
  }

  /**
   * Turning the extension on starts from nothing.
   *
   * Upstream gets this by constructing a session per `CleverFExtension`; here the session is a file-level `val` and
   * outlives one. Two of IdeaVim's own fixtures found it, and the worse of the two was `falfb` - a search that
   * should not have existed was repeated, so the `a` meant for the prompt was read as an append and `lfb` was typed
   * into the buffer.
   */
  @Test
  fun `test turning it off and on again forgets the last character`() {
    val session = Session("one a two\n")

    session.type("fa")
    session.script("set noclever-f")
    session.script("set clever-f")
    session.type("0")
    session.type("f")
    session.key("<CR>")

    assertEquals(0, session.caret, "there is no last character to repeat")
  }

  // ---- Visual mode -----------------------------------------------------------------------------

  @Test
  fun `test f extends a Visual selection`() {
    val session = Session("one a two\n")

    session.type("v")
    session.type("fa")

    assertEquals(0 to 5, session.selection)
  }

  @Test
  fun `test the Visual search is kept apart from the Normal one`() {
    val session = Session("one a two a\n")

    session.type("ft")
    session.type("v")
    session.type("fa")

    assertEquals(10, session.caret, "Visual mode had no search of its own, so it took the a")
  }

  // ---- what ends a search ----------------------------------------------------------------------

  @Test
  fun `test a text change ends the search`() {
    val session = Session("one a two a three\n")

    session.type("fa")
    session.type("x")
    session.type("ft")

    assertEquals(5, session.caret, "f after an edit searched for the t it was given")
  }

  @Test
  fun `test an insert ends the search`() {
    val session = Session("one a two a three\n")

    session.type("fa")
    session.type("i")
    session.key("<Esc>")
    session.type("ft")

    assertEquals(6, session.caret)
  }

  @Test
  fun `test the reset mapping ends the search`() {
    val session = Session("one a two a three\n")

    session.script("nmap <Space> <Plug>(clever-f-reset)")
    session.type("fa")
    session.key("<Space>")
    session.type("ft")

    assertEquals(6, session.caret)
  }

  // ---- the variables ---------------------------------------------------------------------------

  @Test
  fun `test ignore_case finds the other case`() {
    val session = Session("one A two\n", listOf("let g:clever_f_ignore_case = 1"))

    session.type("fa")

    assertEquals(4, session.caret)
  }

  /** smartcase is the half-measure: a lowercase search ignores case, an uppercase one does not. */
  @Test
  fun `test smart_case only ignores case for a lowercase character`() {
    val session = Session("one A two a\n", listOf("let g:clever_f_smart_case = 1"))

    session.type("fA")

    assertEquals(4, session.caret)

    val other = Session("one A two a\n", listOf("let g:clever_f_smart_case = 1"))
    other.type("fa")

    assertEquals(4, other.caret, "a lowercase a matched the A")
  }

  @Test
  fun `test chars_match_any_signs makes one sign stand for all of them`() {
    // A Vim literal string, so the dot does not have to survive two layers of quoting.
    val session = Session("one; two\n", listOf("let g:clever_f_chars_match_any_signs = '.'"))

    session.type("f.")

    assertEquals(3, session.caret, "the semicolon matched a dot")
  }

  @Test
  fun `test enter repeats the last searched character`() {
    val session = Session("one a two a\n")

    session.type("fa")
    session.type("0")
    session.type("f")
    session.key("<CR>")

    assertEquals(4, session.caret)
  }

  @Test
  fun `test fix_key_direction makes f always forward`() {
    val session = Session("one a two a three a\n", listOf("let g:clever_f_fix_key_direction = 1"))

    session.type("Fa")
    assertEquals(0, session.caret, "nothing behind the cursor")

    session.type("$")
    session.type("Fa")
    assertEquals(10, session.caret)
    session.type("f")
    assertEquals(18, session.caret, "with fix_key_direction a lowercase key searches forward")
  }

  @Test
  fun `test not_overwrites_standard_mappings leaves f alone`() {
    val session = Session("one a two a\n", listOf("let g:clever_f_not_overwrites_standard_mappings = 1"))

    session.type("fa")
    session.type("f")

    assertEquals(4, session.caret, "the second f is Vim's own and has no character yet")
    session.type("a")
    assertEquals(10, session.caret)
  }

  // ---- the candidate marks ---------------------------------------------------------------------

  @Test
  fun `test the characters the search can reach on the line are marked`() {
    val session = Session("one a two a three\n")

    session.type("fa")

    assertEquals(listOf(4, 10), session.highlighted())
  }

  @Test
  fun `test mark_char off marks nothing`() {
    val session = Session("one a two a three\n", listOf("let g:clever_f_mark_char = 0"))

    session.type("fa")

    assertEquals(emptyList(), session.highlighted())
  }

  @Test
  fun `test the marks go when the search does`() {
    val session = Session("one a two a three\n")

    session.type("fa")
    session.type("j")

    assertEquals(emptyList(), session.highlighted())
  }
}
