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
    fun run(line: String, on: TextEditor = fake) {
      // Back to Normal first: a line that leaves the editor somewhere else - `:startinsert` does -
      // would otherwise swallow the `:` of the next one, and a probe that never ran reports no
      // error, which reads as a command that works.
      host.key(on, "<Esc>")
      host.type(on, ":")
      line.forEach { host.type(on, it.toString()) }
      host.key(on, "<CR>")
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

  // ---- `'syntax'` and `'filetype'`, which are local to the *buffer*.

  /**
   * `:set syntax=html` names one buffer, and every other document keeps the language it had.
   *
   * Reported from a real window: `:set syntax` in an untitled document turned every open file into
   * that language, saved ones included. `:set` on a local option writes the local value *and* the
   * global one - Vim does the same, so that a new buffer inherits it - and this host keyed
   * buffer-local storage by the `VsCodeEditor` wrapper. VS Code hands out a new `TextEditor`
   * whenever a hidden tab is shown, so the wrapper is replaced, so the engine was told the buffer
   * had never been initialised, so it copied every local-to-buffer option from the globals. Each tab
   * became `html` as it was looked at. See `EditorKeyedStorage`.
   */
  @Test
  fun `test the syntax of one document does not reach another`() {
    val session = Session()
    val other = FakeEditor("another file", path = "/test/other.txt")
    session.host.editorFor(other)

    session.run("set syntax=html")

    assertEquals("html", session.fake.document.languageId, "the document it was typed in")
    assertEquals("plaintext", other.document.languageId, "and no other")
  }

  /**
   * The engine asks which editors show a buffer before it reports a local-to-buffer change.
   *
   * This host used to answer "all of them", so every `:set syntax` ran the language, filetype and
   * indent listeners against every open file. That was wasted work rather than a wrong answer -
   * each editor then read its own effective value and mostly did nothing - which is why it survived
   * so long, and why it is asserted here directly rather than through a side effect.
   */
  @Test
  fun `test only the editors showing a buffer are told its local options changed`() {
    val session = Session()
    val mine = session.host.editorFor(session.fake)
    val other = session.host.editorFor(FakeEditor("another file", path = "/test/other.txt"))

    assertEquals(listOf(mine), injector.editorGroup.getEditors(mine.document).toList())
    assertEquals(listOf(other), injector.editorGroup.getEditors(other.document).toList())
  }

  /**
   * The exact report: an untitled buffer, then a switch to a file that was open all along.
   *
   * A tab *switch* is what makes this visible rather than a tab open, because VS Code hands the
   * host a new `TextEditor` object for a document it has had open the whole time - and that is what
   * used to read as a new buffer.
   */
  @Test
  fun `test a tab switched to after a set syntax keeps its own language`() {
    val session = Session()
    val titled = FakeEditor("saved file", path = "/test/saved.txt")
    session.host.editorFor(titled)
    val untitled = FakeEditor("pasted text", path = "Untitled-1", untitled = true)
    session.host.editorFor(untitled)

    session.run("set syntax=html", on = untitled)
    // The switch back: the same document, a new `TextEditor`, whose wrapper the host replaces.
    val shownAgain = FakeEditor("saved file", path = "/test/saved.txt")
    session.host.editorFor(shownAgain)

    assertEquals("html", untitled.document.languageId, "the buffer that asked for it")
    assertEquals("plaintext", shownAgain.document.languageId, "the one that did not")
  }

  /** ...and the buffer that *did* ask keeps it when its own editor is replaced. */
  @Test
  fun `test a document keeps its syntax when its editor is replaced`() {
    val session = Session()
    session.run("set syntax=html")

    val again = FakeEditor("one two", path = "/test/buffer.txt")
    val editor = session.host.editorFor(again)

    assertEquals(
      "html",
      injector.optionGroup.getOptionValue(VsCodeOptions.syntax, OptionAccessScope.EFFECTIVE(editor))
        .toVimString().value,
      "the buffer is the same buffer, whatever object VS Code handed over",
    )
  }

  /**
   * VS Code detecting the language of an untitled buffer must not count as closing it.
   *
   * The whole of the second report, and the reason the first fix was not enough.
   * `onDidCloseTextDocument` fires for a *language change* as well as for a close - VS Code's own
   * words are "disposed or when the language id of a text document has been changed" - and VS Code
   * detects the language of an untitled buffer by itself. So typing Java into a new tab fired this
   * host's `forgetDocument`, the buffer's options went, and switching back after `:set syntax=sql`
   * in another tab re-initialised them from the global value that `:set` had just written.
   *
   * `isClosed` separates the two exactly rather than by guess: a real close disposes the document
   * and drops it from the collection before firing, a language change fires the same object out and
   * straight back in.
   */
  @Test
  fun `test a language change is not a close`() {
    val session = Session()
    val java = FakeEditor("class Hello {}", path = "Untitled-1", untitled = true)
    session.host.editorFor(java)
    session.run("setlocal shiftwidth=7", on = java)

    // What VS Code's own language detection does to an untitled buffer: the document is handed to
    // `onDidCloseTextDocument` and then straight back, without ever being disposed.
    java.document.languageId = "java"
    session.host.forgetDocument(java.document)

    assertEquals(
      7,
      injector.optionGroup.getOptionValue(VsCodeOptions.shiftwidth, OptionAccessScope.EFFECTIVE(session.host.editorFor(java)))
        .toVimNumber().value,
      "the buffer is still open and still the same buffer",
    )
  }

  /**
   * ...and the report end to end: two untitled tabs, a detected language, and `:set syntax` in the
   * other one.
   */
  @Test
  fun `test a detected language survives a set syntax in another tab`() {
    val session = Session()
    val java = FakeEditor("class Hello {}", path = "Untitled-1", untitled = true)
    session.host.editorFor(java)
    // VS Code works out that this is Java, which reaches the host as a close and an open.
    java.document.languageId = "java"
    session.host.forgetDocument(java.document)

    val sql = FakeEditor("select * from table", path = "Untitled-2", untitled = true)
    session.host.editorFor(sql)
    session.run("set syntax=sql", on = sql)

    // Switching back: a new `TextEditor` for a document that was open the whole time.
    val javaAgain = FakeEditor("class Hello {}", path = "Untitled-1", untitled = true)
    javaAgain.document.languageId = "java"
    session.host.editorFor(javaAgain)

    assertEquals("sql", sql.document.languageId, "the tab that asked")
    assertEquals("java", javaAgain.document.languageId, "and the one that did not")
  }

  /**
   * An indent option is local to the buffer too, and was being reset by the same path.
   *
   * Worth its own test because it is the half nobody would have reported: `'shiftwidth'` is usually
   * the same in every file, so a buffer quietly taking the global value looks like nothing at all.
   */
  @Test
  fun `test a buffer local indent survives its editor being replaced`() {
    val session = Session()
    session.run("setlocal shiftwidth=7")

    val again = FakeEditor("one two", path = "/test/buffer.txt")
    val editor = session.host.editorFor(again)

    assertEquals(
      7,
      injector.optionGroup.getOptionValue(VsCodeOptions.shiftwidth, OptionAccessScope.EFFECTIVE(editor))
        .toVimNumber().value,
    )
  }

  /**
   * Closing a file takes its buffer with it, so reopening one starts from the global values.
   *
   * Vim's rule rather than a tidy-up: `:bdelete` and a fresh `:edit` give a new buffer. Keeping the
   * old local values would answer for a file out of a memory of the last time it was open, which is
   * the mistake `forgetWordWrap` exists to avoid one scope down.
   */
  @Test
  fun `test a reopened file does not keep the last one's buffer options`() {
    val session = Session()
    session.run("setlocal shiftwidth=7")

    // `isClosed` is what a real close looks like; without it this is a language change, which the
    // host is right to leave alone. See [VimHost.forgetDocument].
    session.fake.document.isClosed = true
    session.host.forgetDocument(session.fake.document)
    val reopened = FakeEditor("one two", path = "/test/buffer.txt")
    val editor = session.host.editorFor(reopened)

    assertEquals(
      0,
      injector.optionGroup.getOptionValue(VsCodeOptions.shiftwidth, OptionAccessScope.EFFECTIVE(editor))
        .toVimNumber().value,
      "back to the global value, which `setlocal` never touched",
    )
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

  // `'wrap'`, which is the third option here that does something rather than being accepted - and
  // the only one that is not a value this host keeps. See [WordWrapSettingMapper]: it is the
  // editor's own transient wrap, driven by counted presses of `editor.action.toggleWordWrap`, and
  // no settings file is written at all.

  /** Every press of the wrap toggle the host asked VS Code for. */
  private fun Session.toggles(): List<String> = dispatched.filter { it == VsCodeCommands.TOGGLE_WORD_WRAP }

  /**
   * `:set wrap` presses the toggle once, and writes nothing.
   *
   * The first version of this option ran the same command and kept a belief about which way the
   * editor was, which is unfixable: one wrong belief and every command means its opposite, and it
   * did exactly that in a real window. The second wrote `editor.wordWrap`, which is absolute but is
   * one setting for every editor showing the language, so two tabs could not disagree.
   *
   * The toggle is not a coin flip once its rule is known: with no override it sets one to the
   * opposite of the setting. The setting says `off` here, so one press is the whole of it.
   */
  @Test
  fun `test set wrap toggles the editor's own wrap`() {
    try {
      val session = Session()
      val editor = session.host.editorFor(session.fake)
      session.dispatched.clear()
      forget()

      session.run("set wrap")

      assertEquals(emptyList(), session.errors)
      assertEquals(listOf(VsCodeCommands.TOGGLE_WORD_WRAP), session.toggles())
      assertTrue(wordWrapNow(editor), "the file wraps now")
      assertEquals(emptyList(), writes(), "and settings.json is not touched")
    } finally {
      reset()
    }
  }

  /**
   * Turning it off again clears the override rather than setting a second one.
   *
   * Which is one press, not two, because the setting underneath already says `off` - and knowing
   * that is the difference between counting presses and guessing at them.
   */
  @Test
  fun `test set nowrap clears the override when the setting already agrees`() {
    try {
      val session = Session()
      val editor = session.host.editorFor(session.fake)
      session.run("set wrap")
      session.dispatched.clear()

      session.run("set nowrap")

      assertEquals(listOf(VsCodeCommands.TOGGLE_WORD_WRAP), session.toggles())
      assertEquals(false, wordWrapNow(editor))
    } finally {
      reset()
    }
  }

  /**
   * ...and two presses when it does not, which is the case the arithmetic exists for.
   *
   * An override is in place and the setting has moved underneath it - another window's `:set`, or
   * the user editing `settings.json`. Clearing the override lands on the setting, which is the
   * wrong answer, so a second press sets a fresh override to the opposite of it.
   */
  @Test
  fun `test two presses when clearing the override would land on the wrong answer`() {
    try {
      val session = Session()
      val editor = session.host.editorFor(session.fake)
      session.run("set wrap")
      // The setting moves underneath: what the override was hiding now says `on` too.
      js("require('vscode').workspace.languageConfiguration.plaintext = { editor: { wordWrap: 'on' } }")
      session.dispatched.clear()

      session.run("set nowrap")

      assertEquals(2, session.toggles().size, "clear it, then set one the other way")
      assertEquals(false, wordWrapNow(editor))
    } finally {
      js("delete require('vscode').workspace.languageConfiguration.plaintext")
      reset()
    }
  }

  /**
   * The wrap is per file, which is the whole point and is what was reported.
   *
   * `:set wrap` in one tab, `:set nowrap` in the next, and the first one is still wrapping when you
   * come back to it. Under the old design it was not: `'wrap'` was pushed at `editor.wordWrap`,
   * which is one setting for every editor showing the language, so the tab touched last took the
   * answer away from the other one.
   */
  @Test
  fun `test the wrap is per file`() {
    try {
      val session = Session()
      val a = session.host.editorFor(session.fake)
      session.host.activeEditorChanged(session.fake)
      session.run("set wrap")

      val other = FakeEditor("other\nfile", path = "/test/other.txt")
      session.host.activeEditorChanged(other)
      session.run("set nowrap", on = other)

      assertEquals(false, wordWrapNow(session.host.editorFor(other)))
      session.host.activeEditorChanged(session.fake)
      assertTrue(wordWrapNow(a), "the first tab kept its own answer")
      assertEquals(emptyList(), writes(), "and neither tab wrote a settings file")
    } finally {
      reset()
    }
  }

  /**
   * A file opened later takes the wrap of the window it was opened from, which is Vim.
   *
   * `'wrap'` is local to a window and Vim copies window-local options into a new one, so `:set
   * wrap` and then opening a file means that file wraps too - and it is what makes a `set nowrap`
   * in a `~/.vimperorrc` reach every file rather than whichever window happened to be open when the
   * config ran.
   *
   * Safe in a way it was not when the value was a setting: what arrives is applied to the arriving
   * tab and reaches no other.
   */
  @Test
  fun `test a file opened later takes the wrap of the window it came from`() {
    try {
      val session = Session()
      session.host.activeEditorChanged(session.fake)
      session.run("set wrap")
      session.dispatched.clear()

      val later = FakeEditor("later\nfile", path = "/test/later.txt")
      session.host.activeEditorChanged(later)

      assertEquals(listOf(VsCodeCommands.TOGGLE_WORD_WRAP), session.toggles())
      assertTrue(wordWrapNow(session.host.editorFor(later)), "the new tab wraps, as Vim means it to")
    } finally {
      reset()
    }
  }

  /**
   * ...but opening a file does not flip its wrap when nobody asked.
   *
   * An option nobody set is [OptionValue.Default], and a window carries one into every file it
   * opens. Acting on it would mean a file that wraps because of a `[markdown]` block stopped
   * wrapping the moment it was opened, on the strength of an option's default - which is the shape
   * of the bug the previous design had, one level down.
   */
  @Test
  fun `test opening a file does not flip a wrap nobody asked about`() {
    val byLanguage = js("require('vscode').workspace.languageConfiguration")
    byLanguage["plaintext"] = js("({ editor: { wordWrap: 'on' } })")
    try {
      val session = Session()
      session.host.activeEditorChanged(session.fake)
      session.dispatched.clear()
      forget()

      val later = FakeEditor("later\nfile", path = "/test/later.txt")
      session.host.activeEditorChanged(later)

      assertEquals(emptyList(), session.toggles(), "nobody asked, so nothing is pressed")
      assertTrue(wordWrapNow(session.host.editorFor(later)), "and the file goes on wrapping")
      assertEquals(emptyList(), writes())
    } finally {
      byLanguage["plaintext"] = undefined
      reset()
    }
  }

  /**
   * `Alt+Z` is seen rather than missed, and that is what makes the one bit of belief affordable.
   *
   * The editor's real wrap is not something an extension can read. It *is* something a `when`
   * clause can read, so the manifest binds the chord twice - under `editorWordWrap` and under its
   * negation - and what arrives here is the answer. Without it a single `Alt+Z` would invert every
   * later `:set wrap` in that file for as long as it stayed open.
   */
  @Test
  fun `test Alt+Z is read back rather than lost`() {
    try {
      val session = Session()
      val editor = session.host.editorFor(session.fake)
      session.run("set wrap")
      assertTrue(wordWrapNow(editor))

      // The user presses Alt+Z while the file is wrapping. The setting says `off`, so what VS Code
      // is about to do is clear the override.
      session.host.wordWrapToggled(session.fake, wasWrapping = true)
      assertEquals(false, wordWrapNow(editor), "the override is gone, so the setting answers")

      session.dispatched.clear()
      session.run("set wrap")
      assertEquals(listOf(VsCodeCommands.TOGGLE_WORD_WRAP), session.toggles(), "and `:set wrap` works again")
    } finally {
      reset()
    }
  }

  /**
   * A file VS Code has closed forgets its wrap, because VS Code forgot it too.
   *
   * The transient property is disposed with the model, so what this host remembers has to go at the
   * same moment - or a file reopened would be answered for out of a memory of the last time it was
   * open, and every press counted from it would be one out.
   */
  @Test
  fun `test a closed file forgets its wrap`() {
    try {
      val session = Session()
      session.run("set wrap")
      assertTrue(wordWrapNow(session.host.editorFor(session.fake)))

      session.fake.document.isClosed = true
      session.host.forget(session.fake)

      val again = FakeEditor("one two\nthree four")
      assertEquals(false, wordWrapNow(session.host.editorFor(again)), "back to what the setting says")
    } finally {
      reset()
    }
  }

  /**
   * A wrap asked for while the file is not on screen waits until it is.
   *
   * `editor.action.toggleWordWrap` is an editor action: it runs against whatever has focus and
   * cannot be pointed at another tab. A `~/.vimperorrc` read before VS Code has focused anything is
   * the case this exists for.
   */
  @Test
  fun `test a wrap asked for off screen waits for the file`() {
    try {
      val session = Session()
      val other = FakeEditor("other\nfile", path = "/test/other.txt")
      val otherEditor = session.host.editorFor(other)
      session.host.activeEditorChanged(session.fake)
      session.dispatched.clear()

      setWordWrap(otherEditor, wrapping = true)
      assertEquals(emptyList(), session.toggles(), "not the file on screen, so not yet")
      assertTrue(wordWrapNow(otherEditor), "though `:set wrap?` answers with what was asked for")

      session.host.activeEditorChanged(other)

      assertEquals(listOf(VsCodeCommands.TOGGLE_WORD_WRAP), session.toggles(), "delivered once it is")
    } finally {
      reset()
    }
  }

  /** Setting it to what it already is presses nothing: the editor is already doing it. */
  @Test
  fun `test setting wrap to what it already is presses nothing`() {
    try {
      val session = Session()
      session.run("set wrap")
      session.dispatched.clear()

      session.run("set wrap")
      session.run("set wrap")

      assertEquals(emptyList(), session.toggles())
    } finally {
      reset()
    }
  }

  /**
   * And nothing happens just because the extension loaded, or because a key was pressed.
   *
   * Vim wraps by default and VS Code does not, so an option carrying Vim's answer would turn
   * wrapping on in every editor the moment Vimperor was installed. Nothing carries an answer: the
   * option is what the editor is drawn with, so there is nothing for a keystroke to apply.
   */
  @Test
  fun `test starting up and typing leave the wrap alone`() {
    try {
      val session = Session()
      session.dispatched.clear()
      forget()

      session.host.type(session.fake, "x")
      session.host.key(session.fake, "<Esc>")

      assertEquals(emptyList(), session.toggles())
      assertEquals(emptyList(), writes())
    } finally {
      reset()
    }
  }

  /**
   * With no override, `'wrap'` is the setting - resolved for the *document*, so a `[language]`
   * block is seen.
   *
   * Without a scope VS Code answers for the window and ignores a `[markdown]` block turning word
   * wrap on, which is how people usually turn it on. `'wrap'` came out `nowrap` while the lines
   * wrapped, so `:set nowrap` agreed with itself and changed nothing.
   */
  @Test
  fun `test a language block is what the wrap is read from`() {
    val byLanguage = js("require('vscode').workspace.languageConfiguration")
    byLanguage["plaintext"] = js("({ editor: { wordWrap: 'on' } })")
    try {
      val session = Session()
      session.dispatched.clear()

      session.run("set wrap?")
      assertTrue(
        session.printed.any { it.contains("wrap") && !it.contains("nowrap") },
        "the language block should be what `'wrap'` is read from, printed: ${session.printed}",
      )

      session.run("set nowrap")
      assertEquals(listOf(VsCodeCommands.TOGGLE_WORD_WRAP), session.toggles())
      assertEquals(false, wordWrapNow(session.host.editorFor(session.fake)))
    } finally {
      byLanguage["plaintext"] = undefined
      reset()
    }
  }

  /** The same for a setting scoped to the file rather than to its language. */
  @Test
  fun `test a scoped setting is what the wrap is read from`() {
    val settings = js("require('vscode').workspace.scopedConfiguration")
    settings["/test/buffer.txt"] = js("({ editor: { wordWrap: 'on' } })")
    try {
      val session = Session()

      session.run("set wrap?")

      assertTrue(
        session.printed.any { it.contains("wrap") && !it.contains("nowrap") },
        "the option should start where the editor is, printed: ${session.printed}",
      )
    } finally {
      settings["/test/buffer.txt"] = undefined
      reset()
    }
  }

  /** What the wrap decided reaches the keystroke trace, which is the only way to see it in a window. */
  @Test
  fun `test the wrap writes a line into the trace`() {
    try {
      val session = Session()

      session.run("set wrap")

      val traced = session.host.describeState(session.fake)
      assertTrue(traced.contains("wrap: true, by 1 press"), "the presses should be traced, got: $traced")
      assertEquals(
        session.host.describeState(session.fake).contains("wrap:"),
        false,
        "and drained, so the next key does not repeat it",
      )
    } finally {
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
    js("require('vscode').workspace.workspaceConfiguration = {}")
    js("require('vscode').workspace.workspaceLanguageConfiguration = {}")
    // The language blocks too. Every write goes into one now, so a test that set the wrap left the
    // next one's editor already wrapping - and `set wrap` is not a change, so it wrote nothing and
    // the failure read as the write being broken.
    js("require('vscode').workspace.languageConfiguration = {}")
    forget()
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
      autoindent ai clipboard cb cmdheight ch comments com digraph dg foldlevel fdl gdefault gd guicursor gcr
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
      "expandtab", "tabstop", "shiftwidth", "softtabstop", "smartindent", "smarttab",
      "foldmethod", "foldenable", "foldcolumn", "encoding", "fileencoding", "fileformat",
      "fileformats", "swapfile", "backup", "writebackup", "undofile", "undodir", "autoread",
      "autowrite", "hidden", "modeline", "title", "ruler", "laststatus", "showtabline",
      "termguicolors", "background", "splitbelow", "splitright", "wildmenu", "wildmode",
      "shortmess", "belloff", "compatible", "paste", "backspace", "lazyredraw", "ttyfast",
      "updatetime", "ttimeoutlen", "report", "spell", "spelllang",
    )
  }
}
