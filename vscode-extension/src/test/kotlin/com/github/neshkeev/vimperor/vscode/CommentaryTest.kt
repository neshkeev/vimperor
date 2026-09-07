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
import kotlin.test.assertTrue

/**
 * `gc`, and the first extension on this host whose work is done by VS Code rather than by the
 * engine.
 *
 * Everything else bundled here computes a range and edits the buffer itself. Commenting cannot:
 * a comment is `//` in one language and `#` in another, and which one applies is a fact about the
 * file that only the host has. So the extension asks `VimCommentService` to toggle a *range*, and
 * on this host that means putting a selection over it and dispatching `editor.action.commentLine` -
 * a command that lands later, like `undo` and the re-indent behind `=`.
 *
 * These tests therefore assert three separate things, and the middle one is the interesting one:
 * which command went out, what selection it was given, and where the caret ended up once it landed.
 * A stub VS Code cannot comment anything, so the test does the commenting itself in [complete] -
 * which also means the toggle in these fixtures is `// `, not whatever the file's language uses.
 */
class CommentaryTest {

  /** A VS Code whose commands land when the test says so, and which comments when they do. */
  private class Session(text: String) {
    val fake = FakeEditor(text)
    val dispatched: MutableList<String> = mutableListOf()
    private val callbacks: MutableList<(Boolean) -> Unit> = mutableListOf()

    val host = VimHost(runCommand = { command, _, onDone ->
      dispatched += command
      callbacks += onDone
    }).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      injector.extensionLoader.enableExtension(
        ExtensionBean("commentary", VsCodeExtensions.PLUGIN_ID, "init", ""),
      )
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }

    fun ex(command: String) {
      type(":$command")
      host.key(fake, "<CR>")
    }

    val content: String get() = fake.document.content
    val caret: Int get() = host.editorFor(fake).primaryCaret().offset

    /**
     * The lines the selection covers, which is what VS Code's own command would act on.
     *
     * `anchor` and `active` rather than `start` and `end`: VS Code's `Selection` is a `Range` whose
     * ends are ordered, and the class this host declares carries only the two it was constructed
     * with. The host always builds them in document order, so `anchor` is the earlier one.
     */
    val selectedLines: IntRange
      get() = fake.selection.anchor.line..fake.selection.active.line

    /**
     * Does what VS Code would have done, then lets the command resolve.
     *
     * The toggle is the real one in shape if not in syntax: a selection where *every* line is
     * already commented gets uncommented, and anything else gets commented - which is the decision
     * the host makes and the reason the engine asks for a toggle rather than for "comment this".
     */
    fun complete() {
      val waiting = callbacks.toList()
      callbacks.clear()
      if (dispatched.isNotEmpty()) toggleSelectedLines()
      dispatched.clear()
      waiting.forEach { it(true) }
    }

    private fun toggleSelectedLines() {
      val lines = fake.document.content.split("\n").toMutableList()
      val touched = selectedLines.filter { it in lines.indices }
      val allCommented = touched.all { lines[it].trimStart().startsWith(PREFIX) }
      for (line in touched) {
        lines[line] = if (allCommented) lines[line].replaceFirst(PREFIX, "") else PREFIX + lines[line]
      }
      fake.document.content = lines.joinToString("\n")
      fake.document.version++
    }
  }

  // ---- which command goes out ----------------------------------------------------------------

  @Test
  fun `test gcc asks VS Code to toggle a line comment`() {
    val session = Session("one\ntwo\n")

    session.type("gcc")

    assertEquals(listOf("editor.action.commentLine"), session.dispatched)
  }

  @Test
  fun `test the selection it is given is the line the caret is on`() {
    val session = Session("one\ntwo\nthree\n")
    session.type("j")

    session.type("gcc")

    assertEquals(1..1, session.selectedLines)
  }

  @Test
  fun `test gc with a motion covers every line the motion touched`() {
    val session = Session("one\ntwo\nthree\n")

    session.type("gcj")

    assertEquals(0..1, session.selectedLines)
  }

  /** `:Commentary` takes a range the ex way, which is the other road into the same function. */
  @Test
  fun `test the Commentary command takes a range`() {
    val session = Session("one\ntwo\nthree\n")

    session.ex("1,2Commentary")

    assertEquals(listOf("editor.action.commentLine"), session.dispatched)
    assertEquals(0..1, session.selectedLines)
  }

  // ---- and what the buffer looks like once it lands --------------------------------------------

  @Test
  fun `test the line is commented once the command lands`() {
    val session = Session("one\ntwo\n")

    session.type("gcc")
    session.complete()

    assertEquals("// one\ntwo\n", session.content)
  }

  /** The toggle half: `gcc` on a commented line takes the comment off again. */
  @Test
  fun `test gcc on a commented line uncomments it`() {
    val session = Session("// one\ntwo\n")

    session.type("gcc")
    session.complete()

    assertEquals("one\ntwo\n", session.content)
  }

  @Test
  fun `test gc over a motion comments every line it covered`() {
    val session = Session("one\ntwo\nthree\n")

    session.type("gcj")
    session.complete()

    assertEquals("// one\n// two\nthree\n", session.content)
  }

  @Test
  fun `test gc in visual mode comments the selection`() {
    val session = Session("one\ntwo\nthree\n")

    session.type("Vj")
    session.type("gc")
    session.complete()

    assertEquals("// one\n// two\nthree\n", session.content)
  }

  /**
   * The caret is placed after the command lands rather than before, and by *line* rather than by
   * offset: commenting inserts text ahead of the caret, so the offset the extension asked for names
   * a different character by the time the toggle is done.
   */
  @Test
  fun `test the caret lands on the line it started on`() {
    val session = Session("one\ntwo\nthree\n")
    session.type("j")

    session.type("gcc")
    session.complete()

    assertEquals("one\n// two\nthree\n", session.content)
    assertEquals(4, session.caret, "the start of the line that was commented, not four bytes into it")
  }

  // ---- the part this host cannot do ------------------------------------------------------------

  /**
   * `dgc` is the text object over a run of comment lines, and it needs to know which lines *are*
   * comments - `injector.psiService.getCommentBlockRange`, which `VsCodePsiService` answers null
   * to. So it does nothing here, which is asserted rather than left to be discovered.
   */
  @Test
  fun `test dgc does nothing without a syntax tree`() {
    val session = Session("// one\n// two\nthree\n")

    session.type("dgc")

    assertEquals("// one\n// two\nthree\n", session.content)
    assertTrue(session.dispatched.isEmpty(), "and asks VS Code for nothing")
  }

  // ---- turning it off --------------------------------------------------------------------------

  /** `:Commentary` is a command alias, which the loader cannot remove by owner. */
  @Test
  fun `test disabling it takes the Commentary command with it`() {
    Session("one\n")
    assertTrue(injector.commandGroup.hasAlias("Commentary"))

    injector.extensionLoader.disableExtension("commentary")

    assertTrue(!injector.commandGroup.hasAlias("Commentary"), "the command should go with the extension")
  }

  private companion object {
    const val PREFIX = "// "
  }
}
