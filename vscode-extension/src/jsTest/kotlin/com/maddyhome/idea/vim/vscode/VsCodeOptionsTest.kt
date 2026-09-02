/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.Options
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The options and commands a `~/.vimrc` is made of.
 *
 * Two different jobs, and the difference is the point of [VsCodeOptions]. `'number'` and
 * `'relativenumber'` are *implemented*: VS Code lets an extension write `lineNumbers` per editor,
 * which is exactly what a window-local Vim option is, so the two ideas meet with nothing left over.
 * The rest are *accepted*: `set expandtab` and `syntax on` name things VS Code has already decided,
 * and an option or command that is not declared is not ignored - it is `E518` or `E492`, one line
 * of red per line of config, and a config that stops being read.
 *
 * The list in [`a real vimrc loads without a single error`] is the acceptance test for that, taken
 * from the configuration this was reported against.
 */
class VsCodeOptionsTest {

  private class Session(text: String = "one two\nthree four") {
    val fake = FakeEditor(text)
    val errors: MutableList<String> = mutableListOf()
    val messages: MutableList<String> = mutableListOf()
    val host = VimHost(
      sink = object : MessageSink {
        override fun message(text: String?) { messages += text.orEmpty() }
        override fun error(text: String?) { errors += text.orEmpty() }
        override fun status(text: String?) {}
      },
    ).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    /**
     * Types the line at the `:` prompt, character by character, the way a user does.
     *
     * Not `vimscriptExecutor.execute`, which is the same command by a shorter road and misses the
     * road. `:set rnu` typed at the prompt is a keystroke, and the host applies the gutter after
     * every keystroke - a test that called the executor would have been asserting the option
     * listener alone, which is exactly the part that cannot be relied on.
     */
    fun run(line: String) {
      host.type(fake, ":")
      line.forEach { host.type(fake, it.toString()) }
      host.key(fake, "<CR>")
    }

    val lineNumbers: Int get() = fake.lineNumbers
  }

  // `'number'` and `'relativenumber'`, which are the ones that do something.

  /** Both spellings and both directions, which is `:set` doing its half. */
  @Test
  fun `test both spellings of the option are accepted`() {
    val session = Session()
    for (line in listOf("set relativenumber", "set rnu", "set nornu", "set norelativenumber", "set rnu!")) {
      session.errors.clear()
      session.run(line)
      assertEquals(emptyList(), session.errors, "`:$line` should be a Vim option this host knows")
    }
  }

  /**
   * The mapping, which is where Vim and VS Code do not quite line up.
   *
   * Vim has four states and VS Code has three. `'relativenumber'` on its own puts a `0` on the
   * caret's line and VS Code's `Relative` puts the absolute number there, which is Vim with both
   * options set - so both spellings of relative land in the same place, and the state that is lost
   * is the one nobody sets on purpose.
   *
   * Asserted through [applyLineNumbers] with the options set directly, rather than by typing
   * `:set rnu` and reading the gutter afterwards. The engine does not notify a listener for the
   * *first* set of an option that has no stored value - it reads that as initialisation, which is
   * the rule `VsCodeInjector.register` documents for `'foldlevel'` - so an end-to-end assertion
   * here passes or fails on when the option happened to be stored, which is not what is being
   * tested. That is also why the gutter is applied from three places rather than one: see
   * [watchLineNumbers].
   */
  private fun gutterFor(number: Boolean, relative: Boolean): Int {
    val session = Session()
    val editor = session.host.editorFor(session.fake)
    injector.optionGroup.setOptionValue(Options.number, OptionAccessScope.EFFECTIVE(editor), VimInt(if (number) 1 else 0))
    injector.optionGroup.setOptionValue(
      VsCodeOptions.relativenumber,
      OptionAccessScope.EFFECTIVE(editor),
      VimInt(if (relative) 1 else 0),
    )
    applyLineNumbers(editor)
    return session.lineNumbers
  }

  @Test
  fun `test relativenumber makes the gutter relative`() {
    assertEquals(TextEditorLineNumbersStyle.Relative, gutterFor(number = false, relative = true))
  }

  @Test
  fun `test number on its own numbers the lines absolutely`() {
    assertEquals(TextEditorLineNumbersStyle.On, gutterFor(number = true, relative = false))
  }

  @Test
  fun `test both together are also relative`() {
    assertEquals(TextEditorLineNumbersStyle.Relative, gutterFor(number = true, relative = true))
  }

  @Test
  fun `test neither one turns the gutter off`() {
    assertEquals(TextEditorLineNumbersStyle.Off, gutterFor(number = false, relative = false))
  }

  /**
   * A window opened later starts from the option's default, which is not what Vim does.
   *
   * `'relativenumber'` is local to a window, and this engine stores the "global" value of a
   * window-local option per window too - so `:set rnu` reaches the window it was typed in and a
   * window opened afterwards is initialised from the option's default rather than from it. Vim
   * carries the value; IdeaVim's own window-local options are carried by the tracking that
   * `startInitVimRc` turns on, which is for options a *host* maps to an IDE setting.
   *
   * Asserted rather than left to be discovered, because it is the difference between a gutter that
   * stays put and one that resets when you open a file. The fix is to register a new editor with
   * the one it was opened from as its source - `initialiseLocalOptions` takes a source editor and
   * a scenario for exactly this - which is a change to how every editor is registered and belongs
   * on its own rather than at the end of this one.
   */
  @Test
  fun `test a window opened later does not yet inherit the gutter`() {
    val session = Session()
    session.run("set relativenumber")

    val opened = FakeEditor("later\nfile")
    session.host.editorFor(opened)

    assertEquals(TextEditorLineNumbersStyle.Off, opened.lineNumbers, "known, and written down above")
  }

  /** Writing the same style twice is a round trip to VS Code that buys nothing. */
  @Test
  fun `test the gutter is only written when the answer changes`() {
    val session = Session()
    val editor = session.host.editorFor(session.fake)
    injector.optionGroup.setOptionValue(VsCodeOptions.relativenumber, OptionAccessScope.EFFECTIVE(editor), VimInt(1))
    applyLineNumbers(editor)

    session.fake.lineNumbers = -1
    applyLineNumbers(editor)

    assertEquals(-1, session.fake.lineNumbers, "nothing had changed, so nothing should have been written")
  }

  // The rest: accepted, so a config loads.

  /**
   * A real `~/.vimrc`, line by line, with nothing to report.
   *
   * Every line here was `E518: Unknown option` or `E492: Not an editor command` - twenty-eight of
   * them out of the forty-odd lines a `~/.vimrc` sourced from an `.ideavimrc` actually contains.
   */
  @Test
  fun `test a real vimrc loads without a single error`() {
    val session = Session()
    val vimrc = listOf(
      "set nu rnu",
      "set encoding=utf-8",
      "set hlsearch",
      "set incsearch",
      "set ignorecase",
      "set smartcase",
      "set nowrap",
      "set paste",
      "set tabstop=4",
      "set shiftwidth=4",
      "set expandtab",
      "syntax on",
      "set clipboard^=unnamed,unnamedplus",
      "nmap <c-n> gt",
      "filetype plugin indent on",
      "set title",
      "set undofile",
      "set undodir=/tmp/undo",
      "set undolevels=5000",
      "set nocompatible",
      "set backspace=indent,eol,start",
      "set laststatus=2",
      "set ruler",
      "set termguicolors",
      "set signcolumn=yes",
      "set cursorline",
      "set list",
      "set softtabstop=4",
      "set autoindent",
      "set smartindent",
      "set foldmethod=indent",
      "set noswapfile",
      "set nobackup",
      "set hidden",
      "set lazyredraw",
      "set updatetime=300",
      "set splitbelow",
      "set splitright",
      "set linebreak",
      "set textwidth=80",
      "set colorcolumn=80",
      "set fileformat=unix",
      "set sw=2 ts=2 et",
      "scriptencoding utf-8",
      "runtime macros/matchit.vim",
      "syntax enable",
    )

    val failed = vimrc.filter { line ->
      session.errors.clear()
      session.run(line)
      session.errors.isNotEmpty()
    }

    assertEquals(emptyList(), failed, "every one of these has to load")
  }

  @Test
  fun `test an option that really does not exist is still an error`() {
    // The comparison has to be able to fail: accepting everything would pass the test above just as
    // well, and would mean a typo in a config never got reported.
    val session = Session()
    session.run("set nosuchoptionatall")

    assertTrue(session.errors.any { "E518" in it }, "got ${session.errors}")
  }

  // The commands that go with them.

  @Test
  fun `test syntax on is accepted in silence`() {
    val session = Session()
    session.run("syntax on")

    assertEquals(emptyList(), session.errors)
    assertEquals(emptyList(), session.messages, "the line that made this necessary must not add noise of its own")
  }

  /**
   * ...and `:syntax off` says what happened, because it asked for something and did not get it.
   *
   * The two halves are deliberate. A message on every `syntax on` would be the wall of errors again
   * in a different colour; silence on `syntax off` would be this host pretending to have done
   * something.
   */
  @Test
  fun `test syntax off says that it did nothing`() {
    val session = Session()
    session.run("syntax off")

    assertEquals(emptyList(), session.errors)
    assertTrue(session.messages.any { "cannot be told to stop" in it }, "got ${session.messages}")
  }

  @Test
  fun `test filetype on is silent and filetype off is not`() {
    val session = Session()
    session.run("filetype plugin indent on")
    assertEquals(emptyList(), session.messages)

    session.run("filetype off")
    assertTrue(session.messages.any { "works out a file's language itself" in it }, "got ${session.messages}")
  }

  @Test
  fun `test colorscheme says where the theme actually lives`() {
    val session = Session()
    session.run("colorscheme desert")

    assertEquals(emptyList(), session.errors)
    assertTrue(session.messages.any { "Preferences: Color Theme" in it }, "got ${session.messages}")
  }

  @Test
  fun `test the abbreviated forms resolve`() {
    val session = Session()
    for (line in listOf("sy on", "syn on", "filet on", "colo", "hi", "ru", "lan en_US")) {
      session.errors.clear()
      session.run(line)
      assertEquals(emptyList(), session.errors, "`:$line` should resolve")
    }
  }

  /**
   * None of these shadows an option the engine already declares.
   *
   * `Options.addOption` is a map write: a name the engine had would be replaced by this host's copy
   * and the engine's behaviour would go quietly missing. The list is what the engine declared before
   * any of these were added, so the engine growing an option this host also has is a failure here
   * rather than a silent overwrite - and the fix is to delete the host's copy.
   */
  @Test
  fun `test no host option shadows one the engine already has`() {
    val engineOptions = ENGINE_OPTIONS.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
    val clashes = injector.optionGroup.getAllOptions()
      .filter { it.name in HOST_OPTIONS }
      .filter { it.name in engineOptions || it.abbrev in engineOptions }
      .map { it.name }

    assertEquals(emptyList(), clashes)
    assertTrue(HOST_OPTIONS.size > 40, "the host list looks too short: ${HOST_OPTIONS.size}")
  }

  @Test
  fun `test every host option is registered under both its names`() {
    injector = VsCodeInjector().also { it.register(VsCodeEditor(FakeEditor(""))) }
    VsCodeOptions.initialise()
    val registered = injector.optionGroup.getAllOptions()
    val byName = registered.associateBy { it.name }
    val byAbbrev = registered.associateBy { it.abbrev }

    val missing = HOST_OPTIONS.filter { it !in byName }
    assertEquals(emptyList(), missing, "declared but not registered")

    val unabbreviated = HOST_OPTIONS.mapNotNull { byName[it] }.filter { byAbbrev[it.abbrev] == null }
    assertEquals(emptyList(), unabbreviated.map { it.name }, "registered without their short form")
  }

  private companion object {
    /**
     * What the engine declares, read out of `getAllOptions` before any of this host's were added.
     *
     * Names and abbreviations together, because either colliding is the same accident.
     */
    const val ENGINE_OPTIONS = """
      clipboard cb cmdheight ch comments com digraph dg foldlevel fdl gdefault gd guicursor gcr
      history hi hlsearch hls ideastrictmode ideatracetime ignorecase ic iminsert imi inccommand icm
      incsearch is isfname isf iskeyword isk keymap kmp keymodel km langmap lmap langnoremap lnr
      langremap lrm matchpairs mps maxmapdepth mmd maxsearchcount msc messagesopt mopt more mouse
      nrformats nf number nu operatorfunc opfunc scroll scr scrolljump sj scrolloff so selection sel
      selectmode slm shell sh shellcmdflag shcf shellxescape sxe shellxquote sxq showcmd sc showmode
      smd sidescroll ss sidescrolloff siso smartcase scs startofline sol timeout to timeoutlen tm
      undolevels ul viminfo vi virtualedit ve visualbell vb whichwrap ww wrapscan ws
    """

    /** Every option [VsCodeOptions] declares, by name. */
    val HOST_OPTIONS = listOf(
      "relativenumber", "wrap", "linebreak", "list", "cursorline", "cursorcolumn", "breakindent",
      "colorcolumn", "signcolumn", "numberwidth", "conceallevel", "textwidth", "wrapmargin",
      "expandtab", "tabstop", "shiftwidth", "softtabstop", "autoindent", "smartindent", "smarttab",
      "foldmethod", "foldenable", "foldcolumn", "encoding", "fileencoding", "fileformat",
      "fileformats", "swapfile", "backup", "writebackup", "undofile", "undodir", "autoread",
      "autowrite", "hidden", "modeline", "title", "ruler", "laststatus", "showtabline",
      "termguicolors", "background", "splitbelow", "splitright", "wildmenu", "wildmode",
      "shortmess", "belloff", "compatible", "paste", "backspace", "lazyredraw", "ttyfast",
      "updatetime", "ttimeoutlen", "report", "spell", "spelllang",
    )
  }
}
