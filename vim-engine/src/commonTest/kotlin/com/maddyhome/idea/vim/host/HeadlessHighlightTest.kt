/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.highlight.HighlightAttributes
import com.maddyhome.idea.vim.highlight.Highlights
import com.maddyhome.idea.vim.highlight.UnderlineStyle
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `:highlight` - what a group looks like, said by the user rather than guessed at by the host.
 *
 * The test that matters most is the one about *absence*. Before this command existed, a host
 * turned a group name into a colour by looking it up in the theme, and that has to keep working
 * for every group nobody defines - which is nearly all of them. So the first thing checked is that
 * an untouched `Search` still resolves to null and reaches the host as a name to interpret, and
 * that only a group somebody actually wrote about carries colours with it.
 *
 * The second is the difference between untouched and *disabled*. `:hi Search NONE` is not a way of
 * putting `Search` back to the theme's colours - Vim is explicit that it is not - and a design that
 * stored only attributes would collapse the two. They are checked apart here because that is
 * exactly the distinction the storage exists to keep.
 */
class HeadlessHighlightTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one two one three", listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val panel: HeadlessOutputPanelService get() = injector.outputPanel as HeadlessOutputPanelService
    val painted: HeadlessMatchHighlighter get() = injector.matchHighlighter as HeadlessMatchHighlighter

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

  // ---- the two absences ------------------------------------------------------------------------

  @Test
  fun `test a group nobody defined resolves to nothing so the host uses its theme`() {
    session()

    assertNull(Highlights.group("Search").attributes, "an undefined group must stay the host's to decide")
    assertEquals("Search", Highlights.group("Search").name)
  }

  @Test
  fun `test a disabled group is not the same as an undefined one`() {
    val s = session()
    s.run("highlight Search NONE")

    val resolved = Highlights.group("Search").attributes
    assertNotNull(resolved, "a disabled group is defined, and defined as painting nothing")
    assertTrue(resolved.paintsNothing)
  }

  @Test
  fun `test highlight clear on one group disables it and clear on its own forgets everything`() {
    val s = session()
    s.run("highlight Todo guibg=Red")
    s.run("highlight clear Todo")
    assertTrue(Highlights.group("Todo").attributes?.paintsNothing == true)

    s.run("highlight clear")
    assertNull(Highlights.group("Todo").attributes, "clear with no group puts the host back in charge")
  }

  // ---- defining --------------------------------------------------------------------------------

  @Test
  fun `test a gui colour name becomes the hex Vim would have used`() {
    val s = session()
    s.run("highlight Todo guifg=DarkCyan guibg=#503030")

    val resolved = Highlights.group("Todo").attributes
    assertEquals("#008b8b", resolved?.foreground)
    assertEquals("#503030", resolved?.background)
  }

  /**
   * The surprising half of Vim's colour naming, and the reason it is worth a test of its own.
   *
   * `ctermfg=Blue` is not colour 4. Vim numbers its colour names after the MS-Windows console,
   * where `Blue` is the bright one, so on an ansi terminal it lands on 12 - and 12 in the xterm
   * palette is `#5c5cff`, not `#0000ee`. Getting this wrong would be invisible in review and
   * obvious on screen.
   */
  @Test
  fun `test a cterm colour name is the terminal index Vim means by it`() {
    val s = session()
    s.run("highlight Todo ctermfg=Blue ctermbg=DarkBlue")

    val resolved = Highlights.group("Todo").attributes
    assertEquals("#5c5cff", resolved?.foreground, "ctermfg=Blue is index 12, the bright one")
    assertEquals("#0000ee", resolved?.background, "ctermbg=DarkBlue is index 4")
  }

  @Test
  fun `test a cterm number is read out of the xterm palette`() {
    val s = session()
    // 196 is the top-right corner of the 6x6x6 cube and 235 is the fourth of the greys, which are
    // the two ends of the range a colour scheme actually picks from.
    s.run("highlight Todo ctermfg=196 ctermbg=235")

    val resolved = Highlights.group("Todo").attributes
    assertEquals("#ff0000", resolved?.foreground)
    assertEquals("#262626", resolved?.background)
  }

  @Test
  fun `test gui colours win over cterm ones because both hosts draw a real colour`() {
    val s = session()
    s.run("highlight Todo ctermfg=White guifg=DarkRed")

    assertEquals("#8b0000", Highlights.group("Todo").attributes?.foreground)
  }

  @Test
  fun `test cterm is used when there is no gui, which Vim would ignore and a config relies on`() {
    val s = session()
    s.run("highlight Todo cterm=bold,underline ctermfg=Red")

    val resolved = Highlights.group("Todo").attributes
    assertTrue(resolved?.bold == true)
    assertEquals(UnderlineStyle.STRAIGHT, resolved?.underline)
    assertEquals("#ff0000", resolved?.foreground)
  }

  @Test
  fun `test each of Vims underlines arrives as its own style`() {
    val s = session()
    for ((argument, expected) in listOf(
      "underline" to UnderlineStyle.STRAIGHT,
      "undercurl" to UnderlineStyle.CURL,
      "underdouble" to UnderlineStyle.DOUBLE,
      "underdotted" to UnderlineStyle.DOTTED,
      "underdashed" to UnderlineStyle.DASHED,
    )) {
      s.run("highlight clear")
      s.run("highlight Todo gui=$argument")
      assertEquals(expected, Highlights.group("Todo").attributes?.underline, argument)
    }
  }

  @Test
  fun `test the attributes that are not colours all arrive`() {
    val s = session()
    s.run("highlight Todo gui=bold,italic,reverse,strikethrough,standout")

    val resolved = Highlights.group("Todo").attributes
    assertEquals(
      HighlightAttributes(
        bold = true,
        italic = true,
        reverse = true,
        strikethrough = true,
        standout = true,
      ),
      resolved,
    )
  }

  /** Vim: "all settings that are not included remain the same, only the specified field is used". */
  @Test
  fun `test a second highlight merges into the first rather than replacing it`() {
    val s = session()
    s.run("highlight Todo gui=bold")
    s.run("highlight Todo guifg=Red")

    val resolved = Highlights.group("Todo").attributes
    assertTrue(resolved?.bold == true, "the bold from the first command is still there")
    assertEquals("#ff0000", resolved?.foreground)
  }

  @Test
  fun `test NONE as a value removes one setting and leaves the rest`() {
    val s = session()
    s.run("highlight Todo gui=bold guifg=Red")
    s.run("highlight Todo guifg=NONE")

    val resolved = Highlights.group("Todo").attributes
    assertTrue(resolved?.bold == true)
    assertNull(resolved?.foreground)
  }

  @Test
  fun `test fg and bg mean the editors own colour, which is to leave it alone`() {
    val s = session()
    s.run("highlight Todo guifg=bg guibg=fg")

    val resolved = Highlights.group("Todo").attributes
    assertNotNull(resolved, "the group was defined even though neither colour could be resolved")
    assertNull(resolved.foreground)
    assertNull(resolved.background)
  }

  @Test
  fun `test the group name is case insensitive the way Vims is`() {
    val s = session()
    s.run("highlight Todo guifg=Red")

    assertEquals("#ff0000", Highlights.group("TODO").attributes?.foreground)
    assertEquals("#ff0000", Highlights.group("todo").attributes?.foreground)
  }

  // ---- linking ---------------------------------------------------------------------------------

  @Test
  fun `test a link resolves to what it points at`() {
    val s = session()
    s.run("highlight Todo guibg=DarkRed")
    s.run("highlight link myGroup Todo")

    assertEquals("#8b0000", Highlights.group("myGroup").attributes?.background)
  }

  @Test
  fun `test linking a group that has settings is refused unless forced`() {
    val s = session()
    s.run("highlight Todo guibg=Red")
    s.run("highlight Other guibg=Blue")
    s.run("highlight link Other Todo")

    assertEquals("E414: group has settings, highlight link ignored", s.messages.lastError)
    assertEquals("#0000ff", Highlights.group("Other").attributes?.background, "the settings stayed")

    s.run("highlight! link Other Todo")
    assertEquals("#ff0000", Highlights.group("Other").attributes?.background, "the bang went through")
  }

  /** How every syntax file in the world states a colour without overruling the user's vimrc. */
  @Test
  fun `test a default link is ignored without complaint when the group is already spoken for`() {
    val s = session()
    s.run("highlight Other guibg=Blue")
    s.run("highlight default link Other Todo")

    assertNull(s.messages.lastError, "a refused default link is the normal case, not a problem")
    assertEquals("#0000ff", Highlights.group("Other").attributes?.background)
  }

  @Test
  fun `test highlight default does not overrule a definition that is already there`() {
    val s = session()
    s.run("highlight Todo guifg=Red")
    s.run("highlight default Todo guifg=Blue")

    assertEquals("#ff0000", Highlights.group("Todo").attributes?.foreground)
  }

  @Test
  fun `test link to NONE takes the link off`() {
    val s = session()
    s.run("highlight Todo guibg=Red")
    s.run("highlight link myGroup Todo")
    s.run("highlight link myGroup NONE")

    assertTrue(Highlights.group("myGroup").attributes?.paintsNothing == true)
  }

  /**
   * A link that eventually points at itself paints nothing rather than hanging the editor.
   *
   * Vim refuses to build one; doing that here would mean walking the chain on every `:hi link`,
   * and this loop is only ever reached by a config that asked for it.
   */
  @Test
  fun `test a circular link stops instead of recurring forever`() {
    val s = session()
    s.run("highlight link a b")
    s.run("highlight link b a")

    assertTrue(Highlights.group("a").attributes?.paintsNothing == true)
  }

  // ---- listing ---------------------------------------------------------------------------------

  @Test
  fun `test listing one group prints back what was set`() {
    val s = session()
    s.run("highlight Todo term=bold ctermfg=4 guifg=Blue")
    s.run("highlight Todo")

    assertEquals(listOf("Todo           xxx term=bold ctermfg=4 guifg=Blue\n"), s.panel.lines)
  }

  @Test
  fun `test listing a linked group says what it links to`() {
    val s = session()
    s.run("highlight link myGroup Todo")
    s.run("highlight myGroup")

    assertEquals(listOf("myGroup        xxx links to Todo\n"), s.panel.lines)
  }

  @Test
  fun `test listing a group nobody mentioned is E411`() {
    val s = session()
    s.run("highlight NoSuchGroup")

    assertEquals("E411: highlight group not found: NoSuchGroup", s.messages.lastError)
  }

  @Test
  fun `test listing everything covers every group that was mentioned`() {
    val s = session()
    s.run("highlight Todo guifg=Red")
    s.run("highlight link myGroup Todo")
    s.run("highlight")

    val text = s.panel.lines.joinToString("")
    assertTrue("Todo" in text && "myGroup" in text, "both groups should be listed, got: $text")
  }

  // ---- errors ----------------------------------------------------------------------------------

  @Test
  fun `test an argument with no equal sign is E416`() {
    val s = session()
    s.run("highlight Todo bold")

    assertEquals("E416: missing equal sign: bold", s.messages.lastError)
  }

  @Test
  fun `test a key nobody knows is E423`() {
    val s = session()
    s.run("highlight Todo sparkle=yes")

    assertEquals("E423: Illegal argument: sparkle=yes", s.messages.lastError)
  }

  @Test
  fun `test a key with nothing after it is E417`() {
    val s = session()
    s.run("highlight Todo guifg=")

    assertEquals("E417: missing argument: guifg=", s.messages.lastError)
  }

  @Test
  fun `test an attribute Vim does not have is E418`() {
    val s = session()
    s.run("highlight Todo gui=sparkly")

    assertEquals("E418: Illegal value: sparkly", s.messages.lastError)
  }

  @Test
  fun `test a colour nobody recognises is E421`() {
    val s = session()
    s.run("highlight Todo guifg=NotAColour")

    assertEquals("E421: Color name or number not recognized: NotAColour", s.messages.lastError)
  }

  @Test
  fun `test a cterm number past the end of the palette is E421`() {
    val s = session()
    s.run("highlight Todo ctermfg=300")

    assertEquals("E421: Color name or number not recognized: 300", s.messages.lastError)
  }

  @Test
  fun `test a link missing its target is E412`() {
    val s = session()
    s.run("highlight link myGroup")

    assertEquals("""E412: Not enough arguments: ":highlight link {from} {to}"""", s.messages.lastError)
  }

  @Test
  fun `test a link with a third argument is E413`() {
    val s = session()
    s.run("highlight link a b c")

    assertEquals("""E413: Too many arguments: ":highlight link"""", s.messages.lastError)
  }

  @Test
  fun `test an equal sign in the group name is E415`() {
    val s = session()
    s.run("highlight Todo=bold")

    assertEquals("E415: unexpected equal sign: Todo=bold", s.messages.lastError)
  }

  // ---- what it is all for ----------------------------------------------------------------------

  /**
   * The reason `:highlight` was written: `:match` had no way to be told what a colour should be.
   *
   * The group reaches the host resolved, so a definition made *after* the `:match` still arrives -
   * which needs the repaint at the end of the command, and is the one moment a standing highlight
   * changes appearance with nobody touching a key.
   */
  @Test
  fun `test defining a group repaints the match that is already showing`() {
    val s = session()
    s.run("match Todo /one/")
    assertNull(s.painted.shown[1]?.first?.attributes, "nothing defined yet, so the host decides")

    s.run("highlight Todo guibg=#503030")
    assertEquals("#503030", s.painted.shown[1]?.first?.attributes?.background)
  }

  @Test
  fun `test a match on a group that was never defined still carries only the name`() {
    val s = session()
    s.run("highlight Todo guibg=Red")
    s.run("match Search /one/")

    assertEquals("Search", s.painted.shown[1]?.first?.name)
    assertNull(s.painted.shown[1]?.first?.attributes, "defining Todo says nothing about Search")
  }
}
