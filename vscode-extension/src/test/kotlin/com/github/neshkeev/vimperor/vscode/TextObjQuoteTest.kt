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

/**
 * `vim-textobj-quote`: `iq`/`aq` and `iQ`/`aQ` for curly quotes, and `:Educate`.
 *
 * IdeaVim's three suites, played here. A `|` in an expected string is the caret.
 */
class TextObjQuoteTest {

  private class Session(text: String, enableNow: Boolean = true) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      if (enableNow) enable()
    }

    fun enable() {
      injector.extensionLoader.enableExtension(
        ExtensionBean("textobj-quote", VsCodeExtensions.PLUGIN_ID, "init", ""),
      )
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    fun script(line: String) =
      injector.vimscriptExecutor.execute(line, host.editorFor(fake), VsCodeExecutionContext, skipHistory = true)

    fun ex(command: String) {
      type(":$command")
      key("<CR>")
    }

    /** The caret sits on the first character of [fragment]. */
    fun at(fragment: String) {
      val offset = fake.document.content.indexOf(fragment)
      check(offset >= 0) { "no '$fragment' in '${fake.document.content}'" }
      host.editorFor(fake).primaryCaret().moveToOffset(offset)
    }

    fun caretsAt(vararg offsets: Int) {
      val editor = host.editorFor(fake)
      editor.primaryCaret().moveToOffset(offsets.first())
      offsets.drop(1).forEach { editor.addCaret(it) }
    }

    val content: String get() = fake.document.content
    val caret: Int get() = host.editorFor(fake).primaryCaret().offset
    val carets: List<Int> get() = host.editorFor(fake).carets().map { it.offset }
    val selection: Pair<Int, Int>
      get() = host.editorFor(fake).primaryCaret().let { it.selectionStart to it.selectionEnd }
  }

  private fun assertState(session: Session, marked: String) {
    val caret = marked.indexOf('|')
    check(caret >= 0) { marked }
    assertEquals(marked.removeRange(caret, caret + 1), session.content)
    assertEquals(caret, session.caret)
  }

  private fun assertCarets(session: Session, marked: String) {
    val offsets = mutableListOf<Int>()
    val text = StringBuilder()
    for (ch in marked) {
      if (ch == '|') offsets += text.length else text.append(ch)
    }
    assertEquals(text.toString(), session.content)
    assertEquals(offsets, session.carets)
  }

  // ---- iq / aq ---------------------------------------------------------------------------------

  @Test
  fun `test delete inside curly double quotes`() {
    val session = Session("She said “hello there” and left")
    session.at("llo")

    session.type("diq")

    assertState(session, "She said “|” and left")
  }

  @Test
  fun `test delete around curly double quotes`() {
    val session = Session("She said “hello there” and left")
    session.at("llo")

    session.type("daq")

    assertState(session, "She said | and left")
  }

  @Test
  fun `test delete inside curly double quotes with caret on opening quote`() {
    val session = Session("She said “hello there” and left")
    session.at("“")

    session.type("diq")

    assertState(session, "She said “|” and left")
  }

  @Test
  fun `test delete inside curly double quotes with caret on closing quote`() {
    val session = Session("She said “hello there” and left")
    session.at("”")

    session.type("diq")

    assertState(session, "She said “|” and left")
  }

  @Test
  fun `test delete inside does nothing when caret is between two quoted strings`() {
    val session = Session("“hello” and then “bye”")
    session.at("then")

    session.type("diq")

    assertState(session, "“hello” and |then “bye”")
  }

  @Test
  fun `test delete inside selects outer pair of nested quotes`() {
    val session = Session("She said “ask “why” first” and left")
    session.at("sk")

    session.type("diq")

    assertState(session, "She said “|” and left")
  }

  @Test
  fun `test delete inside selects inner pair of nested quotes`() {
    val session = Session("She said “ask “why” first” and left")
    session.at("hy")

    session.type("diq")

    assertState(session, "She said “ask “|” first” and left")
  }

  @Test
  fun `test delete inside in empty buffer does nothing`() {
    val session = Session("")

    session.type("diq")

    assertEquals("", session.content)
    assertEquals(0, session.caret)
  }

  @Test
  fun `test delete inside on empty last line does nothing`() {
    val session = Session("“hello”\n")
    session.host.editorFor(session.fake).primaryCaret().moveToOffset(session.content.length)

    session.type("diq")

    assertEquals("“hello”\n", session.content)
  }

  @Test
  fun `test select inside curly double quotes`() {
    val session = Session("She said “hello there” and left")
    session.at("llo")

    session.type("viq")

    assertEquals("VISUAL", session.host.modeName())
    val (start, end) = session.selection
    assertEquals("hello there", session.content.substring(start, end))
  }

  @Test
  fun `test select around curly double quotes`() {
    val session = Session("She said “hello there” and left")
    session.at("llo")

    session.type("vaq")

    assertEquals("VISUAL", session.host.modeName())
    val (start, end) = session.selection
    assertEquals("“hello there”", session.content.substring(start, end))
  }

  @Test
  fun `test delete inside curly single quotes`() {
    val session = Session("She said ‘hello there’ and left")
    session.at("llo")

    session.type("diQ")

    assertState(session, "She said ‘|’ and left")
  }

  @Test
  fun `test delete around curly single quotes`() {
    val session = Session("She said ‘hello there’ and left")
    session.at("llo")

    session.type("daQ")

    assertState(session, "She said | and left")
  }

  @Test
  fun `test delete inside single quotes skips apostrophe after caret`() {
    val session = Session("She said ‘I don’t know’ and left")
    session.at("n’t")

    session.type("diQ")

    assertState(session, "She said ‘|’ and left")
  }

  @Test
  fun `test delete inside single quotes skips apostrophe before caret`() {
    val session = Session("She said ‘I don’t know’ and left")
    session.at("now")

    session.type("diQ")

    assertState(session, "She said ‘|’ and left")
  }

  @Test
  fun `test delete inside single quotes closed before punctuation`() {
    val session = Session("‘hello there’, she said")
    session.at("llo")

    session.type("diQ")

    assertState(session, "‘|’, she said")
  }

  @Test
  fun `test delete inside quotes spanning multiple lines`() {
    val session = Session("She said “hello\nworld” and left")
    session.at("rld")

    session.type("diq")

    assertState(session, "She said “|” and left")
  }

  @Test
  fun `test change inside curly double quotes`() {
    val session = Session("She said “hello there” and left")
    session.at("llo")

    session.type("ciqbye")
    session.key("<Esc>")

    assertState(session, "She said “by|e” and left")
  }

  @Test
  fun `test repeat change inside curly double quotes`() {
    val session = Session("“aa” “bb”")
    session.at("a”")

    session.type("ciqX")
    session.key("<Esc>")
    session.type("fb.")

    assertState(session, "“X” “|X”")
  }

  @Test
  fun `test select inside single quotes with apostrophes before and after caret`() {
    val session = Session("‘Really, I’d rather not relive the ’70s,’ said zombie Elvis.")
    session.at("live")

    session.type("viQ")

    assertEquals("VISUAL", session.host.modeName())
    val (start, end) = session.selection
    assertEquals("Really, I’d rather not relive the ’70s,", session.content.substring(start, end))
  }

  @Test
  fun `test set textobj-quote maps iq`() {
    val session = Session("She said “hello there” and left", enableNow = false)
    session.ex("set textobj-quote")
    session.at("llo")

    session.type("diq")

    assertState(session, "She said “|” and left")
  }

  // ---- the keys and the quote characters -------------------------------------------------------

  @Test
  fun `test custom double quote motion`() {
    val session = Session("She said “hello there” and left", enableNow = false)
    session.script("let g:textobj#quote#doubleMotion = 'x'")
    session.enable()
    session.at("llo")

    session.type("dix")

    assertState(session, "She said “|” and left")
  }

  @Test
  fun `test custom single quote motion`() {
    val session = Session("She said ‘hello there’ and left", enableNow = false)
    session.script("let g:textobj#quote#singleMotion = 'x'")
    session.enable()
    session.at("llo")

    session.type("dix")

    assertState(session, "She said ‘|’ and left")
  }

  @Test
  fun `test plug mapping`() {
    val session = Session("She said “hello there” and left")
    session.script("omap ix <Plug>(textobj-quote-select-d-i)")
    session.at("llo")

    session.type("dix")

    assertState(session, "She said “|” and left")
  }

  @Test
  fun `test custom double quotes`() {
    val session = Session("Sie sagte „Hallo“ und ging")
    session.script("let g:textobj#quote#doubleDefault = '„“'")
    session.at("llo")

    session.type("diq")

    assertState(session, "Sie sagte „|“ und ging")
  }

  @Test
  fun `test custom single closing quote followed by a letter is not an apostrophe`() {
    val session = Session("‚Hallo‘x und ‚Welt‘")
    session.script("let g:textobj#quote#singleDefault = '‚‘'")
    session.at("llo")

    session.type("diQ")

    assertState(session, "‚|‘x und ‚Welt‘")
  }

  @Test
  fun `test invalid custom quotes fall back to the default ones`() {
    val session = Session("She said “hello there” and left")
    session.script("let g:textobj#quote#doubleDefault = 'x'")
    session.at("llo")

    session.type("diq")

    assertState(session, "She said “|” and left")
  }

  // ---- :Educate --------------------------------------------------------------------------------

  @Test
  fun `test educate turns straight double quotes into curly ones`() {
    val session = Session("")
    session.ex("Educate")

    session.type("i\"hello\"")

    assertState(session, "“hello”|")
    assertEquals("INSERT", session.host.modeName())
  }

  @Test
  fun `test straight double quotes are kept without educate`() {
    val session = Session("")

    session.type("i\"hello\"")

    assertState(session, "\"hello\"|")
  }

  @Test
  fun `test double quote after closing parenthesis is a closing one`() {
    val session = Session("")
    session.ex("Educate")

    session.type("i(a)\"")

    assertState(session, "(a)”|")
  }

  @Test
  fun `test double quote after straight single quote is an opening one`() {
    val session = Session("x")
    session.ex("Educate")
    session.type("i")
    session.key("<C-V>")
    session.type("'\"")

    assertState(session, "'“|x")
  }

  @Test
  fun `test double quote after curly single quote is an opening one`() {
    val session = Session("")
    session.ex("Educate")

    session.type("i'\"")

    assertState(session, "‘“|")
  }

  @Test
  fun `test single quote after curly double quote is an opening one`() {
    val session = Session("")
    session.ex("Educate")

    session.type("i\"'")

    assertState(session, "“‘|")
  }

  @Test
  fun `test educate turns straight single quotes into curly ones`() {
    val session = Session("")
    session.ex("Educate")

    session.type("i'hello'")

    assertState(session, "‘hello’|")
  }

  @Test
  fun `test educate turns apostrophe into curly one`() {
    val session = Session("")
    session.ex("Educate")

    session.type("idon't")

    assertState(session, "don’t|")
  }

  @Test
  fun `test NoEducate keeps straight quotes`() {
    val session = Session("x")
    session.ex("Educate")
    session.ex("NoEducate")

    session.type("i\"a'")

    assertState(session, "\"a'|x")
  }

  @Test
  fun `test ToggleEducate switches educating on and off`() {
    val session = Session("")
    session.ex("ToggleEducate")
    session.type("i\"")
    session.key("<Esc>")
    session.ex("ToggleEducate")
    session.type("a\"")

    assertState(session, "“\"|")
  }

  @Test
  fun `test global variable turns educate on`() {
    val session = Session("")
    session.script("let g:textobj#quote#educate = 1")

    session.type("i\"a\"")

    assertState(session, "“a”|")
  }

  @Test
  fun `test educate with custom quotes`() {
    val session = Session("")
    session.script("let g:textobj#quote#doubleDefault = '„“'")
    session.ex("Educate")

    session.type("i\"a\"")

    assertState(session, "„a“|")
  }

  @Test
  fun `test educate with multiple carets`() {
    val session = Session("ab\n c")
    session.ex("Educate")
    // On `b`, and on `c`. The first follows a letter, the second a space.
    session.caretsAt(1, 4)

    session.type("i\"")

    assertCarets(session, "a”|b\n “|c")
  }

  @Test
  fun `test educate with multiple carets at line start`() {
    val session = Session("a\nb")
    session.ex("Educate")
    session.caretsAt(0, 2)

    session.type("i\"")

    assertCarets(session, "“|a\n“|b")
  }

  @Test
  fun `test educate in Replace mode`() {
    val session = Session("abc")
    session.ex("Educate")

    session.type("R\"")

    assertState(session, "“|bc")
  }

  @Test
  fun `test CTRL-V inserts a straight double quote while educating`() {
    val session = Session("x")
    session.ex("Educate")
    session.type("i")
    session.key("<C-V>")
    session.type("\"")

    assertState(session, "\"|x")
  }

  @Test
  fun `test repeat insert with educated quotes`() {
    val session = Session("\nx")
    session.ex("Educate")

    session.type("i\"a\"")
    session.key("<Esc>")
    session.type("j0.")

    assertState(session, "“a”\n“a|”x")
  }

  /**
   * `:Educate` is an alias, and disabling the extension removes mappings by owner. The command has
   * to go with them, or it keeps arming a buffer after `set notextobj-quote`.
   */
  @Test
  fun `test turning the extension off removes Educate`() {
    val session = Session("", enableNow = true)
    injector.extensionLoader.disableExtension("textobj-quote")

    session.ex("Educate")
    session.enable()
    session.type("i\"")

    assertState(session, "\"|")
  }
}
