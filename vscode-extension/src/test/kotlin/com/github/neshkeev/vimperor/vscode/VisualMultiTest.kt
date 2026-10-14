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
 * `vim-visual-multi`, the twenty-eighth bundled extension and the largest one upstream has.
 *
 * It is the same multiple cursors as `multiple-cursors` with a *session* around them, and the session
 * is the whole design: inside one, `n`, `N`, `q`, `Q`, `[`, `]`, `<Tab>` and `i`/`a`/`I`/`A` are about
 * the regions, and outside one those keys still mean what Vim means by them. So the tests that matter
 * most here are the pairs - the same key inside and outside - rather than the features one at a time.
 */
class VisualMultiTest {

  private class Session(text: String, setup: List<String> = emptyList()) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      setup.forEach { script(it) }
      injector.extensionLoader.enableExtension(
        ExtensionBean("visual-multi", VsCodeExtensions.PLUGIN_ID, "init", ""),
      )
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    fun script(line: String) =
      injector.vimscriptExecutor.execute(line, host.editorFor(fake), VsCodeExecutionContext, skipHistory = true)

    val content: String get() = fake.document.content
    val carets: Int get() = host.editorFor(fake).carets().size
    val caret: Int get() = host.editorFor(fake).primaryCaret().offset
    val selections: List<Pair<Int, Int>>
      get() = host.editorFor(fake).carets().map { it.selectionStart to it.selectionEnd }
  }

  // ---- it is on at all -------------------------------------------------------------------------

  @Test
  fun `test it is bundled and can be turned on`() {
    Session("one")

    assertTrue(injector.extensionLoader.getEnabledExtensions().any { it.extensionName == "visual-multi" })
  }

  // ---- starting a session ----------------------------------------------------------------------

  /** Find Under: the same `<C-n>` as `multiple-cursors`, and the thing that starts a session. */
  @Test
  fun `test ctrl-n selects the word and then the next occurrence`() {
    val session = Session("foo bar foo baz foo\n")

    session.key("<C-n>")
    assertEquals(1, session.carets, "the first selects the word under the caret")

    session.key("<C-n>")
    assertEquals(2, session.carets)
  }

  /** `<C-n>` takes a count here, where `multiple-cursors`' mappings never have. */
  @Test
  fun `test ctrl-n takes a count`() {
    val session = Session("foo bar foo baz foo\n")

    session.key("<C-n>")
    session.type("2")
    session.key("<C-n>")

    assertEquals(3, session.carets)
  }

  /** Add Cursor Down, which adds a cursor without searching for anything. */
  @Test
  fun `test ctrl-down adds a cursor on the line below`() {
    val session = Session("one\ntwo\nthree\n")

    session.key("<C-Down>")

    assertEquals(2, session.carets)
  }

  // ---- inside the session, versus outside it ---------------------------------------------------

  /**
   * The pair that the whole design is for. Inside a session `n` adds the next region; outside one it
   * is still Vim's "next search match", which here extends the Visual selection.
   */
  @Test
  fun `test n adds a region inside a session`() {
    val session = Session("foo bar foo baz foo\n")

    session.key("<C-n>")
    session.type("n")

    assertEquals(2, session.carets)
  }

  @Test
  fun `test n outside a session is still the search motion`() {
    val session = Session("foo bar foo baz foo\n")

    session.type("/bar")
    session.key("<CR>")
    session.type("v")
    session.type("n")

    assertEquals(1, session.carets, "no session, so no regions")
  }

  /** `q` skips a region - one cursor, moved on - and `Q` takes the last one back. */
  @Test
  fun `test q skips a region and Q removes one`() {
    val session = Session("foo bar foo baz foo\n")

    session.key("<C-n>")
    session.type("q")
    assertEquals(1, session.carets, "skipped, not added")

    session.type("n")
    assertEquals(2, session.carets)

    session.type("Q")
    assertEquals(1, session.carets)
  }

  /** `<Tab>` turns the regions into plain cursors and back. */
  @Test
  fun `test tab switches between regions and cursors`() {
    val session = Session("foo bar foo\n")

    session.key("<C-n>")
    session.type("n")
    assertTrue(session.selections.all { it.second > it.first }, "regions are selections")

    session.key("<Tab>")
    assertTrue(session.selections.all { it.second == it.first }, "cursors select nothing")

    session.key("<Tab>")
    assertTrue(session.selections.all { it.second > it.first }, "and back to regions")
  }

  /**
   * `]` makes the next region the current one without adding any.
   *
   * This is what `VimEditor.makePrimaryCaret` was written for: "the current region" is the primary
   * caret, and nothing in the engine could move it.
   */
  @Test
  fun `test the closing bracket walks the current region`() {
    val session = Session("foo bar foo baz foo\n")

    session.key("<C-n>")
    session.type("nn")
    assertEquals(3, session.carets)
    val before = session.caret

    session.type("]")

    assertTrue(session.caret != before, "the current region moved")
    assertEquals(3, session.carets, "and none was added")
  }

  /** `i` at every region, which in Visual mode would otherwise start a text object. */
  @Test
  fun `test i inserts at every region`() {
    val session = Session("foo bar foo\n")

    session.key("<C-n>")
    session.type("n")
    session.type("i")
    session.type("X")
    session.key("<Esc>")

    assertEquals("Xfoo bar Xfoo\n", session.content)
  }

  /** `A` appends at the end of each region's line. */
  @Test
  fun `test A appends at the end of the line`() {
    val session = Session("foo\nfoo\n")

    session.key("<C-n>")
    session.type("n")
    session.type("A")
    session.type("!")
    session.key("<Esc>")

    assertEquals("foo!\nfoo!\n", session.content)
  }

  // ---- leaving and coming back -----------------------------------------------------------------

  /** `<Esc>` exits with one cursor, and Reselect Last brings the regions back. */
  @Test
  fun `test escape exits and reselect last brings the regions back`() {
    val session = Session("foo bar foo\n")

    session.key("<C-n>")
    session.type("n")
    assertEquals(2, session.carets)

    session.key("<Esc>")
    assertEquals(1, session.carets)

    session.type("\\\\gS")
    assertEquals(2, session.carets, "the regions of the last session")
  }

  // ---- a Visual selection as the starting point -------------------------------------------------

  /** Visual Cursors: a cursor on each line of the selection, in the column where it starts. */
  @Test
  fun `test visual cursors puts a cursor on each line`() {
    val session = Session("one\ntwo\nthree\n")

    session.type("Vjj")
    session.type("\\\\c")

    assertEquals(3, session.carets)
  }

  /** Visual Add: a linewise selection becomes a region per line. */
  @Test
  fun `test visual add turns the lines into regions`() {
    val session = Session("one\ntwo\nthree\n")

    session.type("Vj")
    session.type("\\\\a")

    assertEquals(2, session.carets)
    assertTrue(session.selections.all { it.second > it.first })
  }

  // ---- what the session can run ----------------------------------------------------------------

  /** The case-conversion menu, which asks for a key and converts each region. */
  @Test
  fun `test the case conversion menu uppercases every region`() {
    val session = Session("foo bar foo\n")

    session.key("<C-n>")
    session.type("n")
    session.type("\\\\C")
    session.type("U")

    assertEquals("FOO bar FOO\n", session.content)
  }

  /**
   * Run Normal, which asks for a command and runs it at each cursor through `:normal`.
   *
   * The cursor is at the *end* of its region, which is vim-visual-multi's own rule, so `x` takes the
   * last character of each `foo` rather than the first.
   *
   * The command is read with `readLine`, not with a command line: a command line opened from inside
   * a mapping's handler is gone by the time the next key arrives, and the `x` meant for the prompt
   * deleted the regions instead. See the note on `readLine`.
   */
  @Test
  fun `test run normal runs a command at every cursor`() {
    val session = Session("foo bar foo\n")

    session.key("<C-n>")
    session.type("n")
    session.type("\\\\z")
    session.type("x")
    session.key("<CR>")

    assertEquals("fo bar fo\n", session.content)
  }

  /**
   * Run Last Normal repeats the command without asking again, which is the whole of what it adds.
   *
   * Asserted as "it ran at both cursors" rather than as an exact string: where the cursors sit after
   * a multi-caret `:normal x` is the engine's bookkeeping rather than this extension's, and pinning
   * it here would be testing the wrong thing.
   */
  @Test
  fun `test run last normal repeats the command`() {
    val session = Session("foo bar foo\n")

    session.key("<C-n>")
    session.type("n")
    session.type("\\\\z")
    session.type("x")
    session.key("<CR>")
    val afterFirst = session.content

    session.type("\\\\Z")

    assertEquals(afterFirst.length - 2, session.content.length, "one character gone at each of the two cursors")
  }

  /** Align, which pads with spaces until every cursor is in the rightmost one's column. */
  @Test
  fun `test align pads the cursors into one column`() {
    val session = Session("a foo\nbbbb foo\n")

    // `w` first: the word under the caret is `a`, which occurs once, so a session started here would
    // have one region and nothing to align.
    session.type("w")
    session.key("<C-n>")
    session.type("n")

    session.type("\\\\a")

    assertEquals("a    foo\nbbbb foo\n", session.content)
  }

  // ---- configuration ---------------------------------------------------------------------------

  /**
   * `g:VM_maps` renames the keys, which is the documented way to rebind this extension.
   *
   * One name at a time, which is worth knowing: `Find Under` is the Normal-mode mapping, so moving
   * it to `<C-d>` leaves the Visual-mode `Find Subword Under` on `<C-n>`. The second region is added
   * with `n` here rather than by pressing the new key again.
   */
  @Test
  fun `test VM_maps moves a mapping to other keys`() {
    val session = Session("foo bar foo\n", setup = listOf("let g:VM_maps = {'Find Under': '<C-d>'}"))

    session.key("<C-d>")
    session.type("n")

    assertEquals(2, session.carets)
  }

  /** `g:VM_leader` changes the prefix the `\\`-mappings hang off. */
  @Test
  fun `test VM_leader changes the prefix`() {
    val session = Session("one\ntwo\nthree\n", setup = listOf("let g:VM_leader = ','"))

    session.type("Vjj")
    session.type(",c")

    assertEquals(3, session.carets)
  }

  /**
   * `g:VM_case_setting` of `ignore` makes the search case-insensitive, where `multiple-cursors` is
   * always case-sensitive.
   */
  @Test
  fun `test VM_case_setting ignore finds a differently cased occurrence`() {
    val session = Session("foo bar FOO\n", setup = listOf("let g:VM_case_setting = 'ignore'"))

    session.key("<C-n>")
    session.type("n")

    assertEquals(2, session.carets)
  }

  @Test
  fun `test the default case setting follows ignorecase`() {
    val session = Session("foo bar FOO\n", setup = listOf("set noignorecase"))

    session.key("<C-n>")
    session.type("n")

    assertEquals(1, session.carets, "nothing else matches case-sensitively")
  }
}
