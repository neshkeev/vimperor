/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.Options
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.options
import com.maddyhome.idea.vim.options.NumberOption
import com.maddyhome.idea.vim.options.Option
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.options.OptionDeclaredScope.GLOBAL
import com.maddyhome.idea.vim.options.OptionDeclaredScope.LOCAL_TO_BUFFER
import com.maddyhome.idea.vim.options.OptionDeclaredScope.LOCAL_TO_WINDOW
import com.maddyhome.idea.vim.options.StringOption
import com.maddyhome.idea.vim.options.ToggleOption
import com.maddyhome.idea.vim.options.UnsignedNumberOption
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType

/**
 * The Vim options this host adds to the engine's.
 *
 * Two kinds, and the difference is the whole of this file.
 *
 * [relativenumber] is *implemented*: it and the engine's `'number'` decide what the gutter shows,
 * through `TextEditorOptions.lineNumbers`, which VS Code lets an extension write per editor. That
 * is exactly what a window-local Vim option is, so the two ideas line up with nothing left over.
 *
 * Everything below it is *accepted*. `set expandtab` and `set laststatus=2` name things VS Code has
 * already decided - indentation it resolves per language and per file, a status bar it draws
 * itself - and an option that is not declared is not ignored, it is `E518: Unknown option`. A
 * `~/.vimrc` sourced from an `.ideavimrc` is thirty lines of these, so the choice is between a
 * config that loads and a config that reports a wall of errors and stops being read. Vim's own
 * answer to the same problem is `'compatible'`: an option that exists so that scripts can set it.
 *
 * The cost is honest and worth writing down: `:set expandtab?` will answer with what was set rather
 * than with what the editor is doing. IdeaVim does the same thing for the same reason - see
 * `IjOptions`, whose first group is headed "Vim options that are implemented purely by existing
 * IntelliJ features and not used by vim-engine". This is that group for VS Code, and it is longer
 * because a VS Code user's `~/.vimrc` is more often a terminal Vim's.
 */
@Suppress("SpellCheckingInspection")
internal object VsCodeOptions {

  /**
   * Registers everything here, before a `~/.ideavimrc` can name it.
   *
   * Nothing else would: `:set` looks options up by name in the engine's registry, so the object has
   * to be touched deliberately, which is the same reason `IjOptions.initialise` exists.
   *
   * The properties below only *construct* their options; registering them happens here, walking
   * [declared]. Kotlin/JS initialises an object's properties in declaration order on first touch,
   * and a property that registered as a side effect of being constructed would mean anything
   * reading `VsCodeOptions.relativenumber` while the object was still initialising saw `undefined`
   * rather than an option. That is the rule that moved `VsCodeEditor.pushedSelections` and the
   * tutor's host, and it is cheaper to keep out of the way of than to meet.
   */
  fun initialise() {
    declared.forEach { Options.addOption(it) }
  }

  // ---- Implemented.

  /** `'relativenumber'`, with `'number'`, against `editor.lineNumbers`. See [applyLineNumbers]. */
  val relativenumber: ToggleOption = ToggleOption("relativenumber", LOCAL_TO_WINDOW, "rnu", false)

  // ---- Accepted. VS Code decides these, and Vim's spelling of them exists so a config loads.

  // How text is drawn. `editor.wordWrap`, `editor.renderWhitespace`, `editor.renderLineHighlight`
  // and the rest are user settings rather than per-editor ones, so writing them from a vimrc would
  // change every window rather than this one.
  val wrap: ToggleOption = ToggleOption("wrap", LOCAL_TO_WINDOW, "wrap", true)
  val linebreak: ToggleOption = ToggleOption("linebreak", LOCAL_TO_WINDOW, "lbr", false)
  val list: ToggleOption = ToggleOption("list", LOCAL_TO_WINDOW, "list", false)
  val cursorline: ToggleOption = ToggleOption("cursorline", LOCAL_TO_WINDOW, "cul", false)
  val cursorcolumn: ToggleOption = ToggleOption("cursorcolumn", LOCAL_TO_WINDOW, "cuc", false)
  val breakindent: ToggleOption = ToggleOption("breakindent", LOCAL_TO_WINDOW, "bri", false)
  val colorcolumn: StringOption = StringOption("colorcolumn", LOCAL_TO_WINDOW, "cc", "")
  val signcolumn: StringOption = StringOption("signcolumn", LOCAL_TO_WINDOW, "scl", "auto")
  val numberwidth: NumberOption = UnsignedNumberOption("numberwidth", LOCAL_TO_WINDOW, "nuw", 4)
  val conceallevel: NumberOption = UnsignedNumberOption("conceallevel", LOCAL_TO_WINDOW, "cole", 0)
  val textwidth: NumberOption = UnsignedNumberOption("textwidth", LOCAL_TO_BUFFER, "tw", 0)
  val wrapmargin: NumberOption = UnsignedNumberOption("wrapmargin", LOCAL_TO_BUFFER, "wm", 0)

  // Indentation, which this host reads from `editor.options` rather than from Vim. VS Code resolves
  // it per language and per file and can detect it from the file itself, which is a better answer
  // than one number in a vimrc - see the port's README.
  val expandtab: ToggleOption = ToggleOption("expandtab", LOCAL_TO_BUFFER, "et", false)
  val tabstop: NumberOption = UnsignedNumberOption("tabstop", LOCAL_TO_BUFFER, "ts", 8)
  val shiftwidth: NumberOption = UnsignedNumberOption("shiftwidth", LOCAL_TO_BUFFER, "sw", 8)
  val softtabstop: NumberOption = UnsignedNumberOption("softtabstop", LOCAL_TO_BUFFER, "sts", 0)
  val autoindent: ToggleOption = ToggleOption("autoindent", LOCAL_TO_BUFFER, "ai", false)
  val smartindent: ToggleOption = ToggleOption("smartindent", LOCAL_TO_BUFFER, "si", false)
  val smarttab: ToggleOption = ToggleOption("smarttab", GLOBAL, "sta", false)

  // Folding. VS Code folds by its own language-aware provider and takes no instruction about how.
  val foldmethod: StringOption = StringOption("foldmethod", LOCAL_TO_WINDOW, "fdm", "manual")
  val foldenable: ToggleOption = ToggleOption("foldenable", LOCAL_TO_WINDOW, "fen", true)
  val foldcolumn: NumberOption = UnsignedNumberOption("foldcolumn", LOCAL_TO_WINDOW, "fdc", 0)

  // Files on disk, which VS Code manages with hot exit and its own undo history.
  val encoding: StringOption = StringOption("encoding", GLOBAL, "enc", "utf-8")
  val fileencoding: StringOption = StringOption("fileencoding", LOCAL_TO_BUFFER, "fenc", "utf-8")
  val fileformat: StringOption = StringOption("fileformat", LOCAL_TO_BUFFER, "ff", "unix")
  val fileformats: StringOption = StringOption("fileformats", GLOBAL, "ffs", "unix,dos")
  val swapfile: ToggleOption = ToggleOption("swapfile", LOCAL_TO_BUFFER, "swf", true)
  val backup: ToggleOption = ToggleOption("backup", GLOBAL, "bk", false)
  val writebackup: ToggleOption = ToggleOption("writebackup", GLOBAL, "wb", true)
  val undofile: ToggleOption = ToggleOption("undofile", LOCAL_TO_BUFFER, "udf", false)
  val undodir: StringOption = StringOption("undodir", GLOBAL, "udir", ".")
  val autoread: ToggleOption = ToggleOption("autoread", GLOBAL, "ar", false)
  val autowrite: ToggleOption = ToggleOption("autowrite", GLOBAL, "aw", false)
  val hidden: ToggleOption = ToggleOption("hidden", GLOBAL, "hid", false)
  val modeline: ToggleOption = ToggleOption("modeline", LOCAL_TO_BUFFER, "ml", true)

  // The screen around the editor, all of which is VS Code's own furniture.
  val title: ToggleOption = ToggleOption("title", GLOBAL, "title", false)
  val ruler: ToggleOption = ToggleOption("ruler", GLOBAL, "ru", true)
  val laststatus: NumberOption = UnsignedNumberOption("laststatus", GLOBAL, "ls", 2)
  val showtabline: NumberOption = UnsignedNumberOption("showtabline", GLOBAL, "stal", 1)
  val termguicolors: ToggleOption = ToggleOption("termguicolors", GLOBAL, "tgc", false)
  val background: StringOption = StringOption("background", GLOBAL, "bg", "dark")
  val splitbelow: ToggleOption = ToggleOption("splitbelow", GLOBAL, "sb", false)
  val splitright: ToggleOption = ToggleOption("splitright", GLOBAL, "spr", false)
  val wildmenu: ToggleOption = ToggleOption("wildmenu", GLOBAL, "wmnu", true)
  val wildmode: StringOption = StringOption("wildmode", GLOBAL, "wim", "full")
  val shortmess: StringOption = StringOption("shortmess", GLOBAL, "shm", "filnxtToOF")
  val belloff: StringOption = StringOption("belloff", GLOBAL, "bo", "all")

  // Behaviour a terminal needs and an editor does not.
  val compatible: ToggleOption = ToggleOption("compatible", GLOBAL, "cp", false)
  val paste: ToggleOption = ToggleOption("paste", GLOBAL, "paste", false)
  val backspace: StringOption = StringOption("backspace", GLOBAL, "bs", "indent,eol,start")
  val lazyredraw: ToggleOption = ToggleOption("lazyredraw", GLOBAL, "lz", false)
  val ttyfast: ToggleOption = ToggleOption("ttyfast", GLOBAL, "tf", true)
  val updatetime: NumberOption = UnsignedNumberOption("updatetime", GLOBAL, "ut", 4000)
  val ttimeoutlen: NumberOption = UnsignedNumberOption("ttimeoutlen", GLOBAL, "ttm", 50)
  val report: NumberOption = UnsignedNumberOption("report", GLOBAL, "report", 2)
  val spell: ToggleOption = ToggleOption("spell", LOCAL_TO_WINDOW, "spell", false)
  val spelllang: StringOption = StringOption("spelllang", LOCAL_TO_BUFFER, "spl", "en")

  /**
   * Every option above, in one list, so that [initialise] cannot miss one.
   *
   * Declared last on purpose: it reads the properties, so it has to come after all of them.
   */
  private val declared: List<Option<out VimDataType>> = listOf(
    relativenumber,
    wrap,
    linebreak,
    list,
    cursorline,
    cursorcolumn,
    breakindent,
    colorcolumn,
    signcolumn,
    numberwidth,
    conceallevel,
    textwidth,
    wrapmargin,
    expandtab,
    tabstop,
    shiftwidth,
    softtabstop,
    autoindent,
    smartindent,
    smarttab,
    foldmethod,
    foldenable,
    foldcolumn,
    encoding,
    fileencoding,
    fileformat,
    fileformats,
    swapfile,
    backup,
    writebackup,
    undofile,
    undodir,
    autoread,
    autowrite,
    hidden,
    modeline,
    title,
    ruler,
    laststatus,
    showtabline,
    termguicolors,
    background,
    splitbelow,
    splitright,
    wildmenu,
    wildmode,
    shortmess,
    belloff,
    compatible,
    paste,
    backspace,
    lazyredraw,
    ttyfast,
    updatetime,
    ttimeoutlen,
    report,
    spell,
    spelllang,
  )
}

/**
 * The gutter, from `'number'` and `'relativenumber'`.
 *
 * Vim has four states and VS Code has three. `'relativenumber'` on its own puts a `0` on the
 * caret's line; VS Code's `Relative` puts the absolute number there, which is Vim with both options
 * set. There is no way to ask for the fourth, so both spellings of relative land on `Relative` -
 * and the one that is lost is the one nobody sets on purpose, because the absolute number of the
 * line you are on is the useful half of the pair.
 */
internal fun applyLineNumbers(editor: VimEditor) {
  val vsCode = editor as? VsCodeEditor ?: return
  val relative = injector.optionGroup
    .getOptionValue(VsCodeOptions.relativenumber, OptionAccessScope.EFFECTIVE(editor))
    .asBoolean()
  val absolute = injector.options(editor).number

  val style = when {
    relative -> TextEditorLineNumbersStyle.Relative
    absolute -> TextEditorLineNumbersStyle.On
    else -> TextEditorLineNumbersStyle.Off
  }
  // Only on a change. This runs after every keystroke - see [VimHost.handle] - and writing an
  // editor option is a round trip to VS Code, which is not something to do fifty times for a typed
  // word.
  if (style == vsCode.lastLineNumbers) return
  vsCode.lastLineNumbers = style
  vsCode.nativeEditor.options.lineNumbers = style
}

/**
 * Starts watching the two options, and is called once the injector is in place.
 *
 * The listener is the engine's `EffectiveOptionValueChangeListener`, which is told which editor is
 * affected - so a `:setlocal rnu` reaches the one window it was typed in, which is what
 * local-to-window means and what a global `:set` would have got wrong.
 *
 * It is not the only thing that applies them. [applyLineNumbers] also runs when an editor is
 * registered, so a window that appears after the value was set is not left with the gutter it was
 * born with, and after every keystroke, so nothing depends on a notification arriving. It writes
 * only when its answer has changed, which is what makes running it that often free.
 */
internal fun watchLineNumbers() {
  injector.optionGroup.addEffectiveOptionValueChangeListener(Options.number) { applyLineNumbers(it) }
  injector.optionGroup.addEffectiveOptionValueChangeListener(VsCodeOptions.relativenumber) { applyLineNumbers(it) }
}
