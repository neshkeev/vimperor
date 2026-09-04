/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.state.mode.Mode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Insert mode, which is where a host stops being a spectator.
 *
 * Normal-mode commands compute a change and hand it over once. Insert mode is a key at a time, and
 * IdeaVim's answer on IntelliJ - hand each character to the IDE's own typed-action handler, so its
 * auto-indent and bracket closing run - is unavailable here: VS Code's equivalent is a command, and
 * commands are asynchronous. So characters go into the buffer directly, which is what Vim itself
 * does, and the editor's own typing features are a later decision rather than a missing piece.
 */
class VsCodeInsertModeTest {

  private class Session(text: String, caretOffset: Int) {
    val fake = FakeEditor(text)
    val editor: VsCodeEditor

    init {
      injector = VsCodeInjector().also { it.register(VsCodeEditor(fake)) }
      engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      VsCodeCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      injector.functionService.registerHandlers()
      editor = injector.editorGroup.getEditors().first() as VsCodeEditor
      editor.primaryCaret().moveToOffsetNative(caretOffset)
      KeyHandler.getInstance().fullReset(editor)
    }

    fun type(keys: String) {
      val handler = KeyHandler.getInstance()
      val state = handler.keyHandlerState
      for (stroke in injector.parser.parseKeys(keys)) {
        handler.handleKey(editor, stroke, VsCodeExecutionContext, state)
      }
      editor.flush()
    }
  }

  private fun type(text: String, keys: String, caretOffset: Int = 0): String {
    val session = Session(text, caretOffset)
    session.type(keys)
    return session.fake.document.content
  }

  @Test
  fun `test i inserts before the caret`() {
    assertEquals("Xabc", type("abc", "iX<Esc>"))
  }

  @Test
  fun `test a inserts after the caret`() {
    assertEquals("aXbc", type("abc", "aX<Esc>"))
  }

  @Test
  fun `test A appends at the end of the line`() {
    assertEquals("abcX", type("abc", "AX<Esc>"))
  }

  @Test
  fun `test I inserts before the first non-blank`() {
    assertEquals("  Xabc", type("  abc", "IX<Esc>", caretOffset = 4))
  }

  @Test
  fun `test typing several characters keeps them in order`() {
    assertEquals("hello world", type(" world", "ihello<Esc>"))
  }

  @Test
  fun `test o opens a line below`() {
    assertEquals("one\ntwo", type("one", "otwo<Esc>"))
  }

  @Test
  fun `test O opens a line above`() {
    assertEquals("one\ntwo", type("two", "Oone<Esc>"))
  }

  @Test
  fun `test cw changes a word`() {
    assertEquals("ONE two", type("one two", "cwONE<Esc>"))
  }

  @Test
  fun `test backspace deletes what was just typed`() {
    assertEquals("ab", type("", "iabX<BS><Esc>"))
  }

  @Test
  fun `test a whole insert session reaches the document as one edit`() {
    // Every keystroke mutates the buffer; VS Code hears once, at the end. That is what makes an
    // insert session a single undo step without any of the grouping IntelliJ needs a wrapper for.
    val session = Session("", 0)
    session.type("ihello<Esc>")

    assertEquals("hello", session.fake.document.content)
    assertEquals(1, session.fake.recordedEdits.size)
  }

  @Test
  fun `test Escape leaves the caret where Vim puts it`() {
    // Vim steps the caret back one on leaving insert mode, so `x` next would delete the last
    // character typed rather than the one after it.
    val session = Session("", 0)
    session.type("iabc<Esc>")

    assertEquals("abc", session.fake.document.content)
    assertEquals(2, session.editor.primaryCaret().offset)
  }

  /**
   * `<CR>` in Insert mode, which for a long time did nothing at all here.
   *
   * The engine does not insert the newline itself: [VimChangeGroupBase.processEnter] asks the key
   * group what the host has bound to Enter and runs that, because IntelliJ's Enter knows about
   * indenting and brace matching and Vim would rather defer to it. This host answered "nothing",
   * so Insert mode could type every character except a line break - and every test here typed on
   * one line, so nothing noticed.
   */
  @Test
  fun `test Enter in insert mode inserts a line break`() {
    assertEquals("one\ntwo", type("", "ione<CR>two<Esc>"))
  }

  @Test
  fun `test Enter splits the line at the caret`() {
    assertEquals("ab\ncd", type("abcd", "lli<CR><Esc>"))
  }

  @Test
  fun `test Enter opens a line when the caret is at the end`() {
    assertEquals("ab\n", type("ab", "A<CR><Esc>"))
  }

  /**
   * The rest of the keys `package.json` sends to the engine.
   *
   * The extension binds Escape, Backspace, Delete, Enter, Tab, the four arrows and `<C-R>`, because
   * a key VS Code handles itself never reaches Vim. Binding a key the engine has no Insert-mode
   * action for is worse than not binding it: VS Code's own handler is suppressed and Vim's does not
   * exist, so the key does nothing whatsoever. Backspace, the left and right arrows and `<C-R>` are
   * the engine's; Delete, Tab and the vertical arrows are IntelliJ's, and so were nobody's here.
   */
  @Test
  fun `test Delete in insert mode removes the character under the caret`() {
    assertEquals("ac", type("abc", "li<Del><Esc>"))
  }

  @Test
  fun `test Delete at the end of a line joins the next one`() {
    assertEquals("abcd", type("ab\ncd", "A<Del><Esc>"))
  }

  @Test
  fun `test Delete at the end of the file does nothing`() {
    assertEquals("ab", type("ab", "A<Del><Esc>"))
  }

  @Test
  fun `test Up in insert mode moves the caret up a line`() {
    val session = Session("one\ntwo", 5)
    session.type("i<Up>")
    assertEquals(1, session.editor.primaryCaret().offset)
  }

  @Test
  fun `test Down in insert mode moves the caret down a line`() {
    val session = Session("one\ntwo", 1)
    session.type("i<Down>")
    assertEquals(5, session.editor.primaryCaret().offset)
  }

  @Test
  fun `test Up on the first line stays put`() {
    val session = Session("one\ntwo", 1)
    session.type("i<Up>")
    assertEquals(1, session.editor.primaryCaret().offset)
  }

  @Test
  fun `test Up keeps typing where it lands`() {
    assertEquals("oXne\ntwo", type("one\ntwo", "i<Up>X<Esc>", caretOffset = 5))
  }

  // Digraphs. `<C-K>` and the two-character name of a character that is not on the keyboard - a
  // table and a lookup, which the engine carries whole, so the host only has to hand it over.

  @Test
  fun `test a digraph inserts the character it names`() {
    assertEquals("\u00eb", type("", "i<C-K>e:<Esc>"))
  }

  @Test
  fun `test a digraph in the middle of typing`() {
    assertEquals("na\u00efve", type("", "ina<C-K>i:ve<Esc>"))
  }
  // ---- Replace mode, which is Insert mode that overwrites.
  //
  // IntelliJ needs no host code for this: its editor has an insert/overwrite mode and the platform's
  // typing honours it. VS Code has no such mode, so the overwrite happens in `typeAtCarets`. Both of
  // these came from IdeaVim's own fixtures; nothing here had pressed `R`.

  @Test
  fun `test R overwrites what is under the caret`() {
    val session = Session("grzyb\nnext", 0)
    session.type("Rmush")

    assertEquals("mushb\nnext", session.fake.document.content)
  }

  /** Past the end of the line there is nothing to overwrite, so Vim appends instead of eating it. */
  @Test
  fun `test R past the end of a line appends rather than swallowing the next one`() {
    val session = Session("grzyb\nnext", 0)
    session.type("Rmushroom")

    assertEquals("mushroom\nnext", session.fake.document.content)
  }

  /**
   * Backspace in replace mode puts back what was overwritten, which is a stack the engine keeps
   * keyed by a live marker - and it looks entries up by building a *new* marker at the offset. Two
   * markers over the same span have to be equal for that to find anything.
   */
  @Test
  fun `test backspace in replace mode restores the character that was overwritten`() {
    val session = Session("grzyb", 0)
    session.type("Rm<C-H>")

    assertEquals("grzyb", session.fake.document.content)
  }

  // `:startinsert`, which is a command that leaves the user typing.

  @Test
  fun `test startinsert leaves the editor in insert mode`() {
    val session = Session("abc", 1)
    session.type(":startinsert<CR>")

    assertEquals(Mode.INSERT, session.editor.mode)
  }

  /** ...at the caret, like `i`. */
  @Test
  fun `test what is typed after startinsert goes in at the caret`() {
    val session = Session("abc", 1)
    session.type(":startinsert<CR>X<Esc>")

    assertEquals("aXbc", session.fake.document.content)
  }

  /** The bang is `A` rather than `i`, which is the whole of the difference. */
  @Test
  fun `test startinsert bang appends at the end of the line`() {
    val session = Session("abc\nnext", 1)
    session.type(":startinsert!<CR>X<Esc>")

    assertEquals("abcX\nnext", session.fake.document.content)
  }

  /**
   * Already inserting is not an error in Vim, and must not move the caret either.
   *
   * Run through the executor rather than typed, because typing it would be typing it: the only way
   * to reach a command while in Insert is for something else to run it, which a sourced file or an
   * autocommand can.
   */
  @Test
  fun `test startinsert while already inserting does nothing`() {
    val session = Session("abc", 1)
    session.type("i")

    injector.vimscriptExecutor.execute(
      "startinsert!",
      session.editor,
      VsCodeExecutionContext,
      skipHistory = true,
      indicateErrors = true,
    )

    assertEquals(Mode.INSERT, session.editor.mode)
    assertEquals(1, session.editor.primaryCaret().offset, "the bang must not have moved the caret to the line end")
  }

  // `:startreplace`, which is the same command in the other mode.

  @Test
  fun `test startreplace leaves the editor in replace mode`() {
    val session = Session("abc", 1)
    session.type(":startreplace<CR>")

    assertEquals(Mode.REPLACE, session.editor.mode)
  }

  @Test
  fun `test what is typed after startreplace overwrites`() {
    val session = Session("abc", 0)
    session.type(":startreplace<CR>XY<Esc>")

    assertEquals("XYc", session.fake.document.content)
  }

  /** The bang starts at the end of the line, as `:startinsert!` does. */
  @Test
  fun `test startreplace bang starts at the end of the line`() {
    val session = Session("abc\nnext", 0)
    session.type(":startreplace!<CR>X<Esc>")

    assertEquals("abcX\nnext", session.fake.document.content)
  }

  /**
   * `:startgreplace` is Vim's *virtual* replace mode, which this engine does not have.
   *
   * It types over the columns a tab occupies rather than over the tab, and differs from Replace
   * only in a buffer that uses them. Registered as Replace rather than left reporting `E492`, and
   * said out loud here so that a real virtual Replace arriving later is a change to this test.
   */
  @Test
  fun `test startgreplace is replace mode`() {
    val session = Session("abc", 0)
    session.type(":startgreplace<CR>")
    assertEquals(Mode.REPLACE, session.editor.mode)

    session.type("X<Esc>")
    assertEquals("Xbc", session.fake.document.content)
  }
}
