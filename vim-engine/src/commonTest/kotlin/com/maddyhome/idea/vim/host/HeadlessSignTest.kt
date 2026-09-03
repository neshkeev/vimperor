/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.sign.Signs
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `:sign` - definitions, placements, and the difference between them.
 *
 * Vim keeps those two apart and so does this, which is the thing worth checking rather than
 * asserting: one `:sign define` serves any number of `:sign place`, an `undefine` does not remove
 * what was placed, and a placement remembers a *name* rather than a copy of the definition - so
 * redefining a sign changes every mark already on screen.
 *
 * The colours are `:highlight`'s, resolved before the host sees them, which is what makes
 * `texthl=Search` mean the theme's find colour in a session that never defined `Search` and the
 * user's red in one that did.
 */
class HeadlessSignTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one\ntwo\nthree\nfour", listOf(caret), path = "/work/a.txt")
      .also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val panel: HeadlessOutputPanelService get() = injector.outputPanel as HeadlessOutputPanelService
    val drawn: HeadlessSignDisplay get() = injector.signDisplay as HeadlessSignDisplay

    fun run(line: String): ExecutionResult =
      injector.vimscriptExecutor.execute(
        line,
        editor,
        HeadlessExecutionContext,
        skipHistory = true,
        indicateErrors = true,
        CommandLineVimLContext,
      )

    fun clearPanel() = injector.outputPanel.clear(editor, HeadlessExecutionContext)

    fun printed(command: String): String {
      clearPanel()
      run(command)
      return panel.lines.joinToString("")
    }
  }

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session()
  }

  // ---- defining ----------------------------------------------------------------------------------

  @Test
  fun `test a definition keeps every attribute it was given`() {
    val s = session()
    s.run("sign define piet text=>> texthl=Search linehl=Todo numhl=ErrorMsg priority=20")

    val definition = Signs.definition("piet")
    assertNotNull(definition)
    assertEquals(">>", definition.text)
    assertEquals("Search", definition.textHighlight)
    assertEquals("Todo", definition.lineHighlight)
    assertEquals("ErrorMsg", definition.numberHighlight)
    assertEquals(20, definition.priority)
  }

  /** Vim: "define a new sign **or set attributes for an existing sign**". */
  @Test
  fun `test defining a sign again changes only what was named`() {
    val s = session()
    s.run("sign define piet text=>> texthl=Search")
    s.run("sign define piet linehl=Todo")

    val definition = Signs.definition("piet")
    assertEquals(">>", definition?.text, "the text from the first command survives")
    assertEquals("Todo", definition?.lineHighlight)
  }

  /** Vim: "leading zeros are ignored, thus 0012, 012 and 12 are considered the same name". */
  @Test
  fun `test a numeric name ignores its leading zeros`() {
    val s = session()
    s.run("sign define 0012 text=aa")

    assertEquals("aa", Signs.definition("12")?.text)
    assertEquals("aa", Signs.definition("012")?.text)
  }

  @Test
  fun `test sign text of more than two characters is E239`() {
    val s = session()
    s.run("sign define piet text=toolong")

    assertEquals("E239: Invalid sign text: toolong", s.messages.lastError)
  }

  @Test
  fun `test undefining a sign nobody defined is E155`() {
    val s = session()
    s.run("sign undefine nosuchsign")

    assertEquals("E155: Unknown sign: nosuchsign", s.messages.lastError)
  }

  @Test
  fun `test a subcommand Vim does not have is E160`() {
    val s = session()
    s.run("sign sideways")

    assertEquals("E160: Unknown sign command: sideways", s.messages.lastError)
  }

  // ---- placing -----------------------------------------------------------------------------------

  @Test
  fun `test a placed sign reaches the host with its resolved colours`() {
    val s = session()
    s.run("highlight Todo guibg=#503030")
    s.run("sign define piet text=>> texthl=Search linehl=Todo")
    s.run("sign place 1 line=2 name=piet file=/work/a.txt")

    val drawn = s.drawn.shown["/work/a.txt"]?.single()
    assertNotNull(drawn)
    assertEquals(2, drawn.line)
    assertEquals(">>", drawn.text)
    assertEquals("#503030", drawn.lineHighlight?.attributes?.background, "the group the user defined")
    assertNull(drawn.textHighlight?.attributes, "Search was never defined, so the host decides")
  }

  /** A placement remembers a name, so redefining the sign changes every mark already on screen. */
  @Test
  fun `test redefining a sign changes what is already placed`() {
    val s = session()
    s.run("sign define piet text=>>")
    s.run("sign place 1 line=2 name=piet")
    s.run("sign define piet text=<<")
    s.run("sign place 2 line=3 name=piet")

    val drawn = s.drawn.shown["/work/a.txt"].orEmpty()
    assertEquals(listOf("<<", "<<"), drawn.map { it.text })
  }

  @Test
  fun `test placing with no file uses the buffer the command was typed in`() {
    val s = session()
    s.run("sign define piet text=>>")
    s.run("sign place 1 line=2 name=piet")

    assertEquals(listOf("/work/a.txt"), Signs.placed().map { it.path })
  }

  @Test
  fun `test placing the same id again moves it instead of adding another`() {
    val s = session()
    s.run("sign define piet text=>>")
    s.run("sign place 1 line=2 name=piet")
    s.run("sign place 1 line=4 name=piet")

    assertEquals(listOf(4), Signs.placed().map { it.line })
  }

  /** `:sign place {id} name={name} file={f}` with no `line=` re-points a sign without moving it. */
  @Test
  fun `test placing without a line moves an existing sign to a new definition`() {
    val s = session()
    s.run("sign define one text=aa")
    s.run("sign define two text=bb")
    s.run("sign place 1 line=3 name=one")
    s.run("sign place 1 name=two")

    val placed = Signs.placed().single()
    assertEquals(3, placed.line, "it stayed where it was")
    assertEquals("two", placed.name)
  }

  @Test
  fun `test placing a sign nobody defined is E155`() {
    val s = session()
    s.run("sign place 1 line=2 name=nosuchsign")

    assertEquals("E155: Unknown sign: nosuchsign", s.messages.lastError)
  }

  @Test
  fun `test a new sign with no line is E159`() {
    val s = session()
    s.run("sign define piet text=>>")
    s.run("sign place 1 name=piet")

    assertEquals("E159: Missing sign number", s.messages.lastError)
  }

  @Test
  fun `test groups keep two sets of signs apart`() {
    val s = session()
    s.run("sign define piet text=>>")
    s.run("sign place 1 line=2 name=piet")
    s.run("sign place 1 line=3 name=piet group=lint")

    assertEquals(2, Signs.placed().size, "the same id in two groups is two signs")
    assertEquals(listOf(3), Signs.placed(group = "lint").map { it.line })
  }

  // ---- unplacing ---------------------------------------------------------------------------------

  @Test
  fun `test unplacing one id leaves the rest`() {
    val s = session()
    s.run("sign define piet text=>>")
    s.run("sign place 1 line=2 name=piet")
    s.run("sign place 2 line=3 name=piet")
    s.run("sign unplace 1")

    assertEquals(listOf(2), Signs.placed().map { it.id })
  }

  @Test
  fun `test unplace star clears every file`() {
    val s = session()
    s.run("sign define piet text=>>")
    s.run("sign place 1 line=2 name=piet")
    s.run("sign place 2 line=3 name=piet file=/work/b.txt")
    s.run("sign unplace *")

    assertEquals(emptyList(), Signs.placed())
  }

  @Test
  fun `test undefining a sign does not remove what was placed with it`() {
    val s = session()
    s.run("sign define piet text=>>")
    s.run("sign place 1 line=2 name=piet")
    s.run("sign undefine piet")

    assertEquals(1, Signs.placed().size, "Vim says this causes trouble, not that it tidies up")
    assertNull(s.drawn.shown["/work/a.txt"]?.single()?.text, "with nothing left to say what it looks like")
  }

  // ---- listing -----------------------------------------------------------------------------------

  @Test
  fun `test sign list prints what was defined`() {
    val s = session()
    s.run("sign define piet text=>> texthl=Search")

    assertEquals("sign piet text=>> texthl=Search\n", s.printed("sign list"))
  }

  @Test
  fun `test sign place with nothing else lists what is placed`() {
    val s = session()
    s.run("sign define piet text=>>")
    s.run("sign place 5 line=2 name=piet")

    val printed = s.printed("sign place")
    assertTrue("Signs for /work/a.txt:" in printed, printed)
    assertTrue("line=2" in printed && "id=5" in printed && "name=piet" in printed, printed)
  }

  @Test
  fun `test listing when nothing is there says so`() {
    val s = session()

    assertEquals("No signs defined.\n", s.printed("sign list"))
    assertEquals("No signs placed.\n", s.printed("sign place"))
  }

  // ---- jumping -----------------------------------------------------------------------------------

  @Test
  fun `test sign jump moves the caret to the line the sign is on`() {
    val s = session()
    s.run("sign define piet text=>>")
    s.run("sign place 1 line=3 name=piet")
    s.run("sign jump 1")

    assertEquals(2, s.editor.currentCaret().getBufferPosition().line, "line 3 counting from one")
  }

  @Test
  fun `test jumping to an id nobody placed is E157`() {
    val s = session()
    s.run("sign jump 9")

    assertEquals("E157: Invalid sign ID: 9", s.messages.lastError)
  }

  // ---- painting ----------------------------------------------------------------------------------

  /**
   * The one thing that separates this from `:match`, and the reason it is not the same hook.
   *
   * A standing highlight has to be recomputed after every keystroke because its ranges come from
   * the text. A sign sits on a line, and both hosts' markers already follow that line as text is
   * inserted above it - so handing the host an unchanged list on every key would move the sign back
   * to where it was placed and undo exactly the tracking the host is doing.
   */
  @Test
  fun `test an unchanged list is not handed to the host again`() {
    val s = session()
    s.run("sign define piet text=>>")
    s.run("sign place 1 line=2 name=piet")
    val afterPlacing = s.drawn.calls

    Signs.repaint(s.editor)
    Signs.repaint(s.editor)
    assertEquals(afterPlacing, s.drawn.calls, "nothing changed, so nothing was repainted")

    s.run("sign place 2 line=3 name=piet")
    assertTrue(s.drawn.calls > afterPlacing, "a new sign is a change and is painted")
  }

  @Test
  fun `test clearing the last sign paints an empty list rather than nothing`() {
    val s = session()
    s.run("sign define piet text=>>")
    s.run("sign place 1 line=2 name=piet")
    s.run("sign unplace 1")

    assertEquals(emptyList(), s.drawn.shown["/work/a.txt"], "the host has to be told to take it off")
  }
}
