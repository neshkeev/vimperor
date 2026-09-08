/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `<Tab>` on a file argument, and `<Tab>` after a `%`.
 *
 * The engine has always asked the host to list a directory and this host answered `emptyList()` -
 * `VimFile.listFilesForCompletion` has a default and nothing overrode it - so `:e <Tab>` did
 * nothing at all. Real files in a real directory, because the whole question is what is on disk.
 *
 * The completion is re-prefixed with the directory *as typed*, which is the part worth asserting:
 * the command line is rewritten with what comes back, so a completion that answered with an
 * absolute path where the user typed `%:h/` would silently change the command.
 */
class FileCompletionTest {

  private class RecordingDisplay : CommandLineDisplay {
    var shown: String? = null
      private set

    override fun show(text: String, caret: Int?) { shown = text }
    override fun showMatches(line: String?) {}
    override fun hide() {}
  }

  private class Session(path: String) {
    val fake = FakeEditor("", path)
    val display = RecordingDisplay()
    val host = VimHost(commandLineDisplay = display).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun complete(line: String): String? {
      host.key(fake, "<Esc>")
      ":$line".forEach { host.type(fake, it.toString()) }
      host.key(fake, "<Tab>")
      return display.shown
    }
  }

  @Test
  fun `test Tab completes a file name in a directory`() {
    val directory = temporaryDirectory()
    writeFile("$directory/notes.md", "")
    val session = Session("$directory/current.md")

    assertEquals(":e $directory/notes.md", session.complete("e $directory/no"))
  }

  /** A directory is offered with a trailing slash, so a second `<Tab>` can carry on into it. */
  @Test
  fun `test a directory completes with a trailing slash`() {
    val directory = temporaryDirectory()
    writeFile("$directory/sources/one.md", "")
    val session = Session("$directory/current.md")

    assertEquals(":e $directory/sources/", session.complete("e $directory/sou"))
  }

  /**
   * The point of the whole exercise: `%:h` is the directory of the current file, so `:e %:h/`
   * completes against what sits beside it. The name is expanded before the listing and the typed
   * text is what gets extended.
   */
  @Test
  fun `test Tab completes through the percent head modifier`() {
    val directory = temporaryDirectory()
    writeFile("$directory/sibling.md", "")
    val session = Session("$directory/current.md")

    assertEquals(":e $directory/sibling.md", session.complete("e %:h/sib"))
  }

  /** Nothing matches, so the line is left as typed rather than being rewritten with a guess. */
  @Test
  fun `test no match leaves the line alone`() {
    val directory = temporaryDirectory()
    writeFile("$directory/notes.md", "")
    val session = Session("$directory/current.md")

    assertEquals(":e $directory/zzz", session.complete("e $directory/zzz"))
  }
}
