/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The user's config - `~/.vimperorrc`, or `~/.ideavimrc` - which is how anyone actually
 * configures Vim.
 *
 * Read through Node's `fs` rather than VS Code's `workspace.fs`, for the same reason the command
 * line does not use `showInputBox`: `readFileSync` returns the contents and `workspace.fs` returns
 * a promise, and `:source` has to return with the file. That holds for local workspaces and for
 * SSH and dev containers, where the extension host runs on the remote machine and reads the
 * `.ideavimrc` that is actually there. A web-only workspace has no Node and will need an
 * asynchronous load at startup.
 */
class VimRcTest {

  /** What Vim said, so a config that failed to run says why instead of failing silently. */
  private class RecordingSink : MessageSink {
    val errors: MutableList<String> = mutableListOf()
    override fun message(text: String?) {}
    override fun error(text: String?) { text?.let { errors += it } }
    override fun status(text: String?) {}
  }

  private class Session(text: String, private val files: Map<String, String>) {
    val fake = FakeEditor(text)
    val sink = RecordingSink()
    val host = VimHost(sink = sink).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun load(environment: Map<String, String>): String? =
      host.loadVimRc(host.editorFor(fake)) { environment[it] }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    val content: String get() = fake.document.content
  }

  /** Writes the files a test needs, so the config really is read off a disk. */
  private fun writing(files: Map<String, String>): String {
    val directory = temporaryDirectory()
    files.forEach { (name, content) -> writeFile("$directory/$name", content) }
    return directory
  }

  @Test
  fun `test an option set in the config takes effect`() {
    val home = writing(mapOf(".ideavimrc" to "set ignorecase\n"))
    val session = Session("Foo foo", emptyMap())

    val loaded = session.load(mapOf("HOME" to home))

    assertEquals(emptyList(), session.sink.errors, "the config should have run without complaint")
    assertEquals("$home/.ideavimrc", loaded)
    assertTrue(
      injector.globalOptions().ignorecase,
      "the option should be set the way the config asked",
    )
  }

  @Test
  fun `test a mapping from the config changes what a key does`() {
    // The thing a user notices. Without the config, `Q` is a Vim command of its own.
    val home = writing(mapOf(".ideavimrc" to "nnoremap Q dw\n"))
    val session = Session("one two", emptyMap())
    session.load(mapOf("HOME" to home))

    session.type("Q")

    assertEquals("two", session.content, "Q should have been remapped to dw")
  }

  // ---- which of the two families wins ------------------------------------------------------

  @Test
  fun `test vimperorrc is preferred over ideavimrc`() {
    val home = writing(mapOf(".vimperorrc" to "set ignorecase\n", ".ideavimrc" to "set nowrapscan\n"))
    val session = Session("text", emptyMap())

    assertEquals("$home/.vimperorrc", session.load(mapOf("HOME" to home)))
    assertTrue(injector.globalOptions().wrapscan, "the ideavimrc must not have run as well")
  }

  @Test
  fun `test ideavimrc is read when there is no vimperorrc`() {
    val home = writing(mapOf(".ideavimrc" to "set ignorecase\n"))
    val session = Session("text", emptyMap())

    assertEquals("$home/.ideavimrc", session.load(mapOf("HOME" to home)))
  }

  /**
   * The name is the intent, so a whole family is searched before the next one starts.
   *
   * Within one family the home directory beats XDG. Across the two, a `vimperorrc` anywhere beats
   * an `.ideavimrc` in the home directory - because somebody who wrote a file with that name meant
   * it for this editor, and a search ordered by location rather than by name would ignore that.
   */
  @Test
  fun `test an XDG vimperorrc beats an ideavimrc in the home directory`() {
    val home = writing(mapOf(".ideavimrc" to "set nowrapscan\n"))
    val config = writing(mapOf("vimperor/vimperorrc" to "set ignorecase\n"))
    val session = Session("text", emptyMap())

    assertEquals(
      "$config/vimperor/vimperorrc",
      session.load(mapOf("HOME" to home, "XDG_CONFIG_HOME" to config)),
    )
  }

  @Test
  fun `test _vimperorrc is found when the dotted name is not there`() {
    val home = writing(mapOf("_vimperorrc" to "set ignorecase\n"))
    val session = Session("text", emptyMap())

    assertEquals("$home/_vimperorrc", session.load(mapOf("HOME" to home)))
  }

  /**
   * How a config is shared between the two editors: nothing is merged, so the chaining is written
   * down rather than guessed at.
   */
  @Test
  fun `test a vimperorrc can source the ideavimrc beside it`() {
    val home = writing(mapOf(".ideavimrc" to "nnoremap Q dw\n"))
    writeFile("$home/.vimperorrc", "source $home/.ideavimrc\nset ignorecase\n")
    val session = Session("one two", emptyMap())

    session.load(mapOf("HOME" to home))

    assertEquals(emptyList(), session.sink.errors, "the config should have run without complaint")
    assertTrue(injector.globalOptions().ignorecase, "the vimperorrc's own line should have run")
    session.type("Q")
    assertEquals("two", session.content, "the sourced ideavimrc's mapping should be in place")
  }

  // ---- Vim's own config, for somebody who has never used IdeaVim -----------------------------

  @Test
  fun `test a plain vimrc is read when there is nothing else`() {
    val home = writing(mapOf(".vimrc" to "set ignorecase\n"))
    val session = Session("text", emptyMap())

    assertEquals("$home/.vimrc", session.load(mapOf("HOME" to home)))
    assertTrue(injector.globalOptions().ignorecase, "the vimrc's options should have taken effect")
  }

  @Test
  fun `test an ideavimrc beats a vimrc`() {
    val home = writing(mapOf(".ideavimrc" to "set ignorecase\n", ".vimrc" to "set nowrapscan\n"))
    val session = Session("text", emptyMap())

    assertEquals("$home/.ideavimrc", session.load(mapOf("HOME" to home)))
    assertTrue(injector.globalOptions().wrapscan, "the vimrc must not have run as well")
  }

  /** Where people who keep everything under `~/.vim` put it. One of Vim's own three. */
  @Test
  fun `test the vim directory location is found`() {
    val home = writing(mapOf(".vim/vimrc" to "set ignorecase\n"))
    val session = Session("text", emptyMap())

    assertEquals("$home/.vim/vimrc", session.load(mapOf("HOME" to home)))
  }

  /**
   * The whole point of reading `~/.vimrc`: a config written for Vim, by somebody who has never
   * heard of this fork, has to leave them better off than no config at all.
   *
   * So this is a real one rather than a tidy one - a plugin manager, `syntax on`, a colorscheme,
   * an autocommand, and options that do not exist here. None of those can work. What must work is
   * everything around them: the lines this fork *does* understand have to take effect, which means
   * a line it does not understand cannot stop the file.
   */
  @Test
  fun `test a borrowed vimrc still applies the parts this fork understands`() {
    val home = writing(
      mapOf(
        ".vimrc" to """
          set nocompatible
          filetype plugin indent on
          syntax on

          call plug#begin('~/.vim/plugged')
          Plug 'tpope/vim-surround'
          call plug#end()

          colorscheme desert
          set background=dark

          set number relativenumber
          set ignorecase smartcase
          set scrolloff=5

          autocmd BufWritePre * :%s/\s\+${'$'}//e

          let mapleader = ","
          nnoremap Q dw
        """.trimIndent() + "\n",
      ),
    )
    val session = Session("one two", emptyMap())

    assertEquals("$home/.vimrc", session.load(mapOf("HOME" to home)))
    assertTrue(injector.globalOptions().ignorecase, "'ignorecase' should be on")
    assertTrue(injector.globalOptions().smartcase, "'smartcase' should be on")

    session.type("Q")
    assertEquals("two", session.content, "the mapping below every unsupported line should work")

    // Deliberately not asserted: that the sink stayed empty. It does, but vacuously - a config is
    // run with `indicateErrors = false`, so *nothing* in it reports, and an assertion on that
    // would pass whatever the file contained. What is worth asserting is above: the lines this
    // fork understands took effect even though unsupported ones sat between them.
    assertTrue(isVimsOwnConfig("$home/.vimrc"), "startup has to be able to say this was Vim's own")
  }

  @Test
  fun `test only Vim's own config is recognised as borrowed`() {
    assertTrue(isVimsOwnConfig("/home/x/.vimrc"))
    assertTrue(isVimsOwnConfig("/home/x/_vimrc"))
    assertTrue(isVimsOwnConfig("/home/x/.vim/vimrc"), "the ~/.vim location")
    assertTrue(isVimsOwnConfig("/home/x/.config/vim/vimrc"), "the XDG location")

    assertFalse(isVimsOwnConfig("/home/x/.vimperorrc"))
    assertFalse(isVimsOwnConfig("/home/x/.ideavimrc"))
    assertFalse(isVimsOwnConfig("/home/x/.config/ideavim/ideavimrc"))
  }

  @Test
  fun `test _ideavimrc is found when the dotted name is not there`() {
    val home = writing(mapOf("_ideavimrc" to "set ignorecase\n"))
    val session = Session("text", emptyMap())

    assertEquals("$home/_ideavimrc", session.load(mapOf("HOME" to home)))
  }

  @Test
  fun `test the XDG location is searched after the home directory`() {
    val home = writing(mapOf("ideavim/ideavimrc" to "set ignorecase\n"))
    val session = Session("text", emptyMap())

    assertEquals(
      "$home/ideavim/ideavimrc",
      session.load(mapOf("HOME" to "/nowhere", "XDG_CONFIG_HOME" to home)),
    )
  }

  @Test
  fun `test IDEA_VIM_CUSTOM_VIMRC wins over everything`() {
    val home = writing(mapOf(".ideavimrc" to "set ignorecase\n", "elsewhere.vim" to "set ignorecase\n"))
    val session = Session("text", emptyMap())

    assertEquals(
      "$home/elsewhere.vim",
      session.load(mapOf("HOME" to home, "IDEA_VIM_CUSTOM_VIMRC" to "$home/elsewhere.vim")),
    )
  }

  @Test
  fun `test no config is not an error`() {
    val session = Session("text", emptyMap())
    assertNull(session.load(mapOf("HOME" to "/nowhere-at-all")))
  }

  @Test
  fun `test a broken line does not stop the rest of the config`() {
    // Vim carries on after an error in the vimrc, and a user with one typo still wants the other
    // twenty lines. An extension that refused to start over a bad line would be worse than Vim.
    val home = writing(mapOf(".ideavimrc" to "this is not a command\nnnoremap Q dw\n"))
    val session = Session("one two", emptyMap())
    session.load(mapOf("HOME" to home))

    session.type("Q")

    assertEquals("two", session.content, "the mapping after the broken line should still be set")
  }

  /**
   * `:loadkeymap`, which is only legal in a sourced file - so this is the only place it can be
   * tested from, and the reason it stayed on the unbuilt list after every key had been swept.
   *
   * Everything below the command is its argument, all the way to the end of the file. Each row
   * becomes a language mapping, which is what `'keymap'` is built out of.
   */
  @Test
  fun `test loadkeymap in the config registers language mappings`() {
    val home = writing(
      mapOf(
        ".ideavimrc" to "loadkeymap\n\" a comment row\na b\nc d\n",
      ),
    )
    val session = Session("one two", emptyMap())

    session.load(mapOf("HOME" to home))

    assertEquals(emptyList(), session.sink.errors, "the config should have run without complaint")
    val mapping = injector.keyGroup.getKeyMapping(MappingMode.LANG)
    assertTrue(mapping.hasmapto(injector.parser.parseKeys("b")), "`a` should map to `b`")
    assertTrue(mapping.hasmapto(injector.parser.parseKeys("d")), "`c` should map to `d`")
  }
}

internal val nodeFs: dynamic = requireModule("fs")
internal val nodeOs: dynamic = requireModule("os")
internal val nodePath: dynamic = requireModule("path")

internal fun requireModule(module: String): dynamic = js("require")(module)

internal var directoryCount = 0

internal fun temporaryDirectory(): String {
  // Named by a counter rather than at random: workflow scripts aside, a test that picks a fresh
  // name each run leaves a trail of directories behind it.
  val base = nodePath.join(nodeOs.tmpdir(), "ideavim-vimrc-test-${directoryCount++}") as String
  nodeFs.rmSync(base, js("({recursive: true, force: true})"))
  nodeFs.mkdirSync(base, js("({recursive: true})"))
  return base
}

internal fun writeFile(path: String, content: String) {
  nodeFs.mkdirSync(nodePath.dirname(path), js("({recursive: true})"))
  nodeFs.writeFileSync(path, content)
}
