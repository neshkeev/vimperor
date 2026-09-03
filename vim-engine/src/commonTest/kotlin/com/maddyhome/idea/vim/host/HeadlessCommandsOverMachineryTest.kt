/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.quickfix.Quickfix
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The commands that are one more turn of machinery this fork already has.
 *
 * None of these is a new feature. `:stag` is `:split` and `:tag`, `:ltag` is `:tag` writing its
 * matches into the location list, `:doautoall` is `:doautocmd` for every buffer, `:argedit` is
 * `:edit` that also joins the list. They are grouped here because the thing worth testing about
 * each is the *seam* - that the second half really runs, and runs against the same state the
 * standalone command would.
 */
class HeadlessCommandsOverMachineryTest {

  private class Session {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor("one\ntwo\nthree", listOf(caret), path = "/work/a.txt")
      .also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val files: HeadlessFileSystem get() = injector.fileSystem as HeadlessFileSystem

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

  /**
   * A tags file with two matches for one name, which is the case `:ltag` exists for.
   *
   * `'tags'` is set to the one file rather than left at its default `./tags,tags`, which would find
   * this same file twice in a host where both spellings resolve to the same place.
   */
  private fun twoMatchingTags(s: Session) {
    s.files.written["/work/tags"] = listOf(
      "handle\t/work/first.kt\t/^fun handle() {}$/",
      "handle\t/work/second.kt\t/^fun handle() {}$/",
    ).joinToString("\n")
    s.files.written["/work/first.kt"] = "package a\n\nfun handle() {}\n"
    s.files.written["/work/second.kt"] = "package b\nfun handle() {}\n"
    s.run("set tags=/work/tags")
  }

  // ---- :ltag ---------------------------------------------------------------------------------------

  /**
   * The difference from `:tag`: the whole list of matches ends up somewhere you can read it.
   *
   * `:tag` gives one place and the tag stack; `:ltag` gives the location list, which for a name
   * defined in two files is the difference between pressing `:tnext` and looking at a list.
   */
  @Test
  fun `test ltag fills the location list with every match`() {
    val s = session()
    twoMatchingTags(s)
    s.run("ltag handle")

    val entries = Quickfix.location(s.editor).all()
    assertEquals(listOf("/work/first.kt", "/work/second.kt"), entries.map { it.path })
    assertEquals(listOf("handle", "handle"), entries.map { it.text })
  }

  /**
   * The addresses are resolved, which is what costs a file read each and what makes them jumpable.
   *
   * A tags file stores a search pattern rather than a line number, so an entry whose line was left
   * at zero would send `:lnext` to the top of the file instead of to the definition.
   */
  @Test
  fun `test ltag resolves each address to the line it actually matches`() {
    val s = session()
    twoMatchingTags(s)
    s.run("ltag handle")

    assertEquals(listOf(2, 1), Quickfix.location(s.editor).all().map { it.line })
  }

  @Test
  fun `test ltag on a name nothing defines is E426`() {
    val s = session()
    twoMatchingTags(s)
    s.run("ltag nosuchname")

    assertEquals("E426: Tag not found: nosuchname", s.messages.lastError)
  }

  // ---- :stag ---------------------------------------------------------------------------------------

  /** `:split` and then `:tag`, so the tag half has to reach the same tags file `:tag` does. */
  @Test
  fun `test stag runs the tag command after splitting`() {
    val s = session()
    twoMatchingTags(s)
    s.run("stag handle")

    assertEquals("tag 1 of 2  handle", s.messages.getStatusBarMessage())
  }

  @Test
  fun `test stag reports the tag command's own error`() {
    val s = session()
    twoMatchingTags(s)
    s.run("stag nosuchname")

    assertEquals("E426: Tag not found: nosuchname", s.messages.lastError)
  }

  // ---- :doautoall ------------------------------------------------------------------------------------

  /**
   * Every open buffer, which is the whole difference from `:doautocmd`.
   *
   * The command a config reaches for after installing handlers, when the answer to "and the files
   * that were already open?" has to be all of them.
   */
  @Test
  fun `test doautoall fires the event for every open editor`() {
    val s = session()
    val caret = TestVimCaret(0, isPrimary = true)
    TestVimEditor("other", listOf(caret), path = "/work/b.txt").also { caret.editorRef = it }

    s.run("let g:seen = 0")
    s.run("autocmd BufWritePost * let g:seen = g:seen + 1")
    s.run("doautoall BufWritePost")

    injector.outputPanel.clear(s.editor, HeadlessExecutionContext)
    s.run("echo g:seen")
    val panel = injector.outputPanel as HeadlessOutputPanelService
    assertEquals("2\n", panel.lines.joinToString(""), "two editors are open, so the handler ran twice")
  }

  /** And the difference from `:doautocmd`, which fires for one. */
  @Test
  fun `test doautocmd fires for the current editor only`() {
    val s = session()
    val caret = TestVimCaret(0, isPrimary = true)
    TestVimEditor("other", listOf(caret), path = "/work/b.txt").also { caret.editorRef = it }

    s.run("let g:seen = 0")
    s.run("autocmd BufWritePost * let g:seen = g:seen + 1")
    s.run("doautocmd BufWritePost")

    injector.outputPanel.clear(s.editor, HeadlessExecutionContext)
    s.run("echo g:seen")
    assertEquals("1\n", (injector.outputPanel as HeadlessOutputPanelService).lines.joinToString(""))
  }

  @Test
  fun `test doautoall on an event nothing has is E216`() {
    val s = session()
    s.run("doautoall NoSuchEvent")

    assertEquals("E216: No such group or event: NoSuchEvent", s.messages.lastError)
  }

  // ---- the argument list -----------------------------------------------------------------------------

  @Test
  fun `test argedit opens the file it names`() {
    val s = session()
    s.files.written["/work/new.txt"] = "content"
    s.run("argedit /work/new.txt")

    assertEquals(listOf("/work/new.txt"), (injector.file as HeadlessFile).opened)
  }

  @Test
  fun `test argedit with no name at all is E471`() {
    val s = session()
    s.run("argedit")

    assertEquals("E471: Argument required", s.messages.lastError)
  }

  /**
   * `:argglobal` and `:arglocal` with nothing after them are true by construction here.
   *
   * Vim switches a window between the global list and a private copy; there is one list here and
   * every window sees it, so there is nothing to switch and nothing to report.
   */
  @Test
  fun `test argglobal and arglocal with no argument do nothing and say nothing`() {
    val s = session()
    s.run("argglobal")
    s.run("arglocal")

    assertEquals(null, s.messages.lastError)
    assertEquals(emptyList(), (injector.file as HeadlessFile).opened)
  }

  @Test
  fun `test argglobal with names opens them without taking the focus`() {
    val s = session()
    s.files.written["/work/x.txt"] = "x"
    s.files.written["/work/y.txt"] = "y"
    s.run("argglobal /work/x.txt /work/y.txt")

    assertEquals(listOf("/work/x.txt", "/work/y.txt"), (injector.file as HeadlessFile).opened)
  }

  // ---- :tabfind --------------------------------------------------------------------------------------

  /**
   * `:tabfind` is `:find` here, and the test says so rather than the comment alone.
   *
   * Vim opens a tab page, which is a window holding windows; both hosts open a file in its own tab
   * already, so the two commands are one journey and the name is registered so a config written
   * for Vim reads the same.
   */
  @Test
  fun `test tabfind opens the file the way find does`() {
    val s = session()
    s.files.written["/work/found.txt"] = "here"
    s.run("tabfind /work/found.txt")

    assertEquals(listOf("/work/found.txt"), (injector.file as HeadlessFile).opened)
  }
}
