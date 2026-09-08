/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

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

    /**
     * Where `:set name?` prints, which is not where a message goes: `SetCommand` writes the option
     * and its value to `injector.outputPanel`, so a test that only watched the sink saw nothing.
     */
    val printed: MutableList<String> = mutableListOf()
    /** Every VS Code command the host asked for, which is how `'wrap'` is asserted. */
    val dispatched: MutableList<String> = mutableListOf()
    val host = VimHost(
      runCommand = { command, _, onDone -> dispatched += command; onDone(true) },
      sink = object : MessageSink {
        override fun message(text: String?) { messages += text.orEmpty() }
        override fun error(text: String?) { errors += text.orEmpty() }
        override fun status(text: String?) {}
      },
      outputPanel = OutputChannelPanelService(object : OutputChannel {
        override fun appendLine(value: String) { printed += value }

        @Suppress("OVERRIDING_EXTERNAL_FUN_WITH_OPTIONAL_PARAMS")
        override fun show(preserveFocus: Boolean) {}
        override fun dispose() {}
      }),
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
      // Back to Normal first: a line that leaves the editor somewhere else - `:startinsert` does -
      // would otherwise swallow the `:` of the next one, and a probe that never ran reports no
      // error, which reads as a command that works.
      host.key(fake, "<Esc>")
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
   * The config runs before any file is open, and the first file opened is numbered by it.
   *
   * This is the report, in the order it happens. VS Code activates the extension before it restores
   * a window's editors, so `window.activeTextEditor` is usually null when the `.ideavimrc` runs and
   * the config is evaluated against the fallback window - a hidden editor that exists so that
   * options have somewhere to live when nothing is open. Vim does not need one because it always
   * has a window; this host needs the first real editor to inherit from it, which is what the
   * `FALLBACK` scenario is for and what was missing.
   */
  @Test
  fun `test a file opened after the config is numbered by it`() {
    val host = VimHost().also { it.start() }
    val startup = host.startupEditor(null)

    injector.optionGroup.startInitVimRc()
    injector.vimscriptExecutor.execute(
      "set nu rnu",
      startup,
      VsCodeExecutionContext,
      skipHistory = true,
      indicateErrors = true,
    )
    injector.optionGroup.endInitVimRc()

    val opened = FakeEditor("a file\nopened after", path = "/test/after.txt")
    host.editorFor(opened)

    assertEquals(TextEditorLineNumbersStyle.Relative, opened.lineNumbers)
  }

  /**
   * A file opened later is numbered the same way, which is what `set nu rnu` in a config means.
   *
   * Two things had to change for this. `'relativenumber'` is local to a window, and every editor
   * used to be initialised to the option *defaults* - so the config numbered the window it was read
   * in and no other. Vim carries window-local options from the window you opened from, and
   * evaluates the config in the context of the first window; this host has no window at activation,
   * so the config runs against the fallback window and `FALLBACK` is the scenario that carries what
   * it set into the first real one. `NEW` carries them on from there.
   */
  @Test
  fun `test a file opened later is numbered the same way`() {
    val session = Session()
    session.run("set relativenumber")

    val opened = FakeEditor("later\nfile", path = "/test/later.txt")
    session.host.editorFor(opened)

    assertEquals(TextEditorLineNumbersStyle.Relative, opened.lineNumbers)
  }

  /**
   * ...and so is the same file reopened in a new editor object.
   *
   * VS Code hands out a new `TextEditor` for the same document more often than is obvious - moving
   * a file to a split is enough - and the host replaces its wrapper when that happens. The old one
   * used to be retired *before* the new one was registered, which left the fallback window as the
   * only editor to inherit from, and its options are the defaults.
   */
  @Test
  fun `test a replaced editor keeps the gutter`() {
    val session = Session()
    session.run("set relativenumber")

    val again = FakeEditor("one two\nthree four")
    session.host.editorFor(again)

    assertEquals(TextEditorLineNumbersStyle.Relative, again.lineNumbers)
  }

  /** Writing the same style twice is a round trip to VS Code that buys nothing. */
  @Test
  fun `test the gutter is only written when the answer changes`() {
    val session = Session()
    val editor = session.host.editorFor(session.fake)
    injector.optionGroup.setOptionValue(VsCodeOptions.relativenumber, OptionAccessScope.EFFECTIVE(editor), VimInt(1))
    applyLineNumbers(editor)

    val writes = session.fake.lineNumberWrites
    applyLineNumbers(editor)

    assertEquals(writes, session.fake.lineNumberWrites, "the editor already showed it; nothing to write")
  }

  /**
   * A gutter VS Code turned off on its own account is put back.
   *
   * This is the bug the check above used to hide. `TextEditorOptions` belong to VS Code and it
   * resets them without asking - re-showing an editor that was hidden does it, and so does a change
   * to the `editor.lineNumbers` setting. The old check compared the answer against *what this host
   * last wrote*, so once that happened it went on saying "already relative" while the gutter sat
   * empty, and never wrote again for the life of that editor. Comparing against what the editor is
   * showing cannot drift from the editor.
   */
  @Test
  fun `test a gutter VS Code reset by itself is put back`() {
    val session = Session()
    val editor = session.host.editorFor(session.fake)
    injector.optionGroup.setOptionValue(VsCodeOptions.relativenumber, OptionAccessScope.EFFECTIVE(editor), VimInt(1))
    applyLineNumbers(editor)
    assertEquals(TextEditorLineNumbersStyle.Relative, session.fake.lineNumbers)

    // VS Code, not this host.
    session.fake.lineNumbers = TextEditorLineNumbersStyle.Off

    applyLineNumbers(editor)

    assertEquals(TextEditorLineNumbersStyle.Relative, session.fake.lineNumbers)
  }

  /** ...and the next keystroke is enough to notice, because that is when the host looks. */
  @Test
  fun `test the next keystroke puts it back`() {
    val session = Session()
    session.run("set relativenumber")
    assertEquals(TextEditorLineNumbersStyle.Relative, session.lineNumbers)

    session.fake.lineNumbers = TextEditorLineNumbersStyle.Off
    session.host.key(session.fake, "<Esc>")

    assertEquals(TextEditorLineNumbersStyle.Relative, session.lineNumbers)
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

  // The three IdeaVim options this host can answer. The other eleven `IjOptions` declares describe
  // IDE behaviour VS Code has no analogue for - `ideamarks` wants bookmarks, `idearefactormode` a
  // refactoring template, `lookupkeys` a completion popup an extension can read.

  /**
   * `&ide` names the editor, which is what a config shared with a JetBrains IDE asks it for.
   *
   * `if &ide =~? 'clion'` is in IdeaVim's own documentation, and a `~/.ideavimrc` that Vimperor also
   * reads wants the same question answered rather than `E518`.
   */
  @Test
  fun `test the ide option names the editor`() {
    val session = Session()
    session.run("set ide?")

    assertEquals(emptyList(), session.errors)
    assertTrue(
      session.printed.any { it.contains("Visual Studio Code") },
      "`:set ide?` should name the editor, printed: ${session.printed}",
    )
  }

  /** And it is settable, as IdeaVim's is - nothing else reads it, so there is nothing to break. */
  @Test
  fun `test the ide option can be set`() {
    val session = Session()
    session.run("set ide=Cursor")
    session.run("set ide?")

    assertEquals(emptyList(), session.errors)
    assertTrue(session.printed.any { it.contains("Cursor") }, "printed: ${session.printed}")
  }

  /**
   * `'ideawrite'` decides whether `:w` saves this file or every open one.
   *
   * IdeaVim's default is `all`, and this asserts the switch rather than the saving: what reaches VS
   * Code is one of two command ids, and which one is the whole of the option.
   */
  @Test
  fun `test ideawrite chooses between saving one file and saving all`() {
    val session = Session()
    assertEquals(false, writesEveryFile(), "the default here is Vim's, not IdeaVim's")

    session.run("set ideawrite=all")
    assertEquals(emptyList(), session.errors)
    assertTrue(writesEveryFile())

    session.run("set ideawrite=file")
    assertEquals(false, writesEveryFile())
  }

  /** A value the option does not have is an error, not a silent third state. */
  @Test
  fun `test ideawrite rejects a value it does not have`() {
    val session = Session()
    session.run("set ideawrite=sometimes")

    assertTrue(session.errors.isNotEmpty(), "an invalid value should report")
    assertEquals(false, writesEveryFile(), "and should leave the option alone")
  }

  /**
   * `'ideastatusicon'` decides what the mode indicator does.
   *
   * IdeaVim's option is about a clickable Vim icon; this host has the mode indicator instead, and
   * the three values carry over to it - shown, muted, gone.
   */
  @Test
  fun `test ideastatusicon chooses what the status bar shows`() {
    val session = Session()
    assertEquals(StatusIcon.SHOWN, statusIcon())

    session.run("set ideastatusicon=gray")
    assertEquals(emptyList(), session.errors)
    assertEquals(StatusIcon.GRAY, statusIcon())

    session.run("set ideastatusicon=disabled")
    assertEquals(StatusIcon.HIDDEN, statusIcon())

    session.run("set ideastatusicon=enabled")
    assertEquals(StatusIcon.SHOWN, statusIcon())
  }

  // `'wrap'`, which is the third option here that does something rather than being accepted.

  /**
   * `:set nowrap` and `:set wrap` reach VS Code's own word wrap, by *writing* it.
   *
   * The first attempt at this ran `editor.action.toggleWordWrap` and kept a belief about which way
   * the editor currently was, because VS Code will not report it. That is unfixable rather than
   * merely fragile: one wrong belief and every command means its opposite, which is what it did in
   * a real window - `:set nowrap` wrapped the file and `:set wrap` unwrapped it. A written value
   * cannot be inverted, and it reads back, so `:set wrap?` answers from the editor.
   */
  @Test
  fun `test set wrap writes VS Code's own setting`() {
    VsCodeOptions.wrapWasAsked = false
    try {
      val session = Session()
      forget()

      session.run("set wrap")
      assertEquals(emptyList(), session.errors)
      assertEquals(listOf("wordWrap=on"), writes())

      forget()
      session.run("set nowrap")
      assertEquals(listOf("wordWrap=off"), writes())
    } finally {
      reset()
    }
  }

  /**
   * A file outside every workspace folder is written for in the user's settings.
   *
   * The target is not "workspace if the window has one": a workspace setting covers the folders in
   * the workspace and nothing else, so a file opened on its own alongside a project would have been
   * written for and unaffected. Target 1 is Global, 3 is WorkspaceFolder.
   */
  /** Both directions go to the same layer, always, or they cannot undo each other. */
  @Test
  fun `test both directions are written into the language block`() {
    VsCodeOptions.wrapWasAsked = false
    try {
      val session = Session()
      forget()

      session.run("set wrap")
      session.run("set nowrap")

      val updates = js("require('vscode').workspace.updates").unsafeCast<Array<dynamic>>()
      assertEquals(listOf(true, true), updates.map { it.overrideInLanguage as Boolean })
    } finally {
      reset()
    }
  }

  @Test
  fun `test the wrap is written where it reaches this file`() {
    VsCodeOptions.wrapWasAsked = false
    try {
      val session = Session()
      forget()

      session.run("set wrap")

      val target = js("require('vscode').workspace.updates[0].target")
      assertEquals(1, target, "the stub has no folders, so nothing is inside one")
    } finally {
      reset()
    }
  }

  /** Written once per value, not once per keystroke: a settings write is a file on disk. */
  @Test
  fun `test the wrap is written once for one set`() {
    VsCodeOptions.wrapWasAsked = false
    try {
      val session = Session()
      forget()

      session.run("set wrap")
      session.host.type(session.fake, "x")
      session.host.key(session.fake, "<Esc>")

      assertEquals(listOf("wordWrap=on"), writes())
    } finally {
      reset()
    }
  }

  /**
   * A `[language]` block is what usually turns word wrap on, and it is what the read must see.
   *
   * The scope decides: a `Uri` resolves the folder's value and stops, a *document* resolves the
   * language override too. Reading by URI answered `off` for a file VS Code was wrapping, so every
   * read agreed with every write and neither described the screen - three rebuilds' worth of a
   * setting that was written, read back, and shadowed.
   */
  @Test
  fun `test a language block is read and written, not shadowed`() {
    val byLanguage = js("require('vscode').workspace.languageConfiguration")
    byLanguage["plaintext"] = js("({ editor: { wordWrap: 'on' } })")
    VsCodeOptions.wrapWasAsked = false
    try {
      val session = Session()
      forget()

      // The file wraps because of its language, so Vim's option starts there rather than at the
      // folder's `off`.
      session.run("set wrap?")
      assertTrue(
        session.printed.any { it.contains("wrap") && !it.contains("nowrap") },
        "the language block should be what `'wrap'` is read from, printed: ${session.printed}",
      )

      // And turning it off has to go into the language block, or the block shadows the write.
      session.run("set nowrap")
      assertEquals(listOf("wordWrap=off"), writes())
      assertEquals(true, js("require('vscode').workspace.updates[0].overrideInLanguage"))
      assertEquals(false, configuredWordWrap(session.host.editorFor(session.fake)))

      // And back, into the same layer. Writing the two directions to *different* layers is what
      // made `:set wrap` wrap and `:set nowrap` do nothing: the second could not undo the first.
      forget()
      session.run("set wrap")
      assertEquals(listOf("wordWrap=on"), writes())
      assertEquals(true, js("require('vscode').workspace.updates[0].overrideInLanguage"))
      assertEquals(true, configuredWordWrap(session.host.editorFor(session.fake)))
    } finally {
      byLanguage["plaintext"] = undefined
      reset()
    }
  }

  /**
   * A typed `:set` is written even when this host thinks it already wrote that value.
   *
   * The once-per-value guard is for the path that runs after every keystroke, so that a typed word
   * is not fifty settings writes. On the path where the user asked it is a way to refuse an
   * instruction, and it did: a `set nowrap` in a vimrc wrote at startup, and the `:set nowrap` the
   * user then typed was suppressed as a repeat. The read is no better a guard - it has disagreed
   * with the screen at every stage of this option's life.
   */
  @Test
  fun `test a typed set is written even if the same value was written before`() {
    VsCodeOptions.wrapWasAsked = false
    try {
      val session = Session()
      val editor = session.host.editorFor(session.fake)
      session.run("set wrap")
      // As if a vimrc had already written `nowrap` for this editor.
      editor.wroteWordWrap = false
      forget()

      session.run("set nowrap")

      assertEquals(listOf("wordWrap=off"), writes())
    } finally {
      reset()
    }
  }

  /** And it is written where it can be read back, so the option and the editor cannot drift. */
  @Test
  fun `test the wrap that was written is the wrap that is read`() {
    VsCodeOptions.wrapWasAsked = false
    try {
      val session = Session()
      val editor = session.host.editorFor(session.fake)
      session.run("set wrap")
      // Read *for this editor*: the write goes into the file's language block, and an unscoped read
      // does not resolve one. That asymmetry is the bug this option kept having, in miniature.
      assertTrue(configuredWordWrap(editor), "the setting should say the editor wraps now")

      session.run("set nowrap")
      assertEquals(false, configuredWordWrap(editor))
    } finally {
      reset()
    }
  }

  /** Setting it to what it already is writes nothing: a settings write is a file on disk. */
  @Test
  fun `test setting wrap to what it already is writes nothing`() {
    VsCodeOptions.wrapWasAsked = false
    try {
      val session = Session()
      session.run("set wrap")
      forget()

      session.run("set wrap")
      session.run("set wrap")

      assertEquals(emptyList(), writes())
    } finally {
      reset()
    }
  }

  /**
   * And nothing is written just because the extension loaded.
   *
   * Vim wraps by default and VS Code does not, so an option that started at Vim's answer would turn
   * wrapping on in every editor the moment Vimperor was installed. The option is seeded from the
   * editor's own setting instead, which is why typing keys changes nothing.
   */
  @Test
  fun `test starting up leaves the wrap alone`() {
    VsCodeOptions.wrapWasAsked = false
    try {
      val session = Session()
      forget()

      session.host.type(session.fake, "x")
      session.host.key(session.fake, "<Esc>")

      assertEquals(emptyList(), writes())
    } finally {
      reset()
    }
  }

  /**
   * An editor wrapping because of a *scoped* setting is read as wrapping.
   *
   * Without a scope VS Code answers for the window and ignores a `[markdown]` block turning word
   * wrap on, or a folder's settings - which is how people usually turn it on. `'wrap'` came out
   * `nowrap` while the lines wrapped, so `:set nowrap` agreed with itself and changed nothing.
   */
  @Test
  fun `test a language override is what the wrap is read from`() {
    val settings = js("require('vscode').workspace.scopedConfiguration")
    settings["/test/buffer.txt"] = js("({ editor: { wordWrap: 'on' } })")
    VsCodeOptions.wrapWasAsked = false
    try {
      val session = Session()
      forget()

      session.run("set wrap?")
      assertTrue(
        session.printed.any { it.contains("wrap") && !it.contains("nowrap") },
        "the option should start where the editor is, printed: ${session.printed}",
      )

      session.run("set nowrap")
      assertEquals(listOf("wordWrap=off"), writes())
    } finally {
      settings["/test/buffer.txt"] = undefined
      reset()
    }
  }

  /** Every settings write the stub recorded since [forget], as `key=value`. */
  private fun writes(): List<String> =
    js("require('vscode').workspace.updates").unsafeCast<Array<dynamic>>().map { "${it.key}=${it.value}" }

  private fun forget() {
    js("require('vscode').workspace.updates.length = 0")
  }

  private fun reset() {
    js("require('vscode').workspace.configuration.editor.wordWrap = 'off'")
    // The language blocks too. Every write goes into one now, so a test that set the wrap left the
    // next one's editor already wrapping - and `set wrap` is not a change, so it wrote nothing and
    // the failure read as the write being broken.
    js("require('vscode').workspace.languageConfiguration = {}")
    forget()
    VsCodeOptions.wrapWasAsked = false
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

  // `'filetype'` and `'syntax'`, which are one setting here.

  /**
   * `set syntax=java` colours the file as Java, which is what it was reported for.
   *
   * Vim has two options where VS Code has one setting: `'filetype'` decides which plugins and
   * indent rules apply, `'syntax'` decides only the colours. VS Code's language mode does both, so
   * `set syntax=java` gets Java's language server as well - more than Vim would have done, and what
   * a VS Code user means by it.
   */
  @Test
  fun `test setting the syntax changes the language mode`() {
    val session = Session()
    session.run("set syntax=java")

    assertEquals("java", session.fake.document.languageId)
    assertEquals(emptyList(), session.errors)
  }

  @Test
  fun `test setting the filetype does the same`() {
    val session = Session()
    session.run("set filetype=markdown")

    assertEquals("markdown", session.fake.document.languageId)
  }

  @Test
  fun `test ft is the short form`() {
    val session = Session()
    session.run("set ft=python")

    assertEquals("python", session.fake.document.languageId)
  }

  /** `'filetype'` is the file's identity, so it wins when both name something. */
  @Test
  fun `test filetype wins over syntax`() {
    val session = Session()
    session.run("set syntax=java")
    session.run("set filetype=kotlin")

    assertEquals("kotlin", session.fake.document.languageId)
  }

  @Test
  fun `test setfiletype sets it too`() {
    val session = Session()
    session.run("setfiletype rust")

    assertEquals("rust", session.fake.document.languageId)
    assertEquals(emptyList(), session.errors)
  }

  /**
   * Neither of them set leaves VS Code's own answer alone.
   *
   * The ordinary case, and the one worth asserting: a host that wrote an empty language on every
   * keystroke would take the language away from every file that had one.
   */
  @Test
  fun `test a file with neither option set keeps the language VS Code gave it`() {
    val session = Session()
    session.fake.document.languageId = "typescript"
    session.run("set nu")

    assertEquals("typescript", session.fake.document.languageId)
  }

  // Every ex command a Vim config can say.

  /**
   * Every ex command a `~/.vimrc` can use, typed at the prompt, with nothing left that `E492`.
   *
   * This list started as the measurement of what was missing - typed rather than guessed at, and
   * asserted rather than printed, so that implementing one had to come with taking it off. It is
   * the same test with nothing left on it: a command that stops resolving shows up here.
   *
   * The first eight are not commands at all but *modifiers*, and the last few change the buffer or
   * the window, which is why this runs them for real rather than only asking the registry - a name
   * that resolves to a command that then throws is not a command a config can use.
   *
   * The four `keep*` ones never reported `E492` and were the worse for it: the parser turned any
   * name starting with `k` into `:k{mark}` before asking the registry, so `:keepjumps {cmd}` set a
   * mark called `e` and ran nothing, in silence. A sweep that looks for errors cannot find that.
   */
  @Test
  fun `test every ex command a config can use resolves`() {
    val session = Session()
    val candidates = listOf(
      "silent! echo 1", "verbose set nu", "noautocmd echo 1", "lockmarks echo 1",
      "keepmarks echo 1", "keepjumps echo 1", "keeppatterns echo 1", "keepalt echo 1",
      "vertical split", "horizontal split", "topleft split", "botright split", "aboveleft split",
      "belowright split", "leftabove split", "rightbelow split", "tab split",
      "confirm echo 1", "sandbox echo 1", "noswapfile echo 1", "unsilent echo 1", "filter /x/ echo 1",
      "unlet g:x",
      "echomsg 'x'", "echoerr 'x'", "echon 'x'", "echohl None", "eval 1", "undojoin",
      "left", "right", "center", "retab", "number", "list", "z",
      "args", "argadd /tmp/x", "argdelete x", "badd /tmp/x", "bmodified", "bunload", "bwipeout",
      "drop /tmp/x", "view /tmp/x", "visual /tmp/x", "tabs", "tabfirst", "tabrewind", "tablast",
      "sbuffer", "snext", "sprevious", "sfind", "sview", "rewind",
      "fold", "foldopen", "foldclose", "redraw", "redrawstatus", "sleep 1m", "checktime",
      "startreplace", "startgreplace",
      "cexpr 'x'", "caddexpr 'x'", "lexpr 'x'", "laddexpr 'x'", "cbuffer", "lbuffer",
      "clist", "copen", "cwindow", "cclose", "llist", "lopen", "lwindow", "lclose",
      "cnext", "cprevious", "cfirst", "clast", "cc", "lnext", "lprevious", "lfirst", "llast", "ll",
      "cfile", "lfile", "smagic/x/y/", "snomagic/x/y/",
      "enew", "new", "vnew", "tabnew", "tabedit", "wincmd l", "bfirst", "blast", "pwd",
      "bufdo echo 1", "windo echo 1", "tabdo echo 1", "argdo echo 1",
      "startinsert", "doautocmd BufRead", "earlier 1", "later 1",
    )

    val missing = candidates.filter { line ->
      session.errors.clear()
      session.run(line)
      session.errors.any { "E492" in it || "Not an editor command" in it }
    }

    assertEquals(emptyList(), missing)
  }

  @Test
  fun `test the commands added for a config are all registered`() {
    val session = Session()
    for (line in listOf(
      "syntax on", "filetype plugin indent on", "setfiletype java", "colorscheme x",
      // `:highlight` is the engine's now and does something, so it is given a line that defines a
      // group rather than one that lists an undefined one - which is `E411` and would be right.
      "highlight Todo guifg=Red", "runtime x", "scriptencoding utf-8", "language en", "behave xterm",
      // `:redir` is real too, so it is closed again rather than left capturing the rest of the list.
      "packloadall", "scriptnames", "messages", "redir => x", "redir END", "mkview", "loadview",
      "sign define x",
      "profile start x", "menu", "unmenu", "diffthis", "diffoff", "cd /tmp", "lcd /tmp",
    )) {
      session.errors.clear()
      session.run(line)
      assertEquals(emptyList(), session.errors, "`:$line` should be a command this host knows")
    }
  }

  /**
   * `:silent!` guarding a line that fails, which is the whole reason a config writes it.
   *
   * The end of the road the engine's `SilentCommand` starts: this host's messages have to consult
   * the suppression for the modifier to do anything at all, and nothing about that is checked by
   * the fact that the command is registered.
   */
  @Test
  fun `test silent bang swallows the error a config was guarding against`() {
    val session = Session()
    session.run("set nosuchoptionatall")
    assertTrue(session.errors.any { "E518" in it }, "the bare line should fail: ${session.errors}")

    session.errors.clear()
    session.run("silent! set nosuchoptionatall")
    assertEquals(emptyList(), session.errors)
  }

  /** ...and `:silent` on its own still reports it, which is the only difference between the two. */
  @Test
  fun `test silent without the bang still reports the error`() {
    val session = Session()
    session.run("silent set nosuchoptionatall")

    assertTrue(session.errors.any { "E518" in it }, "got ${session.errors}")
  }

  @Test
  fun `test the modified command actually runs`() {
    val session = Session()
    session.run("silent! set relativenumber")

    assertEquals(TextEditorLineNumbersStyle.Relative, session.lineNumbers)
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
      incsearch is isfname isf iskeyword isk keyboardlayout kbl keymap kmp keymodel km langmap lmap
      langnoremap lnr
      langremap lrm matchpairs mps maxmapdepth mmd maxsearchcount msc messagesopt mopt more mouse
      nrformats nf number nu operatorfunc opfunc scroll scr scrolljump sj scrolloff so selection sel
      selectmode slm shell sh shellcmdflag shcf shellxescape sxe shellxquote sxq showcmd sc showmode
      smd sidescroll ss sidescrolloff siso smartcase scs startofline sol timeout to timeoutlen tm
      undolevels ul viminfo vi virtualedit ve visualbell vb whichwrap ww wrapscan ws
      verbose vbs makeprg mp grepprg gp errorformat efm grepformat gfm
    """

    /** Every option [VsCodeOptions] declares, by name. */
    val HOST_OPTIONS = listOf(
      "ide", "ideastatusicon", "ideawrite",
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
