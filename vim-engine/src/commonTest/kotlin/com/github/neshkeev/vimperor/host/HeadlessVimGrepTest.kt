/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.host
import com.github.neshkeev.vimperor.vimscript.model.commands.VimGrepCommandBase
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.host.HeadlessExecutionContext
import com.maddyhome.idea.vim.host.HeadlessFile
import com.maddyhome.idea.vim.host.HeadlessFileSystem
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.host.HeadlessMessages
import com.maddyhome.idea.vim.host.HeadlessOutputPanelService
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:vimgrep` - Vim's own search across files, into the quickfix list.
 *
 * The other half of the quickfix family, and the half that is worth having: `:grep` shells out to
 * `'grepprg'` and parses what it printed, so its patterns are the shell's; this searches the files
 * itself with Vim's regex engine, so `\<word\>` and `\zs` mean here what they mean at the `/`
 * prompt. That is the whole reason Vim has both, and it is the thing to check.
 *
 * The globbing is the other half of the tests. Vim's rule is that one star stays inside a path
 * segment and two cross them, and getting that backwards is the kind of thing that looks right
 * until a project has a `src/main` and a `src/test`.
 */
class HeadlessVimGrepTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one\ntwo\nthree", listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val files: HeadlessFileSystem get() = injector.fileSystem as HeadlessFileSystem
    val opened: List<String> get() = (injector.file as HeadlessFile).opened
    val printed: String
      get() = (injector.outputPanel as HeadlessOutputPanelService).lines.joinToString("\n").trimEnd('\n')

    fun writeFile(path: String, vararg lines: String) {
      files.written[path] = lines.joinToString("\n") + "\n"
    }

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

  // The pattern and its flags.

  @Test
  fun `test the delimiter can be any punctuation, so a pattern may hold a slash`() {
    val slashes = VimGrepCommandBase.parseArguments("#a/b# src.kt")!!
    val plain = VimGrepCommandBase.parseArguments("/needle/ src.kt")!!

    assertEquals("a/b", slashes.pattern)
    assertEquals("needle", plain.pattern)
  }

  @Test
  fun `test an escaped delimiter is part of the pattern`() {
    val parsed = VimGrepCommandBase.parseArguments("""/a\/b/ src.kt""")!!

    assertEquals("""a\/b""", parsed.pattern)
  }

  /** The form people actually type: no delimiter, pattern up to the first space, no flags. */
  @Test
  fun `test a bare pattern runs to the first space`() {
    val parsed = VimGrepCommandBase.parseArguments("needle src.kt other.kt")!!

    assertEquals("needle", parsed.pattern)
    assertEquals(listOf("src.kt", "other.kt"), parsed.files)
  }

  @Test
  fun `test the g and j flags are read`() {
    val parsed = VimGrepCommandBase.parseArguments("/x/gj a.kt")!!

    assertTrue(parsed.everyMatch)
    assertTrue(parsed.dontJump)
    assertEquals(listOf("a.kt"), parsed.files)
  }

  // Globbing.

  @Test
  fun `test one star stays inside a path segment`() {
    assertTrue(VimGrepCommandBase.matchesSegment("Main.kt", "*.kt"))
    assertTrue(VimGrepCommandBase.matchesSegment("Main.kt", "M*t"))
    assertTrue(!VimGrepCommandBase.matchesSegment("Main.java", "*.kt"))
    assertTrue(VimGrepCommandBase.matchesSegment("abc", "a?c"))
    assertTrue(!VimGrepCommandBase.matchesSegment("ac", "a?c"))
  }

  @Test
  fun `test a star matches nothing as happily as something`() {
    assertTrue(VimGrepCommandBase.matchesSegment(".kt", "*.kt"))
    assertTrue(VimGrepCommandBase.matchesSegment("a", "*a*"))
  }

  @Test
  fun `test a pattern with no wildcard is the file itself`() {
    val s = session()

    assertEquals(listOf("/work/src/Main.kt"), VimGrepCommandBase.expandGlob("src/Main.kt", "/work"))
    assertEquals(listOf("/elsewhere/x"), VimGrepCommandBase.expandGlob("/elsewhere/x", "/work"))
    // Referenced so the session is not unused; the glob above needs no files to answer.
    assertTrue(s.files.written.isEmpty())
  }

  @Test
  fun `test one star lists a directory and two stars walk a tree`() {
    val s = session()
    s.writeFile("/work/src/Main.kt", "x")
    s.writeFile("/work/src/deep/Other.kt", "x")
    s.writeFile("/work/src/Notes.md", "x")

    assertEquals(listOf("/work/src/Main.kt"), VimGrepCommandBase.expandGlob("src/*.kt", "/work"))
    assertEquals(
      listOf("/work/src/Main.kt", "/work/src/deep/Other.kt"),
      VimGrepCommandBase.expandGlob("src/**/*.kt", "/work"),
    )
  }

  // Searching.

  @Test
  fun `test a match fills the quickfix list and opens the first file`() {
    val s = session()
    s.writeFile("/work/a.kt", "nothing", "the needle is here")
    s.run("""vimgrep /needle/ a.kt""")

    assertEquals(listOf("/work/a.kt"), s.opened)

    s.run("clist")
    assertTrue("a.kt:2 col 5: the needle is here" in s.printed, "got ${s.printed}")
  }

  /**
   * The reason `:vimgrep` exists beside `:grep`: the pattern is Vim's, not the shell's.
   *
   * `\<` is a word boundary to this engine and nothing at all to `grep`, so a test that only ever
   * searched for plain text would pass with the wrong engine underneath.
   */
  @Test
  fun `test the pattern is a Vim regular expression`() {
    val s = session()
    s.writeFile("/work/a.kt", "prefixword", "a word here")
    s.run("""vimgrep /\<word\>/ a.kt""")
    s.run("clist")

    assertTrue("a.kt:2" in s.printed, "the word boundary should have skipped `prefixword`: ${s.printed}")
    assertTrue("a.kt:1" !in s.printed, "got ${s.printed}")
  }

  /** Without `g`, Vim records the first match on a line and moves on. */
  @Test
  fun `test one entry per line without the g flag and one per match with it`() {
    val s = session()
    s.writeFile("/work/a.kt", "x x x")

    s.run("""vimgrep /x/j a.kt""")
    s.run("clist")
    assertEquals(1, s.printed.lines().count { "a.kt" in it }, "got ${s.printed}")

    (injector.outputPanel as HeadlessOutputPanelService).getCurrentOutputPanel().clearText()
    s.run("""vimgrep /x/gj a.kt""")
    s.run("clist")
    assertEquals(3, s.printed.lines().count { "a.kt" in it }, "got ${s.printed}")
  }

  @Test
  fun `test the j flag fills the list without going anywhere`() {
    val s = session()
    s.writeFile("/work/a.kt", "needle")
    s.run("""vimgrep /needle/j a.kt""")

    assertEquals(emptyList(), s.opened)
    s.run("clist")
    assertTrue("a.kt" in s.printed)
  }

  @Test
  fun `test several files are searched in the order they were given`() {
    val s = session()
    s.writeFile("/work/a.kt", "needle")
    s.writeFile("/work/b.kt", "needle")
    s.run("""vimgrep /needle/j a.kt b.kt""")
    s.run("clist")

    val order = s.printed.lines().filter { ".kt" in it }
    assertTrue(order[0].contains("a.kt") && order[1].contains("b.kt"), "got ${s.printed}")
  }

  /** `E480` rather than an empty list: a `:vimgrep` that found nothing leaves the list alone. */
  @Test
  fun `test finding nothing is an error and does not empty the list`() {
    val s = session()
    s.writeFile("/work/a.kt", "needle")
    s.run("""vimgrep /needle/j a.kt""")

    s.messages.clearError()
    s.run("""vimgrep /nothinghere/j a.kt""")
    assertTrue(s.messages.lastError?.contains("E480") == true, "got ${s.messages.lastError}")

    s.run("clist")
    assertTrue("needle" in s.printed, "the earlier list should still be there: ${s.printed}")
  }

  @Test
  fun `test vimgrepadd adds to the list instead of replacing it`() {
    val s = session()
    s.writeFile("/work/a.kt", "needle")
    s.writeFile("/work/b.kt", "needle")
    s.run("""vimgrep /needle/j a.kt""")
    s.run("""vimgrepadd /needle/j b.kt""")
    s.run("clist")

    assertTrue("a.kt" in s.printed && "b.kt" in s.printed, "got ${s.printed}")
  }

  /** The `l` twin fills the window's own list, which `:clist` must not see. */
  @Test
  fun `test lvimgrep fills the location list rather than the quickfix list`() {
    val s = session()
    s.writeFile("/work/a.kt", "quickfix")
    s.writeFile("/work/b.kt", "location")
    s.run("""vimgrep /quickfix/j a.kt""")
    s.run("""lvimgrep /location/j b.kt""")

    s.run("clist")
    assertTrue("a.kt" in s.printed && "b.kt" !in s.printed, "got ${s.printed}")

    (injector.outputPanel as HeadlessOutputPanelService).getCurrentOutputPanel().clearText()
    s.run("llist")
    assertTrue("b.kt" in s.printed && "a.kt" !in s.printed, "got ${s.printed}")
  }

  @Test
  fun `test a file that cannot be read is skipped rather than fatal`() {
    val s = session()
    s.writeFile("/work/a.kt", "needle")
    s.run("""vimgrep /needle/j missing.kt a.kt""")
    s.run("clist")

    assertTrue("a.kt" in s.printed, "got ${s.printed}")
  }

  @Test
  fun `test no files at all is E471`() {
    val s = session()
    s.messages.clearError()
    s.run("""vimgrep /needle/""")

    assertTrue(s.messages.lastError?.contains("E471") == true, "got ${s.messages.lastError}")
  }
}
