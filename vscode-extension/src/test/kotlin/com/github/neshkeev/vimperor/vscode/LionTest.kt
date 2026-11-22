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
 * `vim-lion`: `gl` and `gL` align a motion or a Visual selection to a character.
 *
 * IdeaVim's two suites, played here. A `|` in an expected string is the caret.
 */
class LionTest {

  private class Session(text: String, enableNow: Boolean = true) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      if (enableNow) enable()
    }

    fun enable() {
      injector.extensionLoader.enableExtension(
        ExtensionBean("lion", VsCodeExtensions.PLUGIN_ID, "init", ""),
      )
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)

    /** Plain text is typed. A part that starts with `<` is a Vim key name, such as `<CR>`. */
    fun play(vararg parts: String) {
      for (part in parts) {
        if (part.startsWith("<")) key(part) else type(part)
      }
    }

    fun script(line: String) =
      injector.vimscriptExecutor.execute(line, host.editorFor(fake), VsCodeExecutionContext, skipHistory = true)

    fun move(offset: Int) = host.editorFor(fake).primaryCaret().moveToOffset(offset)

    val content: String get() = fake.document.content
    val caret: Int get() = host.editorFor(fake).primaryCaret().offset
  }

  private fun assertState(session: Session, marked: String) {
    val caret = marked.indexOf('|')
    check(caret >= 0) { marked }
    assertEquals(marked.removeRange(caret, caret + 1), session.content)
    assertEquals(caret, session.caret)
  }

  @Test
  fun `test align paragraph by equals sign`() {
    val session = Session("a = 1\nusername = 'tommcdo'\nstuff = 3")

    session.type("glip=")

    assertState(session, "|a        = 1\nusername = 'tommcdo'\nstuff    = 3")
  }

  @Test
  fun `test align when last line has the rightmost char`() {
    val session = Session("a = 1\nbb = 2\n\nccc = 3")

    session.type("glip=")

    assertState(session, "|a  = 1\nbb = 2\n\nccc = 3")
  }

  @Test
  fun `test align all occurrences of the char`() {
    val session = Session("a = 1 = x\nbb = 22 = y\nc = 3")

    session.type("glip=")

    assertState(session, "|a  = 1  = x\nbb = 22 = y\nc  = 3")
  }

  @Test
  fun `test align later occurrences when earlier ones are already aligned`() {
    val session = Session("a = 1 = x\nb = 22 = y")

    session.type("glip=")

    assertState(session, "|a = 1  = x\nb = 22 = y")
  }

  @Test
  fun `test count limits number of aligned occurrences`() {
    val session = Session("a = 1 = x = p = z\nbb = 22 = yy = qq = w\n\nccc = 3")

    session.type("3glip=")

    assertState(session, "|a  = 1  = x  = p = z\nbb = 22 = yy = qq = w\n\nccc = 3")
  }

  @Test
  fun `test gL aligns text after the char`() {
    val session = Session("a: 1\nbbb: 2")

    session.type("gLip:")

    assertState(session, "|a:   1\nbbb: 2")
  }

  @Test
  fun `test gL aligns text after the char when char is preceded by space`() {
    val session = Session("a = 1\nbb = 22")

    session.type("gLip=")

    assertState(session, "|a =  1\nbb = 22")
  }

  @Test
  fun `test gL aligns text directly after the char`() {
    val session = Session("a:1\nbbb:2")

    session.type("gLip:")

    assertState(session, "|a:  1\nbbb:2")
  }

  @Test
  fun `test dot repeats alignment with the same char`() {
    val session = Session("a = 1\nbb = 2\n\nccc = 3\nd = 4")

    session.play("glip=", "}j.")

    assertState(session, "a  = 1\nbb = 2\n\n|ccc = 3\nd   = 4")
  }

  @Test
  fun `test align linewise visual selection`() {
    val session = Session("a = 1\nbb = 2\nccc = 3")

    session.type("Vjgl=")

    assertState(session, "a  = 1\n|bb = 2\nccc = 3")
  }

  @Test
  fun `test align linewise visual selection ending on last line of file`() {
    val session = Session("a = 1\nbb = 2")

    session.type("Vjgl=")

    assertState(session, "a  = 1\n|bb = 2")
  }

  @Test
  fun `test gL in linewise visual selection`() {
    val session = Session("a: 1\nbbb: 2")

    session.type("VjgL:")

    assertState(session, "a:   1\n|bbb: 2")
  }

  @Test
  fun `test dot repeats visual alignment on the same number of lines from cursor`() {
    val session = Session("a = 1\nbb = 2\n\nccc = 3\nd = 4")

    session.play("Vjgl=", "jj.")

    assertState(session, "a  = 1\nbb = 2\n\n|ccc = 3\nd   = 4")
  }

  @Test
  fun `test align skips lines without the char`() {
    val session = Session("a = 1\nno equals here\nbb = 2")

    session.type("glip=")

    assertState(session, "|a  = 1\nno equals here\nbb = 2")
  }

  @Test
  fun `test operator moves caret to start of the aligned text`() {
    val session = Session("a = 1\nbb = 2")
    session.move("a = 1\nbb ".length)

    session.type("glip=")

    assertState(session, "|a  = 1\nbb = 2")
  }

  @Test
  fun `test space aligns the first space after a word`() {
    val session = Session("a  b\nccc d")

    session.play("glip", "<Space>")

    assertState(session, "|a    b\nccc d")
  }

  @Test
  fun `test gL with space aligns the following word`() {
    val session = Session("a  b\nccc d")

    session.play("gLip", "<Space>")

    assertState(session, "|a   b\nccc d")
  }

  @Test
  fun `test number before char skips leading occurrences`() {
    val session = Session("a,b,c\naa,bb,c")

    session.type("glip2,")

    assertState(session, "|a,b  ,c\naa,bb,c")
  }

  @Test
  fun `test count with number before char aligns only that occurrence`() {
    val session = Session("a,b,c,d\naa,bb,cc,d")

    session.type("1glip2,")

    assertState(session, "|a,b  ,c,d\naa,bb,cc,d")
  }

  @Test
  fun `test number followed by enter aligns to the number`() {
    val session = Session("x2\nyyy2")

    session.play("glip2", "<CR>")

    assertState(session, "|x  2\nyyy2")
  }

  @Test
  fun `test slash aligns to a pattern`() {
    val session = Session("a -> 1\nbbb -> 2")

    session.play("glip/", "->", "<CR>")

    assertState(session, "|a   -> 1\nbbb -> 2")
  }

  @Test
  fun `test slash aligns to a regular expression`() {
    val session = Session("a: 1\nbbb = 2")

    session.play("glip/", "[:=]", "<CR>")

    assertState(session, "|a   : 1\nbbb = 2")
  }

  @Test
  fun `test slash with empty pattern aligns to slash`() {
    val session = Session("a/b\nccc/d")

    session.play("glip/", "<CR>")

    assertState(session, "|a  /b\nccc/d")
  }

  @Test
  fun `test invalid pattern leaves text unchanged`() {
    val session = Session("a(1\nbbb(2")

    session.play("glip/", "\\(", "<CR>")

    assertState(session, "|a(1\nbbb(2")
  }

  @Test
  fun `test escape instead of char leaves text unchanged`() {
    val session = Session("a = 1\nbbb = 2")

    session.play("glip", "<Esc>")

    assertState(session, "|a = 1\nbbb = 2")
  }

  @Test
  fun `test squeeze spaces before aligning keeps indent`() {
    val session = Session("    a     = 1\n    bb = 2")
    session.script("let g:lion_squeeze_spaces = 1")

    session.type("glip=")

    assertState(session, "|    a  = 1\n    bb = 2")
  }

  @Test
  fun `test buffer squeeze spaces variable overrides global one`() {
    val session = Session("a     = 1\nbb = 2")
    session.script("let g:lion_squeeze_spaces = 1")
    session.script("let b:lion_squeeze_spaces = 0")

    session.type("glip=")

    assertState(session, "|a     = 1\nbb    = 2")
  }

  @Test
  fun `test tabs count as their screen width`() {
    val session = Session("a\t= 1\nbbbbbb = 2")
    session.script("set tabstop=4")

    session.type("glip=")

    assertState(session, "|a\t   = 1\nbbbbbb = 2")
  }

  @Test
  fun `test characterwise visual selection limits the first and last line`() {
    val session = Session("x = 1 = 2\nyy = 3 = 4")
    session.move("x = ".length)

    session.type("vjgl=")

    assertState(session, "x = 1 = 2\nyy  |  = 3 = 4")
  }

  @Test
  fun `test blockwise visual selection limits all lines to the block columns`() {
    val session = Session("a = 1 = x\nbb = 2 = y\nc = 3 = z")
    session.move("a = ".length)

    session.play("<C-V>", "2j4lgl=")

    assertState(session, "a = 1  = x\nbb = 2 = y\nc = 3  =| z")
  }

  @Test
  fun `test dot repeat keeps the count`() {
    val session = Session("a = 1 = x = p = z\nbb = 22 = yy = qq = w\n\na = 1 = x = p = z\nbb = 22 = yy = qq = w")

    session.play("3glip=", "}j.")

    assertState(session, "a  = 1  = x  = p = z\nbb = 22 = yy = qq = w\n\n|a  = 1  = x  = p = z\nbb = 22 = yy = qq = w")
  }

  @Test
  fun `test custom right align mapping`() {
    val session = Session("a = 1\nbb = 2", enableNow = false)
    session.script("let g:lion_map_right = 'ga'")
    session.enable()

    session.type("gaip=")

    assertState(session, "|a  = 1\nbb = 2")
  }

  @Test
  fun `test custom left align mapping`() {
    val session = Session("a: 1\nbbb: 2", enableNow = false)
    session.script("let g:lion_map_left = 'gA'")
    session.enable()

    session.type("gAip:")

    assertState(session, "|a:   1\nbbb: 2")
  }

  @Test
  fun `test plug mappings work without default mappings`() {
    val session = Session("a = 1\nbb = 2\n\nc = 3\nddd = 4", enableNow = false)
    session.script("let g:lion_create_maps = 0")
    session.enable()
    session.script("nmap ga <Plug>LionRight")
    session.script("xmap ga <Plug>VLionRight")

    session.play("gaip=", "3j", "Vjga=")

    assertState(session, "a  = 1\nbb = 2\n\nc   = 3\n|ddd = 4")
  }
}
