/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The path functions, which is mostly one function and its modifiers.
 *
 * `expand('%:p:h')` is how every configuration in the world asks for the directory of the file it
 * is looking at, and until now it was `E117: Unknown function` in both hosts. The modifiers are the
 * substance: they are applied left to right and they repeat, so `:h:h` is the grandparent, and a
 * reader who takes each one for a flag rather than a step writes the wrong thing.
 *
 * They are shared with `fnamemodify()`, which is the same feature pointed at a string. Both are
 * tested here for the same reason they share an implementation - if the two ever disagreed, a
 * config would work one way round and not the other.
 */
class HeadlessPathFunctionTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one two", listOf(caret), path = "/work/src/Main.kt")
      .also { caret.editorRef = it }
    val files: HeadlessFileSystem get() = injector.fileSystem as HeadlessFileSystem

    fun value(expression: String): String {
      val parsed = injector.vimscriptParser.parseExpression(expression)
        ?: throw AssertionError("failed to parse: $expression")
      return parsed.evaluate(editor, HeadlessExecutionContext, CommandLineVimLContext).toOutputString()
    }

    fun run(line: String) {
      injector.vimscriptExecutor.execute(
        line, editor, HeadlessExecutionContext, skipHistory = true, indicateErrors = true, CommandLineVimLContext,
      )
    }
  }

  private fun session(): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session()
  }

  // ---- expand ----------------------------------------------------------------------------------

  @Test
  fun `test percent is the file being edited`() {
    val s = session()
    assertEquals("/work/src/Main.kt", s.value("expand('%')"))
  }

  /** The line every configuration has in it. */
  @Test
  fun `test the directory of the current file`() {
    val s = session()
    assertEquals("/work/src", s.value("expand('%:p:h')"))
  }

  @Test
  fun `test the modifiers pick the piece that was wanted`() {
    val s = session()
    assertEquals("Main.kt", s.value("expand('%:t')"))
    assertEquals("kt", s.value("expand('%:e')"))
    assertEquals("/work/src/Main", s.value("expand('%:r')"))
    assertEquals("Main", s.value("expand('%:t:r')"))
  }

  /** Left to right and repeatable, which is the part that reads as a flag and is not. */
  @Test
  fun `test a repeated head walks up`() {
    val s = session()
    assertEquals("/work", s.value("expand('%:h:h')"))
    assertEquals("/", s.value("expand('%:h:h:h')"))
  }

  @Test
  fun `test the word under the caret`() {
    val s = session()
    assertEquals("one", s.value("expand('<cword>')"))
  }

  /** `:.` is relative to the directory `:cd` owns, which is what makes it agree with `:pwd`. */
  @Test
  fun `test a path relative to the current directory`() {
    val s = session()
    s.files.written["/work/src/x"] = ""
    s.run("cd /work")
    assertEquals("src/Main.kt", s.value("expand('%:.')"))
  }

  @Test
  fun `test an ordinary path is expanded rather than treated as a special item`() {
    val s = session()
    assertEquals("dir", s.value("expand('dir/file.txt:h')"))
  }

  // ---- fnamemodify -----------------------------------------------------------------------------

  /** The same modifiers, pointed at a string - which is why the two share their implementation. */
  @Test
  fun `test fnamemodify applies the same modifiers`() {
    val s = session()
    assertEquals("/a/b", s.value("fnamemodify('/a/b/c.txt', ':h')"))
    assertEquals("c.txt", s.value("fnamemodify('/a/b/c.txt', ':t')"))
    assertEquals("txt", s.value("fnamemodify('/a/b/c.txt', ':e')"))
    assertEquals("/a/b/c", s.value("fnamemodify('/a/b/c.txt', ':r')"))
  }

  /** Only the last extension goes, which is why `:r:r` is a thing people write. */
  @Test
  fun `test the root modifier removes one extension at a time`() {
    val s = session()
    assertEquals("a.tar", s.value("fnamemodify('a.tar.gz', ':r')"))
    assertEquals("a", s.value("fnamemodify('a.tar.gz', ':r:r')"))
  }

  @Test
  fun `test the substitute modifier rewrites the path`() {
    val s = session()
    assertEquals("/a/b/c.md", s.value("""fnamemodify('/a/b/c.txt', ':s?txt?md?')"""))
  }

  // ---- escaping and simplifying ----------------------------------------------------------------

  /**
   * The two escapes are for different readers, which is why both exist.
   *
   * `fnameescape()` protects the characters Vim's own command line reads - a `%` in a filename
   * would otherwise become the current file. `shellescape()` protects a shell's.
   */
  @Test
  fun `test the two escapes protect against different readers`() {
    val s = session()
    assertEquals("""a\ b.txt""", s.value("""fnameescape('a b.txt')"""))
    assertEquals("'a b.txt'", s.value("""shellescape('a b.txt')"""))
  }

  /** There is no escape inside single quotes, which is the whole reason this looks the way it does. */
  @Test
  fun `test shellescape closes and reopens its quoting for a quote`() {
    val s = session()
    assertEquals("""'it'\''s'""", s.value("""shellescape("it's")"""))
  }

  @Test
  fun `test simplify does the arithmetic without touching the disk`() {
    val s = session()
    assertEquals("b", s.value("simplify('a/../b')"))
    assertEquals("/a/c", s.value("simplify('/a/b/../c')"))
    assertEquals("a/b", s.value("simplify('a/./b')"))
    assertEquals("../a", s.value("simplify('../a')"), "the parent of somewhere unknown stays")
  }

  /** What it is for is a status line: still tells you where you are, in a fraction of the width. */
  @Test
  fun `test pathshorten cuts the directories and keeps the file`() {
    val s = session()
    assertEquals("/h/u/p/main.c", s.value("pathshorten('/home/user/projects/main.c')"))
  }

  @Test
  fun `test isabsolutepath`() {
    val s = session()
    assertEquals("1", s.value("isabsolutepath('/a/b')"))
    assertEquals("0", s.value("isabsolutepath('a/b')"))
  }

  // ---- asking the filesystem -------------------------------------------------------------------

  @Test
  fun `test filereadable and isdirectory are the pair a config uses`() {
    val s = session()
    s.files.written["/work/thing.txt"] = "content"
    assertEquals("1", s.value("filereadable('/work/thing.txt')"))
    assertEquals("0", s.value("filereadable('/work')"), "a directory is not a readable file")
    assertEquals("1", s.value("isdirectory('/work')"))
    assertEquals("0", s.value("isdirectory('/work/thing.txt')"))
    assertEquals("0", s.value("filereadable('/work/nothing')"))
  }

  @Test
  fun `test filewritable tells a directory from a file`() {
    val s = session()
    s.files.written["/work/thing.txt"] = "content"
    assertEquals("1", s.value("filewritable('/work/thing.txt')"))
    assertEquals("2", s.value("filewritable('/work')"))
    assertEquals("0", s.value("filewritable('/work/nothing')"))
  }

  @Test
  fun `test getftype`() {
    val s = session()
    s.files.written["/work/thing.txt"] = "content"
    assertEquals("file", s.value("getftype('/work/thing.txt')"))
    assertEquals("dir", s.value("getftype('/work')"))
    assertEquals("", s.value("getftype('/work/nothing')"))
  }

  @Test
  fun `test getcwd is the directory cd owns`() {
    val s = session()
    s.files.written["/elsewhere/x"] = ""
    s.run("cd /elsewhere")
    assertEquals("/elsewhere", s.value("getcwd()"))
  }

  @Test
  fun `test readfile and writefile are inverses`() {
    val s = session()
    s.value("""writefile(['one', 'two'], '/work/out.txt')""")
    assertEquals("one\ntwo\n", s.files.written["/work/out.txt"])
    assertEquals("['one', 'two']", s.value("readfile('/work/out.txt')"))
  }

  /** A trailing newline ends the last line rather than starting an empty one. */
  @Test
  fun `test readfile does not invent a last empty line`() {
    val s = session()
    s.files.written["/work/x"] = "a\nb\n"
    assertEquals("2", s.value("len(readfile('/work/x'))"))
  }

  @Test
  fun `test readfile takes the first or the last few lines`() {
    val s = session()
    s.files.written["/work/x"] = "a\nb\nc\nd\n"
    assertEquals("['a', 'b']", s.value("readfile('/work/x', '', 2)"))
    assertEquals("['c', 'd']", s.value("readfile('/work/x', '', -2)"))
  }

  @Test
  fun `test an unreadable file is an empty list rather than an error`() {
    val s = session()
    assertEquals("[]", s.value("readfile('/work/nothing')"))
  }

  @Test
  fun `test writefile can append`() {
    val s = session()
    s.value("""writefile(['one'], '/work/out.txt')""")
    s.value("""writefile(['two'], '/work/out.txt', 'a')""")
    assertEquals("one\ntwo\n", s.files.written["/work/out.txt"])
  }

  // ---- globbing --------------------------------------------------------------------------------

  /**
   * The same globber `:vimgrep` uses, which is the point rather than an economy.
   *
   * Two implementations would agree for a while and then disagree about a double star, and the
   * disagreement would show up as a config that works in one command and not the other.
   */
  @Test
  fun `test glob finds the files a pattern names`() {
    val s = session()
    s.files.written["/work/src/Main.kt"] = ""
    s.files.written["/work/src/Other.kt"] = ""
    s.files.written["/work/src/notes.txt"] = ""

    assertEquals("/work/src/Main.kt\n/work/src/Other.kt", s.value("""glob('/work/src/*.kt')"""))
  }

  @Test
  fun `test glob can answer with a list`() {
    val s = session()
    s.files.written["/work/src/Main.kt"] = ""
    assertEquals("['/work/src/Main.kt']", s.value("""glob('/work/src/*.kt', 0, 1)"""))
  }

  @Test
  fun `test a pattern that names nothing is empty rather than itself`() {
    val s = session()
    assertEquals("", s.value("""glob('/work/nothing/*.kt')"""))
    assertEquals("", s.value("""glob('/work/nofile.kt')"""))
  }

  @Test
  fun `test globpath looks under each of several directories`() {
    val s = session()
    s.files.written["/a/colors/one.vim"] = ""
    s.files.written["/b/colors/two.vim"] = ""

    assertEquals("/a/colors/one.vim\n/b/colors/two.vim", s.value("""globpath('/a,/b', 'colors/*.vim')"""))
  }

  /** The one of the three that touches no files: a glob as a pattern, for `=~`. */
  @Test
  fun `test glob2regpat turns a glob into a pattern`() {
    val s = session()
    assertEquals("""^.*\.kt${'$'}""", s.value("""glob2regpat('*.kt')"""))
    assertEquals("1", s.value("""'Main.kt' =~ glob2regpat('*.kt')"""))
    assertEquals("0", s.value("""'Main.java' =~ glob2regpat('*.kt')"""))
  }
}
