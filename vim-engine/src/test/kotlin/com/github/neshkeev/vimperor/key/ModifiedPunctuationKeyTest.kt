/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.key

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.host.HeadlessExecutionContext
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.host.HeadlessMessages
import com.maddyhome.idea.vim.host.HeadlessOutputPanelService
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.key.VimKeyCodes
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Modified punctuation is a key, not the character's code.
 *
 * `'` is 39 and [VimKeyCodes.VK_RIGHT] is 39, `` ` `` is 96 and [VimKeyCodes.VK_NUMPAD0] is 96, and `%` is 37 and
 * [VimKeyCodes.VK_LEFT] is 37. A mapping of `<A-'>` stored as Alt plus that code is a mapping of Alt+Right. Quote and
 * backquote have their own key codes; punctuation that only a shifted key types has none, and stays a typed character.
 */
class ModifiedPunctuationKeyTest {

  private fun parse(notation: String): VimKeyStroke {
    injector = HeadlessInjector()
    return injector.parser.parseKeys(notation).single()
  }

  private fun notation(notation: String): String {
    injector = HeadlessInjector()
    return injector.parser.toKeyNotation(injector.parser.parseKeys(notation))
  }

  @Test
  fun `test alt quote is the quote key and not alt right`() {
    val quote = parse("<A-'>")

    assertEquals(VimKeyCodes.CHAR_UNDEFINED, quote.keyChar)
    assertEquals(VimKeyCodes.VK_QUOTE, quote.keyCode)
    assertEquals(VimKeyStroke.getKeyStroke(VimKeyCodes.VK_QUOTE, VimKeyCodes.ALT_DOWN_MASK), quote)
    assertNotEquals(parse("<A-Right>"), quote)
  }

  @Test
  fun `test other modified unshifted punctuation is its own key`() {
    assertEquals(VimKeyStroke.getKeyStroke(VimKeyCodes.VK_QUOTE, VimKeyCodes.CTRL_DOWN_MASK), parse("<C-'>"))
    assertEquals(VimKeyStroke.getKeyStroke(VimKeyCodes.VK_BACK_QUOTE, VimKeyCodes.ALT_DOWN_MASK), parse("<A-`>"))
    assertEquals(VimKeyStroke.getKeyStroke(VimKeyCodes.VK_BACK_QUOTE, VimKeyCodes.META_DOWN_MASK), parse("<D-`>"))
    assertEquals(VimKeyStroke.getKeyStroke(VimKeyCodes.VK_SEMICOLON, VimKeyCodes.ALT_DOWN_MASK), parse("<A-;>"))
    assertEquals(VimKeyStroke.getKeyStroke(VimKeyCodes.VK_SLASH, VimKeyCodes.CTRL_DOWN_MASK), parse("<C-/>"))
    assertNotEquals(parse("<A-k0>"), parse("<A-`>"))
  }

  @Test
  fun `test a letter or digit with a modifier stays the uppercase character code`() {
    val five = parse("<A-S-5>")

    assertEquals(VimKeyCodes.CHAR_UNDEFINED, five.keyChar)
    assertEquals('5'.code, five.keyCode)
    assertEquals(
      VimKeyStroke.getKeyStroke('5'.code, VimKeyCodes.ALT_DOWN_MASK or VimKeyCodes.SHIFT_DOWN_MASK),
      five,
    )
    assertEquals(VimKeyStroke.getKeyStroke('A'.code, VimKeyCodes.ALT_DOWN_MASK), parse("<A-a>"))
  }

  @Test
  fun `test control of a character in the control range stays that character code`() {
    assertEquals(VimKeyStroke.getKeyStroke('^'.code, VimKeyCodes.CTRL_DOWN_MASK), parse("<C-^>"))
    assertEquals(VimKeyStroke.getKeyStroke('@'.code, VimKeyCodes.CTRL_DOWN_MASK), parse("<C-@>"))
    assertEquals(VimKeyStroke.getKeyStroke('_'.code, VimKeyCodes.CTRL_DOWN_MASK), parse("<C-_>"))
    assertEquals(VimKeyStroke.getKeyStroke('?'.code, VimKeyCodes.CTRL_DOWN_MASK), parse("<C-?>"))
    assertEquals(VimKeyStroke.getKeyStroke(VimKeyCodes.VK_OPEN_BRACKET, VimKeyCodes.CTRL_DOWN_MASK), parse("<C-[>"))
  }

  @Test
  fun `test shifted punctuation with a modifier stays a typed character`() {
    val percent = parse("<A-%>")

    assertEquals('%', percent.keyChar)
    assertEquals(VimKeyCodes.VK_UNDEFINED, percent.keyCode)
    assertEquals(VimKeyStroke.getKeyStroke('%', VimKeyCodes.ALT_DOWN_MASK), percent)
    assertNotEquals(parse("<A-Left>"), percent)
    assertEquals(VimKeyStroke.getKeyStroke('&', VimKeyCodes.ALT_DOWN_MASK), parse("<A-&>"))
    assertEquals(VimKeyStroke.getKeyStroke('{', VimKeyCodes.ALT_DOWN_MASK), parse("<A-{>"))
    assertEquals(VimKeyStroke.getKeyStroke('?', VimKeyCodes.ALT_DOWN_MASK), parse("<A-?>"))
    assertEquals(VimKeyStroke.getKeyStroke('|', VimKeyCodes.ALT_DOWN_MASK), parse("<A-Bar>"))
  }

  @Test
  fun `test notation round trips the key rather than the colliding name`() {
    assertEquals("<A-'>", notation("<A-'>"))
    assertEquals("<C-`>", notation("<C-`>"))
    assertEquals("<A-%>", notation("<A-%>"))
    assertEquals("<A-&>", notation("<A-&>"))
    assertEquals("<A-{>", notation("<A-{>"))
    assertEquals("<A-?>", notation("<A-?>"))

    injector = HeadlessInjector()
    val bar = injector.parser.parseKeys("<A-Bar>")
    assertEquals("<A-|>", injector.parser.toKeyNotation(bar))
    assertEquals(bar, injector.parser.parseKeys(injector.parser.toKeyNotation(bar)))
  }

  @Test
  fun `test a pressed quote or backquote is named by the character it types`() {
    injector = HeadlessInjector()
    val parser = injector.parser
    val quote = VimKeyStroke.getKeyStroke(VimKeyCodes.VK_QUOTE, 0)
    val backquote = VimKeyStroke.getKeyStroke(VimKeyCodes.VK_BACK_QUOTE, 0)

    assertEquals("<'>", parser.toKeyNotation(quote))
    assertEquals("<`>", parser.toKeyNotation(backquote))
    assertEquals("'", parser.toPrintableString(listOf(quote)))
    assertEquals("`", parser.toPrintableString(listOf(backquote)))
    assertFalse(parser.toKeyNotation(quote).contains(VimKeyCodes.VK_QUOTE.toChar()))
    assertFalse(parser.toKeyNotation(backquote).contains(VimKeyCodes.VK_BACK_QUOTE.toChar()))
  }

  @Test
  fun `test a typed character prints bare only when ctrl alt and meta are absent`() {
    assertEquals("%", notation("%"))
    assertEquals("%", notation("<S-%>"))
    assertEquals("<A-%>", notation("<A-%>"))
    assertEquals("A", notation("<S-a>"))
  }

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("foo bar", listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages

    fun run(line: String): ExecutionResult =
      injector.vimscriptExecutor.execute(
        line,
        editor,
        HeadlessExecutionContext,
        skipHistory = true,
        indicateErrors = true,
        CommandLineVimLContext,
      )
  }

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session()
  }

  @Test
  fun `test nmap of alt percent is an error and creates no mapping`() {
    val s = session()

    assertEquals(ExecutionResult.Error, s.run("nmap <A-%> w"))
    assertEquals(
      "Cannot map <A-%>: no key types this character, map the key itself, e.g. <A-S-5> instead of <A-%>",
      s.messages.lastError,
    )
    assertNull(injector.keyGroup.getKeyMapping(MappingMode.NORMAL)[injector.parser.parseKeys("<A-%>")])
  }

  @Test
  fun `test nmap of alt quote is not triggered by alt right`() {
    val s = session()

    assertEquals(ExecutionResult.Success, s.run("nmap <A-'> w"))
    val quote = injector.parser.parseKeys("<A-'>")
    val mapping = injector.keyGroup.getKeyMapping(MappingMode.NORMAL)[quote]
    assertNotNull(mapping)
    assertEquals(quote, mapping.fromKeys)
    assertNull(injector.keyGroup.getKeyMapping(MappingMode.NORMAL)[injector.parser.parseKeys("<A-Right>")])

    s.run("nmap")
    val listed = (injector.outputPanel as HeadlessOutputPanelService).lines.joinToString("\n")
    assertTrue("<A-'>" in listed, listed)
    assertFalse("Right" in listed, listed)
  }

  @Test
  fun `test nmap of alt backquote is not the keypad zero key`() {
    val s = session()

    assertEquals(ExecutionResult.Success, s.run("nmap <A-`> w"))
    assertNotNull(injector.keyGroup.getKeyMapping(MappingMode.NORMAL)[injector.parser.parseKeys("<A-`>")])
    assertNull(injector.keyGroup.getKeyMapping(MappingMode.NORMAL)[injector.parser.parseKeys("<A-k0>")])
  }

  @Test
  fun `test nmap of a shifted digit key and of control caret is accepted`() {
    val s = session()

    assertEquals(ExecutionResult.Success, s.run("nmap <A-S-5> w"))
    assertEquals(ExecutionResult.Success, s.run("nmap <C-^> w"))
    assertNotNull(injector.keyGroup.getKeyMapping(MappingMode.NORMAL)[injector.parser.parseKeys("<A-S-5>")])
    assertNotNull(injector.keyGroup.getKeyMapping(MappingMode.NORMAL)[injector.parser.parseKeys("<C-^>")])
  }
}
