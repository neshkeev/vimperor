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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `'autoindent'`, and the indent Vim takes back when nothing was typed on the line.
 *
 * Found by reading IdeaVim's commits after this fork's snapshot, and confirmed here before a line was
 * ported: `o<Esc>` on an indented line left the new line holding its indent and nothing else - four
 * spaces of trailing whitespace per press - where Vim leaves it empty. Vim remembers that the indent
 * was put there for the user (`did_ai`) and deletes it on the way out of Insert mode unless something
 * was typed. IdeaVim ported that in VIM-2478, with the option that turns the indent off altogether.
 *
 * The indent itself is this host's: `insertNewLineAtCarets` copies the line above. So these run in
 * the host, keystroke by keystroke, rather than against the engine alone.
 */
class AutoIndentOptionTest {

  private val function = "fun main() {\n    println(\"hi\")\n}"

  /**
   * Types [keys] into [text], one keystroke at a time as a window would, and returns the text.
   *
   * `u` is VS Code's command rather than the engine's, so it is answered from the fake's own undo
   * stack - without that it goes nowhere, and an undo test would be testing the harness.
   */
  private fun run(text: String, keys: String): String {
    val fake = FakeEditor(text, path = "/test/autoindent-option.txt")
    val host = VimHost(runCommand = { command, _, onDone ->
      when (command) {
        VsCodeCommands.UNDO -> fake.undo()
        VsCodeCommands.REDO -> fake.redo()
      }
      onDone(true)
    }).also { it.start() }
    KeyHandler.getInstance().fullReset(host.editorFor(fake))
    UndoStops.reset()
    injector.parser.parseKeys(keys).forEach { host.handle(fake, listOf(it)) }
    return fake.document.content
  }

  // ---- the indent comes back out when nothing was typed ------------------------------------------

  @Test
  fun `test o and escape leaves an empty line`() {
    assertEquals("fun main() {\n    println(\"hi\")\n\n}", run(function, "jo<Esc>"))
  }

  @Test
  fun `test O and escape leaves an empty line`() {
    assertEquals("fun main() {\n\n    println(\"hi\")\n}", run(function, "jO<Esc>"))
  }

  @Test
  fun `test enter at the end of a line and escape leaves an empty line`() {
    assertEquals("fun main() {\n    println(\"hi\")\n\n}", run(function, "jA<CR><Esc>"))
  }

  @Test
  fun `test a tab indent is taken back too`() {
    assertEquals("\tfoo\n", run("\tfoo", "o<Esc>"))
  }

  @Test
  fun `test cc and escape leaves an empty line`() {
    assertEquals("fun main() {\n\n}", run(function, "jcc<Esc>"))
  }

  @Test
  fun `test S and escape leaves an empty line`() {
    assertEquals("fun main() {\n\n}", run(function, "jS<Esc>"))
  }

  @Test
  fun `test cc with a count and escape leaves an empty line`() {
    assertEquals("fun main() {\n\n}", run("fun main() {\n    println(\"hi\")\n    println(\"there\")\n}", "j2cc<Esc>"))
  }

  /** `.` repeats the `o` and the escape, so the repeated line comes out empty as well. */
  @Test
  fun `test repeating o and escape with dot leaves an empty line too`() {
    assertEquals("    a\n\n    b\n", run("    a\n    b", "o<Esc>j."))
  }

  @Test
  fun `test repeating cc and escape with dot leaves an empty line too`() {
    assertEquals("\n\n    c", run("    a\n    b\n    c", "cc<Esc>j."))
  }

  // ---- an Enter while the indent is still untouched ------------------------------------------------

  /** Vim empties the line an Enter leaves, so every line a count opened comes out empty. */
  @Test
  fun `test o with a count leaves every new line empty`() {
    assertEquals("    a\n\n\n\n    b", run("    a\n    b", "3o<Esc>"))
  }

  @Test
  fun `test O with a count leaves every new line empty`() {
    assertEquals("    a\n\n\n    b", run("    a\n    b", "j2O<Esc>"))
  }

  @Test
  fun `test a second enter empties the line it leaves`() {
    assertEquals("    a\n\n", run("    a", "o<CR><Esc>"))
  }

  @Test
  fun `test enter with a count leaves every new line empty`() {
    assertEquals("    a\n\n\n", run("    a", "3A<CR><Esc>"))
  }

  /** Only the lines left behind: the line the typing happened on keeps its indent. */
  @Test
  fun `test enters and then typing keep the indent of the typed line only`() {
    assertEquals("    a\n\n\n    x", run("    a", "A<CR><CR><CR>x<Esc>"))
  }

  // ---- cc on the last line, beside lines cc has emptied ------------------------------------------

  /**
   * `.` after `cc<Esc>` on the line below: the review found it emptied the whole buffer.
   *
   * `cc` decided where to open the new line by looking at the buffer after the delete, and with the
   * line above emptied by the first `cc<Esc>` the delete left nothing at all - which read as a buffer
   * that had only ever had one line.
   */
  @Test
  fun `test dot on the last line after an emptied line keeps both lines`() {
    assertEquals("\n", run("    a\n    b", "cc<Esc>j."))
  }

  @Test
  fun `test dot on the last line keeps a trailing newline`() {
    assertEquals("\n\n", run("    a\n    b\n", "cc<Esc>j."))
  }

  /** The same thing without `.`: the line above the last is empty, and it stays where it is. */
  @Test
  fun `test cc on the last line after an empty line changes the last line`() {
    assertEquals("    a\n\n    x", run("    a\n\n    c", "Gccx<Esc>"))
  }

  /** Visual `c` over lines keeps the first line's indent, where the caret was not. */
  @Test
  fun `test visual c over lines keeps the indent of the first line`() {
    assertEquals("a\n    x", run("a\n    b\n  c", "jVjcx<Esc>"))
  }

  /** A one-line buffer, where `cc` empties everything - and used to lose the indent with it. */
  @Test
  fun `test cc on the only line keeps its indent`() {
    assertEquals("    x", run("    foo", "ccx<Esc>"))
  }

  @Test
  fun `test cc on the only line of a file ending in a newline keeps the newline`() {
    assertEquals("    x\n", run("    foo\n", "ccx<Esc>"))
  }

  // ---- the line the indent was put on -----------------------------------------------------------

  /**
   * Moving to another line before `<Esc>`: the white space there is the user's.
   *
   * The review found `o<Down><Esc>` deleted the tab on the line below. Vim would also take the
   * automatic indent back as the caret left its line; that half is not done here, and the indent
   * stays - which is what happened before this port.
   */
  @Test
  fun `test leaving the line before escape keeps the white space of the line arrived at`() {
    val lines = run("    a\n\t\nc", "o<Down><Esc>").split("\n")
    assertEquals("\t", lines[2], "the tab on the line the caret moved to is the user's")
  }

  // ---- but only an indent nobody typed ----------------------------------------------------------

  @Test
  fun `test the indent stays when text is typed`() {
    assertEquals("fun main() {\n    println(\"hi\")\n    x\n}", run(function, "jox<Esc>"))
  }

  /** Typing clears the flag, so white space the user typed is theirs. */
  @Test
  fun `test trailing white space the user typed is kept`() {
    assertEquals("fun main() {\n    foo   \n}", run(function, "jccfoo   <Esc>"))
  }

  /** Cleared by typing, not by what the line ends up holding: erasing the text does not re-arm it. */
  @Test
  fun `test the indent stays when the typed text is erased again`() {
    assertEquals("fun main() {\n    \n}", run(function, "jccfoo<BS><BS><BS><Esc>"))
  }

  /**
   * Enter in the middle of a line: the text after the split moves down with the indent in front of
   * it, and that indent is not the kind Vim takes back, because the line is not empty.
   *
   * The first port removed it. `<Esc>` steps the caret back one before the indent is looked at, which
   * put it on the last space of the indent - so the rule only asked where the white space started.
   * It asks where it ends as well now.
   */
  @Test
  fun `test enter in the middle of a line keeps the indent of the new line`() {
    assertEquals("    ab\n    cd", run("    abcd", "fci<CR><Esc>"))
  }

  /** The removal is part of the change, so one `u` puts the line back as it was. */
  @Test
  fun `test one undo restores the line after cc and escape`() {
    assertEquals(function, run(function, "jcc<Esc>u"))
  }

  // ---- 'autoindent' -----------------------------------------------------------------------------

  @Test
  fun `test autoindent is on by default`() {
    assertEquals("fun main() {\n    x\n}", run(function, "jccx<Esc>"))
  }

  @Test
  fun `test cc does not keep the indent when autoindent is off`() {
    assertEquals("fun main() {\nx\n}", run(function, ":set noautoindent<CR>jccx<Esc>"))
  }

  @Test
  fun `test S does not keep the indent when autoindent is off`() {
    assertEquals("fun main() {\nx\n}", run(function, ":set noai<CR>jSx<Esc>"))
  }

  @Test
  fun `test o does not indent the new line when autoindent is off`() {
    assertEquals("fun main() {\n    println(\"hi\")\nx\n}", run(function, ":set noautoindent<CR>jox<Esc>"))
  }

  @Test
  fun `test O does not indent the new line when autoindent is off`() {
    assertEquals("fun main() {\nx\n    println(\"hi\")\n}", run(function, ":set noautoindent<CR>jOx<Esc>"))
  }

  @Test
  fun `test enter does not copy the indent when autoindent is off`() {
    assertEquals("fun main() {\n    println(\"hi\")\nx\n}", run(function, ":set noautoindent<CR>jA<CR>x<Esc>"))
  }

  /**
   * `O` on the first line runs Enter on the line it pushes down and then moves up, so the indent to
   * remove is on the line above the one Enter left the caret on. The review found the pushed-down
   * line losing its indent instead.
   */
  @Test
  fun `test O on the first line with autoindent off leaves the line below alone`() {
    assertEquals("x\n    one", run("    one", ":set noai<CR>Ox<Esc>"))
  }

  /** `.` replays Enter without passing through the `o` action, and has to drop the indent too. */
  @Test
  fun `test dot after o with autoindent off does not indent`() {
    assertEquals("    a\nx\n    b\nx", run("    a\n    b", ":set noai<CR>ox<Esc>j."))
  }

  /**
   * The review's regression: `.` went back to Normal mode, the `o` action then asked for the indent
   * to be dropped anyway, and the white space it dropped was the replayed text's.
   */
  @Test
  fun `test dot after o with autoindent off keeps white space the user typed`() {
    assertEquals("a\n  x\nb\n  x", run("a\nb", ":set noai<CR>o  x<Esc>j."))
  }

  @Test
  fun `test dot after O with autoindent off keeps white space the user typed`() {
    assertEquals("a\n  x\n  x\nb", run("a\nb", ":set noai<CR>jO  x<Esc>j."))
  }

  /** Local to the buffer, so `:setlocal` is enough. */
  @Test
  fun `test setlocal noautoindent affects the current buffer`() {
    assertEquals("fun main() {\nx\n}", run(function, ":setlocal noautoindent<CR>jccx<Esc>"))
  }
}
