/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.host
import com.github.neshkeev.vimperor.tags.Tags
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tags, over a real `tags` file.
 *
 * A tags file rather than the host's symbol index, and the tests are shaped by that choice: they
 * write a file and read it back, because that is what the feature does. The reason for the choice
 * is in [Tags], and it is `E426` - a `:tag` has to know whether the tag exists before it returns,
 * and neither host's symbol search will answer that soon enough.
 *
 * The stack is the part most worth pinning. It is not a stack of places but a stack of *jumps*, and
 * each one keeps the whole list of matches it chose from - which is what makes `:tnext` after a
 * `:pop` walk the right list, and it is the thing that would quietly be wrong if the entry held
 * only the match it went to.
 */
class HeadlessTagsTest {

  private class Session(text: String = "one\ntwo\nthree\nfour\nfive") {
    val caret = TestVimCaret(0, isPrimary = true)
    val editor = TestVimEditor(text, listOf(caret)).also { caret.editorRef = it }
    val messages: HeadlessMessages get() = injector.messages as HeadlessMessages
    val files: HeadlessFileSystem get() = injector.fileSystem as HeadlessFileSystem
    val opened: List<String> get() = (injector.file as HeadlessFile).opened
    val printed: String
      get() = (injector.outputPanel as HeadlessOutputPanelService).lines.joinToString("\n").trimEnd('\n')

    /** A tags file in the working directory, which is where a bare `tags` in `'tags'` looks. */
    fun writeTags(vararg lines: String) {
      files.written["/work/tags"] = lines.joinToString("\n") + "\n"
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

  private fun session(text: String = "one\ntwo\nthree\nfour\nfive"): Session {
    injector = HeadlessInjector()
    injector.functionService.registerHandlers()
    return Session(text)
  }

  // Reading the file.

  @Test
  fun `test a tags line is name, file and address`() {
    val match = Tags.parse("main\tsrc/Main.kt\t42", "/work").single()

    assertEquals("main", match.name)
    assertEquals("/work/src/Main.kt", match.path)
    assertEquals("42", match.address)
  }

  /** ctags puts everything after `;"` and it is not part of the address. */
  @Test
  fun `test the extension fields are not part of the address`() {
    val match = Tags.parse("main\tMain.kt\t/^fun main() {$/;\"\tf\tline:42", "/work").single()

    assertEquals("/^fun main() {$/", match.address)
    assertEquals("f", match.kind)
  }

  @Test
  fun `test the file's own metadata lines are not tags`() {
    val text = "!_TAG_FILE_FORMAT\t2\t/extended format/\nmain\tMain.kt\t1\n"

    assertEquals(listOf("main"), Tags.parse(text, "/work").map { it.name })
  }

  /** A path in a tags file is relative to the tags file, not to wherever Vim happens to be. */
  @Test
  fun `test paths are resolved against the tags file`() {
    val absolute = Tags.parse("main\t/elsewhere/Main.kt\t1", "/work").single()
    val relative = Tags.parse("main\tsub/Main.kt\t1", "/deep/project").single()

    assertEquals("/elsewhere/Main.kt", absolute.path)
    assertEquals("/deep/project/sub/Main.kt", relative.path)
  }

  // Addresses.

  @Test
  fun `test a number address is a line number, counted from one`() {
    val match = Tags.parse("main\tMain.kt\t3", "/work").single()

    assertEquals(2, Tags.lineFor(match, "a\nb\nc\nd"))
  }

  /**
   * A search address is the line as ctags found it. Matched as text rather than as a regular
   * expression - see [Tags] - which is why the anchors have to come off.
   */
  @Test
  fun `test a search address finds the line it was written from`() {
    val match = Tags.parse("main\tMain.kt\t/^fun main() {$/", "/work").single()

    assertEquals(2, Tags.lineFor(match, "package x\n\nfun main() {\n}"))
  }

  @Test
  fun `test a search address that matches nothing answers nothing`() {
    val match = Tags.parse("main\tMain.kt\t/^gone forever$/", "/work").single()

    assertNull(Tags.lineFor(match, "a\nb\nc"))
  }

  // Finding.

  @Test
  fun `test no tags file anywhere is E433 rather than tag not found`() {
    val s = session()
    s.messages.clearError()
    s.run("tag main")

    assertTrue(s.messages.lastError?.contains("E433") == true, "got ${s.messages.lastError}")
  }

  @Test
  fun `test a name no tags file has is E426`() {
    val s = session()
    s.writeTags("main\tMain.kt\t1")
    s.messages.clearError()
    s.run("tag nosuchthing")

    assertTrue(s.messages.lastError?.contains("E426") == true, "got ${s.messages.lastError}")
    assertEquals(emptyList(), s.opened, "a tag that was not found must not have opened anything")
  }

  @Test
  fun `test tag opens the file the tag is in`() {
    val s = session()
    s.writeTags("main\tMain.kt\t3")
    s.run("tag main")

    assertEquals(listOf("/work/Main.kt"), s.opened)
  }

  @Test
  fun `test tag puts the caret on the line the address names`() {
    val s = session()
    s.writeTags("main\tMain.kt\t4")
    s.run("tag main")

    assertEquals(3, s.editor.offsetToBufferPosition(s.caret.offset).line)
  }

  // The stack.

  @Test
  fun `test tags prints the jump and where it came from`() {
    val s = session()
    s.writeTags("main\tMain.kt\t2")
    s.run("tag main")
    s.run("tags")

    assertTrue(s.printed.lines().first().startsWith("  # TO tag"), "got ${s.printed}")
    assertTrue(s.printed.contains("main"), "got ${s.printed}")
  }

  @Test
  fun `test pop goes back to where the tag was jumped from`() {
    val s = session()
    s.writeTags("main\tMain.kt\t5")
    s.caret.moveToOffset(s.editor.bufferPositionToOffset(position(1, 0)))
    s.run("tag main")
    s.run("pop")

    assertEquals(1, s.editor.offsetToBufferPosition(s.caret.offset).line)
  }

  @Test
  fun `test popping an empty stack is E555`() {
    val s = session()
    s.messages.clearError()
    s.run("pop")

    assertTrue(s.messages.lastError?.contains("E555") == true, "got ${s.messages.lastError}")
  }

  /** `:tag` with no argument is the other half of `:pop`, and says so at the top. */
  @Test
  fun `test tag with no argument at the top of the stack is E556`() {
    val s = session()
    s.writeTags("main\tMain.kt\t2")
    s.run("tag main")
    s.messages.clearError()
    s.run("tag")

    assertTrue(s.messages.lastError?.contains("E556") == true, "got ${s.messages.lastError}")
  }

  @Test
  fun `test tag with no argument walks back up after a pop`() {
    val s = session()
    s.writeTags("main\tMain.kt\t4")
    s.run("tag main")
    s.run("pop")
    s.run("tag")

    assertEquals(3, s.editor.offsetToBufferPosition(s.caret.offset).line, "back at the tag")
  }

  // Several matches.

  @Test
  fun `test tselect prints every match, numbered`() {
    val s = session()
    s.writeTags("main\tOne.kt\t1", "main\tTwo.kt\t2")
    s.run("tselect main")

    assertTrue("One.kt" in s.printed, "got ${s.printed}")
    assertTrue("Two.kt" in s.printed, "got ${s.printed}")
    assertTrue(s.printed.contains(">  1"), "the first match is the current one: ${s.printed}")
  }

  /** The one difference Vim keeps between the two: `:tjump` does not stop to show a list of one. */
  @Test
  fun `test tjump goes straight there when there is only one match`() {
    val s = session()
    s.writeTags("main\tOnly.kt\t2")
    s.run("tjump main")

    assertEquals(listOf("/work/Only.kt"), s.opened)
  }

  @Test
  fun `test tjump shows the list when it cannot choose`() {
    val s = session()
    s.writeTags("main\tOne.kt\t1", "main\tTwo.kt\t2")
    s.run("tjump main")

    assertEquals(emptyList(), s.opened, "nothing should be opened while the choice is open")
    assertTrue("One.kt" in s.printed)
  }

  @Test
  fun `test tnext walks to the next match and tprevious comes back`() {
    val s = session()
    s.writeTags("main\tOne.kt\t1", "main\tTwo.kt\t2")
    s.run("tag main")
    s.run("tnext")
    s.run("tprevious")

    assertEquals(listOf("/work/One.kt", "/work/Two.kt", "/work/One.kt"), s.opened)
  }

  @Test
  fun `test tlast goes to the end and tfirst comes back`() {
    val s = session()
    s.writeTags("main\tOne.kt\t1", "main\tTwo.kt\t2", "main\tThree.kt\t3")
    s.run("tag main")
    s.run("tlast")
    s.run("tfirst")

    assertEquals(listOf("/work/One.kt", "/work/Three.kt", "/work/One.kt"), s.opened)
  }

  @Test
  fun `test walking past either end says which end`() {
    val s = session()
    s.writeTags("main\tOne.kt\t1", "main\tTwo.kt\t2")
    s.run("tag main")

    s.messages.clearError()
    s.run("tprevious")
    assertTrue(s.messages.lastError?.contains("E425") == true, "got ${s.messages.lastError}")

    s.run("tlast")
    s.messages.clearError()
    s.run("tnext")
    assertTrue(s.messages.lastError?.contains("E428") == true, "got ${s.messages.lastError}")
  }

  /** A different message when there was never a choice to begin with, which is Vim's. */
  @Test
  fun `test walking a list of one says there is only one`() {
    val s = session()
    s.writeTags("main\tOnly.kt\t1")
    s.run("tag main")
    s.messages.clearError()
    s.run("tnext")

    assertTrue(s.messages.lastError?.contains("E427") == true, "got ${s.messages.lastError}")
  }

  @Test
  fun `test walking with nothing jumped to is E73`() {
    val s = session()
    s.messages.clearError()
    s.run("tnext")

    assertTrue(s.messages.lastError?.contains("E73") == true, "got ${s.messages.lastError}")
  }

  /** The list belongs to the jump, so popping back into one walks the list that jump chose from. */
  @Test
  fun `test the match list follows the stack entry rather than the session`() {
    val s = session()
    s.writeTags("first\tA.kt\t1", "first\tB.kt\t2", "second\tC.kt\t1")
    s.run("tag first")
    s.run("tag second")
    s.run("pop")
    s.run("tnext")

    assertEquals("/work/B.kt", s.opened.last(), "back in `first`, so `:tnext` is first's second match")
  }

  // The preview window, which neither host has.

  @Test
  fun `test the preview tag commands report E319`() {
    val s = session()
    for (line in listOf("ptag main", "ptselect main", "ptjump main", "ptnext", "ptprevious", "ptfirst", "ptlast")) {
      s.messages.clearError()
      s.run(line)
      assertTrue(s.messages.lastError?.contains("E319") == true, "`:$line` gave ${s.messages.lastError}")
    }
  }

  private fun position(line: Int, column: Int) =
    com.maddyhome.idea.vim.api.BufferPosition(line, column, false)
}
