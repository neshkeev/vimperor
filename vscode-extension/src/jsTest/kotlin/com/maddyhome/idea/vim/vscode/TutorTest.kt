/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `vimtutor`, which is how most people learned Vim in the first place.
 *
 * The lessons are Vim's and live in `vim-engine`, shared with IdeaVim; what this host supplies is
 * the handful of paragraphs that are about the editor rather than about Vim. Those are what can be
 * wrong, so those are what is tested - along with the shape of the document, because fixed-width
 * ASCII spliced into more fixed-width ASCII is exactly the sort of thing that silently loses a
 * column.
 *
 * The test that matters most is [`the tutor never promises something this host cannot do`]: a
 * tutor teaching a command that does not exist is worse than no tutor, and the first draft of this
 * one told the reader to run a Command Palette entry that had never been written.
 */
class TutorTest {

  private val text = tutorText

  // The document, as a document.

  @Test
  fun `test the tutor is titled for this host`() {
    assertTrue("Welcome to the Vimperor Tutor" in text, "the title should name this host")
    assertTrue("Welcome to the IdeaVim Tutor" !in text, "and should not name the other one")
  }

  @Test
  fun `test the banner is not left ragged by the name`() {
    // The name is spliced into a fixed-width rule, and "Vimperor" is not the length of "IdeaVim".
    val rule = text.lineSequence().first()
    val title = text.lineSequence().drop(1).first()
    assertEquals(79, rule.length, "the banner rule")
    assertEquals(79, title.length, "the title line has to match it")
    assertTrue(title.startsWith("=") && title.endsWith("="), "and keep its ends: $title")
  }

  @Test
  fun `test no substitution was left unmade`() {
    val leftovers = Regex("%[A-Z_]+%").findAll(text).map { it.value }.toList()
    assertEquals(emptyList(), leftovers, "every token should have been replaced")
  }

  @Test
  fun `test the lessons Vim wrote are all still here`() {
    // 25 in the body and 8 in the appendix. If a splice ate one, this is what notices.
    val lessons = Regex("Lesson [0-9]+\\.[0-9]+:").findAll(text).count()
    assertEquals(33, lessons, "lesson headings")
  }

  /**
   * Nothing runs past the width the tutor is drawn at.
   *
   * One line does, and it is Vim's own - the appendix warning has always been 82 columns wide, in
   * IdeaVim and in the tutor IdeaVim adapted. It is named here rather than the limit being raised,
   * so that the next long line is still a failure. This caught a line of this host's own text that
   * was 80 columns on the first draft.
   */
  @Test
  fun `test nothing runs past the width the tutor is drawn at`() {
    val inheritedFromVim = "!! NOTE: Before executing any of the steps below, read this entire lesson!!"
    val tooWide = text.lines()
      .withIndex()
      .filter { (_, line) -> line.length > 79 && inheritedFromVim !in line }
    assertEquals(emptyList(), tooWide.map { "${it.index + 1}: ${it.value}" }, "lines wider than the banner")
  }

  // What this host says for itself, which is the part that can be false.

  @Test
  fun `test the tutor points at the config file this host actually reads`() {
    assertTrue("~/.ideavimrc" in text, "the vimrc this host looks for")
    assertTrue("XDG_CONFIG_HOME/ideavim/ideavimrc" in text, "and the XDG location it falls back to")
  }

  @Test
  fun `test the tutor does not tell the reader to watch a status bar that shows nothing`() {
    // IdeaVim's wording sends the reader looking for a `d` while an operator is pending. That is
    // `'showcmd'`, which this host does not have.
    assertTrue(
      "will appear in the status bar as you type it" !in text,
      "this host has no 'showcmd', so it must not promise one",
    )
  }

  @Test
  fun `test the tutor does not offer plugins this host cannot load`() {
    assertTrue("Plug '" !in text, "Vim plugins are not supported yet and must not be taught")
    assertTrue("highlightedyank" !in text, "nor the plugin IdeaVim suggests")
  }

  /**
   * Everything the tutor tells the reader to type has to work.
   *
   * This is the whole point of the exercise. Each of these is run against the engine, so a lesson
   * that has drifted away from the host fails here rather than in front of somebody learning Vim.
   */
  @Test
  fun `test the tutor never promises something this host cannot do`() {
    val promised = listOf("q", "w /tmp/vimperor-tutor-test.txt", "r /tmp/vimperor-tutor-test.txt", "help")
    for (command in promised) {
      val fake = FakeEditor("hello\nworld")
      val sink = RecordingSink()
      val host = VimHost(sink = sink).also { it.start() }
      KeyHandler.getInstance().fullReset(host.editorFor(fake))

      ":$command".forEach { host.type(fake, it.toString()) }
      host.key(fake, "<CR>")

      assertEquals(emptyList(), sink.errors, "`:$command` is named in the tutor and must work")
    }
  }

  @Test
  fun `test the command that opens it is in the manifest`() {
    // The palette entry is declared in `package.json`, not in Kotlin, so nothing else would notice
    // if the id here and the id there stopped matching.
    val root = repositoryRoot()
    assertTrue(root != null, "could not find the repository root, so package.json could not be read")
    val manifest = JSON.parse<dynamic>(readText("$root/vscode-extension/package.json"))
    val commands = manifest.contributes.commands as Array<dynamic>
    val ids = commands.map { it.command as String }
    assertTrue("vimperor.tutor" in ids, "the tutor command should be contributed; found $ids")
  }

  @Test
  fun `test the Vim license travels with the packaged extension`() {
    // The tutor text is Vim-licensed and `vsce package` bundles this directory alone, so the
    // license has to be inside it rather than only at the repository root.
    val root = repositoryRoot()
    assertTrue(root != null, "could not find the repository root")
    val licenses = readText("$root/vscode-extension/ThirdPartyLicenses.md")
    assertTrue("VIM LICENSE" in licenses, "the Vim license text")
    assertTrue("It is not allowed to remove this license" in licenses, "in full, not summarised")
  }

  private class RecordingSink : MessageSink {
    val errors = mutableListOf<String>()
    override fun message(text: String?) {}
    override fun error(text: String?) { errors += text.orEmpty() }
    override fun status(text: String?) {}
  }
}
