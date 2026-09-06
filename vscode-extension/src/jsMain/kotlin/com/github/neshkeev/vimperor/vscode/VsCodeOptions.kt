/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.Options
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.options
import com.maddyhome.idea.vim.options.NumberOption
import com.maddyhome.idea.vim.options.Option
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.options.OptionDeclaredScope.GLOBAL
import com.maddyhome.idea.vim.options.OptionDeclaredScope.GLOBAL_OR_LOCAL_TO_BUFFER
import com.maddyhome.idea.vim.options.OptionDeclaredScope.GLOBAL_OR_LOCAL_TO_WINDOW
import com.maddyhome.idea.vim.options.OptionDeclaredScope.LOCAL_TO_BUFFER
import com.maddyhome.idea.vim.options.OptionDeclaredScope.LOCAL_TO_WINDOW
import com.maddyhome.idea.vim.options.StringOption
import com.maddyhome.idea.vim.options.ToggleOption
import com.maddyhome.idea.vim.options.UnsignedNumberOption
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt

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

  /**
   * `'ide'` - the name of the editor this is running in, which a shared config branches on.
   *
   * IdeaVim's, not Vim's, and the reason it exists is that one `~/.ideavimrc` is read by every
   * JetBrains IDE: `if &ide =~? 'clion'`. A config shared with a VS Code install wants to ask the
   * same question, and `env.appName` is the same answer - "Visual Studio Code", or "Cursor", or
   * "Code - Insiders", whichever fork the user is in.
   *
   * Settable, as it is in IdeaVim. Vim has no such option, so nothing else reads it and there is
   * nothing to break by writing it; a script that wants to pretend it is somewhere else may.
   */
  val ide: StringOption = StringOption("ide", GLOBAL, "ide", appName())

  /**
   * `'ideastatusicon'` - whether the plugin puts anything on the status bar, and how loudly.
   *
   * IdeaVim's is about a clickable Vim icon whose menu switches the plugin off. This host has no
   * such icon; what it has on the status bar is the mode indicator, and the option's three values
   * carry over to it without straining: `enabled` shows it, `disabled` hides it, and `gray` draws
   * it in the theme's own muted colour rather than the foreground. Someone who sets
   * `ideastatusicon=disabled` wants a clean status bar and gets one.
   */
  val ideastatusicon: StringOption = StringOption(
    "ideastatusicon",
    GLOBAL,
    "ideastatusicon",
    "enabled",
    boundedValues = setOf("enabled", "gray", "disabled"),
  )

  /**
   * `'ideawrite'` - whether `:w` saves this file or every open one.
   *
   * **The default here is `file` where IdeaVim's is `all`, which is a deliberate divergence.**
   * IdeaVim's reason for `all` is that IntelliJ writes your buffers on its own account anyway, so
   * `:w` meaning "save everything" costs nothing there and saves a `:wa`. VS Code does not autosave
   * unless it is asked to, so the same default would write files the user never mentioned - through
   * format-on-save, and whatever else a save is wired to. Vim's `:w` writes one buffer, and that is
   * the safer of the two to be wrong about.
   *
   * `set ideawrite=all` is IdeaVim's behaviour for anyone who wants it, and both commands were
   * already here - `workbench.action.files.save` and its `saveAll`.
   */
  val ideawrite: StringOption = StringOption(
    "ideawrite",
    GLOBAL,
    "ideawrite",
    "file",
    boundedValues = setOf("all", "file"),
  )

  /**
   * `'wrap'`, against VS Code's own word wrap. See [applyWordWrap].
   *
   * The default is read from the editor's own `wordWrap` setting rather than being Vim's `true`, and that is what
   * makes this safe to apply. Vim wraps by default and VS Code does not, so an option that started
   * at Vim's answer would turn wrapping *on* in every editor the moment the extension loaded -
   * which is not what anyone asked for. Starting where the editor already is means nothing happens
   * until the user says `:set nowrap`, and `:set wrap?` tells the truth in the meantime.
   */
  val wrap: ToggleOption = ToggleOption("wrap", LOCAL_TO_WINDOW, "wrap", configuredWordWrap())

  /**
   * Whether anything has set `'wrap'` - a vimrc, or a `:set` - so that seeding stops.
   *
   * Without it a window opened after `set nowrap` would be seeded back out of it from VS Code's own
   * setting, and the config would apply to the windows that happened to exist when it ran.
   */
  internal var wrapWasAsked: Boolean = false

  /** `'relativenumber'`, with `'number'`, against `editor.lineNumbers`. See [applyLineNumbers]. */
  val relativenumber: ToggleOption = ToggleOption("relativenumber", LOCAL_TO_WINDOW, "rnu", false)

  /**
   * `'filetype'` and `'syntax'`, both of which are VS Code's language mode. See [applyLanguage].
   *
   * Two options in Vim and one setting here, so `set ft=java` and `set syntax=java` do the same
   * thing. `set syntax=java` is the one that was reported, and it was `E518` - the *command*
   * `:syntax` had been added and the option of the same name had not, which is a distinction Vim
   * makes and a config makes use of.
   */
  val filetype: StringOption = StringOption("filetype", LOCAL_TO_BUFFER, "ft", "")
  val syntax: StringOption = StringOption("syntax", LOCAL_TO_BUFFER, "syn", "")

  /**
   * Indentation: `'expandtab'`, `'tabstop'`, `'shiftwidth'` and `'softtabstop'`.
   *
   * These sat in the accepted group under a comment saying this host "reads indentation from
   * `editor.options` rather than from Vim", because VS Code resolves it per language and per file
   * and detects it from the file itself, "which is a better answer than one number in a vimrc".
   * Every clause of that is true and the conclusion was wrong in the way `'wrap'` was wrong: a
   * `~/.vimrc` that says `set shiftwidth=2` was read, accepted, and then ignored, and `>>` went on
   * shifting by whatever VS Code had worked out.
   *
   * Both answers are kept by seeding rather than by choosing. `seedIndent` starts each editor off at
   * VS Code's answer, so a user who has set nothing gets the file's own indentation and `>>` agrees
   * with pressing Tab without Vim; `applyIndent` writes the option to the editor when the user has
   * set one. See `VsCodeIndentConfig` for how they are read.
   *
   * `'shiftwidth'` starts at 0 where Vim's own default is 8, and that is deliberate. Zero is Vim's
   * spelling of "however wide `'tabstop'` is", so the default defers to the editor along with
   * everything else here - and 8 would mean `>>` in a four-column file shifted by eight until the
   * user noticed. `'softtabstop'` is 0 for the same reason and in Vim as well.
   */
  val expandtab: ToggleOption = ToggleOption("expandtab", LOCAL_TO_BUFFER, "et", false)
  val tabstop: NumberOption = UnsignedNumberOption("tabstop", LOCAL_TO_BUFFER, "ts", 8)
  val shiftwidth: NumberOption = UnsignedNumberOption("shiftwidth", LOCAL_TO_BUFFER, "sw", 0)
  val softtabstop: NumberOption = UnsignedNumberOption("softtabstop", LOCAL_TO_BUFFER, "sts", 0)

  // ---- Accepted. VS Code decides these, and Vim's spelling of them exists so a config loads.

  // How text is drawn. `renderWhitespace`, `renderLineHighlight` and the rest are user settings
  // rather than per-editor ones, so writing them from a vimrc would change every window rather
  // than this one.
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


  // What the editor completes, formats and indents with, all of which VS Code resolves from the
  // language mode and the extensions installed for it.
  val formatoptions: StringOption = StringOption("formatoptions", LOCAL_TO_BUFFER, "fo", "tcq")
  val completeopt: StringOption = StringOption("completeopt", GLOBAL, "cot", "menu,preview")
  val complete: StringOption = StringOption("complete", LOCAL_TO_BUFFER, "cpt", ".,w,b,u,t,i")
  val pumheight: NumberOption = UnsignedNumberOption("pumheight", GLOBAL, "ph", 0)
  val omnifunc: StringOption = StringOption("omnifunc", LOCAL_TO_BUFFER, "ofu", "")
  val completefunc: StringOption = StringOption("completefunc", LOCAL_TO_BUFFER, "cfu", "")
  val dictionary: StringOption = StringOption("dictionary", GLOBAL_OR_LOCAL_TO_BUFFER, "dict", "")
  val thesaurus: StringOption = StringOption("thesaurus", GLOBAL_OR_LOCAL_TO_BUFFER, "tsr", "")
  val indentexpr: StringOption = StringOption("indentexpr", LOCAL_TO_BUFFER, "inde", "")
  val cindent: ToggleOption = ToggleOption("cindent", LOCAL_TO_BUFFER, "cin", false)
  val cinoptions: StringOption = StringOption("cinoptions", LOCAL_TO_BUFFER, "cino", "")
  val equalprg: StringOption = StringOption("equalprg", GLOBAL_OR_LOCAL_TO_BUFFER, "ep", "")
  val formatprg: StringOption = StringOption("formatprg", GLOBAL_OR_LOCAL_TO_BUFFER, "fp", "")
  val joinspaces: ToggleOption = ToggleOption("joinspaces", GLOBAL, "js", false)
  val infercase: ToggleOption = ToggleOption("infercase", LOCAL_TO_BUFFER, "inf", false)

  // Decoration Vim draws with characters and VS Code draws itself.
  val listchars: StringOption = StringOption("listchars", GLOBAL_OR_LOCAL_TO_WINDOW, "lcs", "eol:$")
  val fillchars: StringOption = StringOption("fillchars", GLOBAL_OR_LOCAL_TO_WINDOW, "fcs", "vert:|,fold:-")
  val showbreak: StringOption = StringOption("showbreak", GLOBAL_OR_LOCAL_TO_WINDOW, "sbr", "")
  val breakat: StringOption = StringOption("breakat", GLOBAL, "brk", " \t!@*-+;:,./?")
  val breakindentopt: StringOption = StringOption("breakindentopt", LOCAL_TO_WINDOW, "briopt", "")
  val showmatch: ToggleOption = ToggleOption("showmatch", GLOBAL, "sm", false)
  val matchtime: NumberOption = UnsignedNumberOption("matchtime", GLOBAL, "mat", 5)
  val statusline: StringOption = StringOption("statusline", GLOBAL_OR_LOCAL_TO_WINDOW, "stl", "")
  val tabline: StringOption = StringOption("tabline", GLOBAL, "tal", "")
  val rulerformat: StringOption = StringOption("rulerformat", GLOBAL, "ruf", "")
  val titlestring: StringOption = StringOption("titlestring", GLOBAL, "titlestring", "")
  val guifont: StringOption = StringOption("guifont", GLOBAL, "gfn", "")
  val guioptions: StringOption = StringOption("guioptions", GLOBAL, "go", "egmrLT")
  val lines: NumberOption = UnsignedNumberOption("lines", GLOBAL, "lines", 24)
  val columns: NumberOption = UnsignedNumberOption("columns", GLOBAL, "co", 80)
  val emoji: ToggleOption = ToggleOption("emoji", GLOBAL, "emo", true)

  // Folding beyond `'foldmethod'`, which VS Code does with its own language-aware provider.
  val foldlevelstart: NumberOption = NumberOption("foldlevelstart", GLOBAL, "fdls", -1)
  val foldnestmax: NumberOption = UnsignedNumberOption("foldnestmax", LOCAL_TO_WINDOW, "fdn", 20)
  val foldminlines: NumberOption = UnsignedNumberOption("foldminlines", LOCAL_TO_WINDOW, "fml", 1)
  val foldtext: StringOption = StringOption("foldtext", LOCAL_TO_WINDOW, "fdt", "foldtext()")
  val foldopen: StringOption = StringOption("foldopen", GLOBAL, "fdo", "block,hor,mark,percent,quickfix,search,tag,undo")
  val foldclose: StringOption = StringOption("foldclose", GLOBAL, "fcl", "")
  val foldignore: StringOption = StringOption("foldignore", LOCAL_TO_WINDOW, "fdi", "#")

  // Where Vim looks for things, which is the workspace and its search in VS Code.
  val path: StringOption = StringOption("path", GLOBAL_OR_LOCAL_TO_BUFFER, "pa", ".,/usr/include,,")
  val suffixesadd: StringOption = StringOption("suffixesadd", LOCAL_TO_BUFFER, "sua", "")
  val wildignore: StringOption = StringOption("wildignore", GLOBAL, "wig", "")
  val wildignorecase: ToggleOption = ToggleOption("wildignorecase", GLOBAL, "wic", false)
  val tags: StringOption = StringOption("tags", GLOBAL_OR_LOCAL_TO_BUFFER, "tag", "./tags,tags")
  val keywordprg: StringOption = StringOption("keywordprg", GLOBAL_OR_LOCAL_TO_BUFFER, "kp", "man")
  val grepprg: StringOption = StringOption("grepprg", GLOBAL_OR_LOCAL_TO_BUFFER, "gp", "grep -n $* /dev/null")
  val makeprg: StringOption = StringOption("makeprg", GLOBAL_OR_LOCAL_TO_BUFFER, "mp", "make")
  val autochdir: ToggleOption = ToggleOption("autochdir", GLOBAL, "acd", false)

  // Windows and sessions, which VS Code lays out and restores its own way.
  val switchbuf: StringOption = StringOption("switchbuf", GLOBAL, "swb", "")
  val equalalways: ToggleOption = ToggleOption("equalalways", GLOBAL, "ea", true)
  val winheight: NumberOption = UnsignedNumberOption("winheight", GLOBAL, "wh", 1)
  val winwidth: NumberOption = UnsignedNumberOption("winwidth", GLOBAL, "wiw", 20)
  val previewheight: NumberOption = UnsignedNumberOption("previewheight", GLOBAL, "pvh", 12)
  val helpheight: NumberOption = UnsignedNumberOption("helpheight", GLOBAL, "hh", 20)
  val cmdwinheight: NumberOption = UnsignedNumberOption("cmdwinheight", GLOBAL, "cwh", 7)
  val sessionoptions: StringOption = StringOption("sessionoptions", GLOBAL, "ssop", "blank,buffers,curdir,folds,help,options,tabpages,winsize")
  val viewoptions: StringOption = StringOption("viewoptions", GLOBAL, "vop", "folds,cursor,curdir")
  val scrollbind: ToggleOption = ToggleOption("scrollbind", LOCAL_TO_WINDOW, "scb", false)
  val cursorbind: ToggleOption = ToggleOption("cursorbind", LOCAL_TO_WINDOW, "crb", false)
  val diffopt: StringOption = StringOption("diffopt", GLOBAL, "dip", "internal,filler,closeoff")

  // The file itself, and the copies Vim keeps of it.
  val bomb: ToggleOption = ToggleOption("bomb", LOCAL_TO_BUFFER, "bomb", false)
  val binary: ToggleOption = ToggleOption("binary", LOCAL_TO_BUFFER, "bin", false)
  val endofline: ToggleOption = ToggleOption("endofline", LOCAL_TO_BUFFER, "eol", true)
  val readonly: ToggleOption = ToggleOption("readonly", LOCAL_TO_BUFFER, "ro", false)
  val modifiable: ToggleOption = ToggleOption("modifiable", LOCAL_TO_BUFFER, "ma", true)
  val confirm: ToggleOption = ToggleOption("confirm", GLOBAL, "cf", false)
  val backupdir: StringOption = StringOption("backupdir", GLOBAL, "bdir", ".,~/tmp,~/")
  val backupext: StringOption = StringOption("backupext", GLOBAL, "bex", "~")
  val directory: StringOption = StringOption("directory", GLOBAL, "dir", ".,~/tmp,/var/tmp,/tmp")

  // Spelling, which VS Code leaves to an extension.
  val spellfile: StringOption = StringOption("spellfile", GLOBAL_OR_LOCAL_TO_BUFFER, "spf", "")
  val spellsuggest: StringOption = StringOption("spellsuggest", GLOBAL, "sps", "best")
  val spelloptions: StringOption = StringOption("spelloptions", LOCAL_TO_BUFFER, "spo", "")
  val spellcapcheck: StringOption = StringOption("spellcapcheck", LOCAL_TO_BUFFER, "spc", "")

  // Terminal behaviour with no terminal to have it.
  val ttimeout: ToggleOption = ToggleOption("ttimeout", GLOBAL, "ttimeout", true)
  val helplang: StringOption = StringOption("helplang", GLOBAL, "hlg", "")

  /**
   * Every option above, in one list, so that [initialise] cannot miss one.
   *
   * Declared last on purpose: it reads the properties, so it has to come after all of them.
   */
  private val declared: List<Option<out VimDataType>> = listOf(
    ide,
    ideastatusicon,
    ideawrite,
    relativenumber,
    filetype,
    syntax,
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
    formatoptions,
    completeopt,
    complete,
    pumheight,
    omnifunc,
    completefunc,
    dictionary,
    thesaurus,
    indentexpr,
    cindent,
    cinoptions,
    equalprg,
    formatprg,
    joinspaces,
    infercase,
    listchars,
    fillchars,
    showbreak,
    breakat,
    breakindentopt,
    showmatch,
    matchtime,
    statusline,
    tabline,
    rulerformat,
    titlestring,
    guifont,
    guioptions,
    lines,
    columns,
    emoji,
    foldlevelstart,
    foldnestmax,
    foldminlines,
    foldtext,
    foldopen,
    foldclose,
    foldignore,
    path,
    suffixesadd,
    wildignore,
    wildignorecase,
    tags,
    keywordprg,
    grepprg,
    makeprg,
    autochdir,
    switchbuf,
    equalalways,
    winheight,
    winwidth,
    previewheight,
    helpheight,
    cmdwinheight,
    sessionoptions,
    viewoptions,
    scrollbind,
    cursorbind,
    diffopt,
    bomb,
    binary,
    endofline,
    readonly,
    modifiable,
    confirm,
    backupdir,
    backupext,
    directory,
    spellfile,
    spellsuggest,
    spelloptions,
    spellcapcheck,
    ttimeout,
    helplang,
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
  // Only on a change, because this runs after every keystroke - see [VimHost.handle] - and writing
  // an editor option is a round trip to VS Code, which is not something to do fifty times for a
  // typed word.
  //
  // Compared against what the editor is *showing* rather than against what was last written. VS
  // Code owns `TextEditorOptions` and resets them on its own account - re-showing an editor that
  // was hidden is enough, and so is a change to the `editor.lineNumbers` setting - and a memory of
  // our own writes cannot see that happen. It would go on saying "already relative" while the
  // gutter sat empty, and never write again for the life of that editor. The editor's own answer
  // cannot drift from the editor.
  if (style == vsCode.nativeEditor.options.lineNumbers) return
  vsCode.nativeEditor.options.lineNumbers = style
}

/**
 * The editor's language mode, from `'filetype'` and `'syntax'`.
 *
 * Vim has two options for what VS Code has one setting: `'filetype'` chooses which plugins and
 * indent rules apply, `'syntax'` chooses only the colours, and either can be set alone. VS Code's
 * language mode does both, so `set syntax=java` gets Java's language server as well as its
 * highlighting - more than Vim would have done, and what a VS Code user means by it. `'filetype'`
 * wins when both are set, because that is the one Vim treats as the file's identity.
 *
 * Neither being set is the ordinary case and does nothing: VS Code works the language out from the
 * file, and a host that overrode that with an empty string on every keystroke would be a bad
 * neighbour. Nothing is asked for either when the editor is already in that mode.
 */
internal fun applyLanguage(editor: VimEditor) {
  val vsCode = editor as? VsCodeEditor ?: return
  val scope = OptionAccessScope.EFFECTIVE(editor)
  val filetype = injector.optionGroup.getOptionValue(VsCodeOptions.filetype, scope).asString()
  val syntax = injector.optionGroup.getOptionValue(VsCodeOptions.syntax, scope).asString()
  val wanted = filetype.ifEmpty { syntax }
  if (wanted.isEmpty()) return

  val document = vsCode.nativeEditor.document
  if (document.languageId == wanted) return
  // Nothing waits for this. It resolves with a *new* document object for the same file, which the
  // host does not need: it keys editors by the file's identity, and re-reads a document that has
  // moved on before anything looks at one. A language VS Code does not have rejects the promise,
  // and that is the report - the same way an unknown command id is.
  languages.setTextDocumentLanguage(document, wanted).then({ }, { })
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
  injector.optionGroup.addEffectiveOptionValueChangeListener(VsCodeOptions.filetype) { applyLanguage(it) }
  injector.optionGroup.addEffectiveOptionValueChangeListener(VsCodeOptions.syntax) { applyLanguage(it) }
  injector.optionGroup.addEffectiveOptionValueChangeListener(VsCodeOptions.tabstop) { applyIndent(it) }
  injector.optionGroup.addEffectiveOptionValueChangeListener(VsCodeOptions.shiftwidth) { applyIndent(it) }
  injector.optionGroup.addEffectiveOptionValueChangeListener(VsCodeOptions.expandtab) { applyIndent(it) }
  injector.optionGroup.addEffectiveOptionValueChangeListener(VsCodeOptions.wrap) {
    VsCodeOptions.wrapWasAsked = true
    applyWordWrap(it, asked = true)
  }
}

/**
 * Whether the editor wraps, from `'wrap'`.
 *
 * **Written rather than toggled, and that is the whole of what makes it work.** VS Code has no
 * per-editor setting for the wrap - `TextEditorOptions` carries the gutter, the tab size and the
 * caret shape and not this - so the first version of this reached for
 * `editor.action.toggleWordWrap`, which is what `Alt+Z` runs. A toggle cannot be pointed at a
 * state, only flipped, so it needed a belief about which way the editor currently was; and VS Code
 * will not report that either. One wrong belief and every command means its opposite, which is
 * exactly what it did: `:set nowrap` wrapped the file and `:set wrap` unwrapped it. There is no
 * amount of care in maintaining that belief that makes a guess safe.
 *
 * `WorkspaceConfiguration.update` sets an absolute value, so it cannot be inverted, and the same
 * setting reads back - so `:set wrap?` is answering from the editor rather than from a memory of
 * what this host last asked for.
 *
 * The cost is that a Vim option that is *window*-local is written as a setting that is not: it
 * lands in the workspace when there is one and in the user's settings otherwise, and it persists.
 * That is a real difference from Vim and the better of the two trades - the alternative is a
 * `:set nowrap` that means nothing, or means the opposite half the time.
 *
 * ## What this cannot reach
 *
 * VS Code lets an editor carry a word wrap of its own, *on top of* the setting. `Alt+Z` sets one -
 * so does `editor.action.toggleWordWrap` - it wins over `editor.wordWrap`, and there is no API to
 * read it or to clear it. An editor in that state ignores `:set nowrap` however correctly the
 * setting is written, and the only way out is to press `Alt+Z` again.
 *
 * That is not a gap waiting to be filled; it is the same missing API that made the toggle
 * unworkable, seen from the other side. Reported on request rather than silently, because a user
 * who meets it has no way of telling it apart from a bug.
 */
internal fun applyWordWrap(editor: VimEditor, asked: Boolean = false) {
  val vsCode = editor as? VsCodeEditor ?: return
  val wanted = injector.optionGroup
    .getOptionValue(VsCodeOptions.wrap, OptionAccessScope.EFFECTIVE(editor))
    .asBoolean()
  val raw = rawWordWrap(vsCode)
  val configured = raw != null && raw != VsCodeSettings.WORD_WRAP_OFF

  // An explicit `:set wrap` is written whatever the read says and whatever was written before.
  //
  // Both of the guards below are for the path that runs after *every* keystroke, where the point is
  // not to write a settings file fifty times for a typed word. On the path where the user asked,
  // they are two ways to refuse an instruction: the read can disagree with the screen - it has, all
  // through this option's history - and the remembered write can be of a value the setting no
  // longer holds. Neither is a reason to do nothing when someone typed the command.
  if (asked) {
    traceWrap(editor, "wrap: ${VsCodeSettings.WORD_WRAP_SETTING} says ${raw ?: "unset"}, writing ${named(wanted)}")
    writeWordWrap(vsCode, wanted, force = true)
    return
  }
  // Not on every keystroke - this runs after each one, and a settings write is a round trip and a
  // file on disk. Only when Vim's answer and the editor's have actually parted company.
  if (wanted == configured) return
  writeWordWrap(vsCode, wanted, force = false)
}

/** The setting as VS Code answers it, unmapped, so a trace line can show what was actually read. */
private fun rawWordWrap(editor: VsCodeEditor): String? = wordWrapSetting(editor)

private fun named(wrapping: Boolean) = if (wrapping) "wrap" else "nowrap"

/**
 * What to say when the setting was written and the screen may not follow.
 *
 * Confirmed from a real window: the write lands, the read comes back changed, and the editor keeps
 * wrapping - because VS Code applies a per-editor wrap on top of the setting and it survives the
 * setting changing. A message that reports success and leaves the user looking at unchanged text is
 * worse than no message.
 */
private const val OVERRIDE =
  "If the lines did not change, this editor has a wrap of its own on top of the setting - " +
    "press Alt+Z, or close and reopen the file."

/**
 * What `'wrap'` decided, when `vimperor.trace` is on.
 *
 * This option is the one thing in the extension whose state lives entirely outside it - VS Code
 * will not report whether an editor is wrapping, so every version of this has had to reason about a
 * value it cannot see, and two of them reasoned wrongly in a way no test could catch. A line saying
 * what was read and what was written is the difference between a bug report and a guess.
 */
private fun traceWrap(editor: VimEditor, message: String) {
  val tracing = try {
    workspace.getConfiguration("vimperor").get("trace") == true
  } catch (e: Throwable) {
    false
  }
  if (tracing) injector.messages.showMessage(editor, message)
}

/**
 * `editor.wordWrap` on or off, where it will reach *this file*.
 *
 * The target is not "workspace if there is one". A workspace setting covers the folders in the
 * workspace and nothing else, so a file opened on its own alongside a project - which is an
 * ordinary thing to have - would be written for and unaffected. The folder the file is in is the
 * narrowest target that certainly covers it; a file in no folder gets the user's settings.
 *
 * Written at most once per value on the keystroke path: that runs after every key, and a settings
 * write is a file on disk. The read cannot be used to tell - it answers from the configuration,
 * which has not caught up in the same turn, which is why the first version of this wrote twice for
 * one `:set wrap`. A typed `:set` forces its way past that guard; see [applyWordWrap].
 *
 * ## Always into the language block
 *
 * Not "when a language block is what decides this file's wrap", which is what this used to work out
 * by comparing a document-scoped read with a URI-scoped one. That decision was made per call and
 * came out differently for the two directions, so `:set wrap` wrote the language value and
 * `:set nowrap` wrote the plain one - two different layers, and the second could not undo the
 * first. It was visible in a trace as a `wrap` that wrapped and a `nowrap` that did nothing.
 *
 * An option has to be written where it is read. The language block is the layer that wins - a
 * `[markdown]` block beats the plain setting at the same scope, which is exactly why people use it
 * to turn word wrap on - so writing there is the only choice that is certain to take effect and
 * certain to be reversible.
 */
private fun writeWordWrap(editor: VsCodeEditor, wrapping: Boolean, force: Boolean) {
  if (!force && editor.wroteWordWrap == wrapping) return
  editor.wroteWordWrap = wrapping
  val inAFolder = workspace.getWorkspaceFolder(editor.nativeEditor.document.uri) != null
  val target = if (inAFolder) ConfigurationTarget.WorkspaceFolder else ConfigurationTarget.Global
  val value = if (wrapping) VsCodeSettings.WORD_WRAP_ON else VsCodeSettings.WORD_WRAP_OFF
  try {
    workspace.getConfiguration(VsCodeSettings.EDITOR, editor.nativeEditor.document)
      .update(VsCodeSettings.WORD_WRAP, value, target, /* overrideInLanguage = */ true)
      .then(
        {
          val wrote = "wrap: wrote ${VsCodeSettings.WORD_WRAP}=$value for this language to target $target"
          // The hint only where it can be acted on: a write the user asked for is the one whose
          // result they are looking at. The keystroke path writes when nothing was typed.
          traceWrap(editor, if (force) "$wrote. $OVERRIDE" else wrote)
        },
        // Reported rather than swallowed. A settings write can be refused - a workspace target with
        // no folder, a read-only settings file - and a `:set nowrap` that silently does nothing is
        // the failure this option has already had twice.
        { reason ->
          injector.messages.showErrorMessage(
            editor,
            "Vimperor could not write ${VsCodeSettings.WORD_WRAP}=$value: $reason",
          )
        },
      )
  } catch (e: Throwable) {
    injector.messages.showErrorMessage(
      editor,
      "Vimperor could not write ${VsCodeSettings.WORD_WRAP}=$value: ${e.message}",
    )
  }
}

/**
 * What the editor is doing, so that Vim's option starts where the editor already is.
 *
 * Vim wraps by default and VS Code does not, so an option left at Vim's answer would turn wrapping
 * on in every editor the moment Vimperor loaded. Seeded from the editor, the two agree and nothing
 * is written until the user asks.
 *
 * Not once the user has said something: a `~/.vimperorrc` with `set nowrap` in it means the answer
 * for every window, and a window that opened afterwards must not be re-seeded back out of it.
 */
private fun seedWordWrap(editor: VsCodeEditor) {
  if (VsCodeOptions.wrapWasAsked) return
  injector.optionGroup.setOptionValue(
    VsCodeOptions.wrap,
    OptionAccessScope.LOCAL(editor),
    VimInt(if (configuredWordWrap(editor)) 1 else 0),
  )
}

/**
 * The editor's own `wordWrap` setting, as a boolean, for the file this editor is showing.
 *
 * VS Code's four values are `off`, `on`, `wordWrapColumn` and `bounded`, and Vim's option has two -
 * so everything but `off` is wrapping. Unset reads as `off`, which is VS Code's own default.
 *
 * Scoped to the document, because without a scope VS Code answers for the *window* and ignores a
 * language override - a `[markdown]` block turning word wrap on - which is how people usually do
 * it.
 *
 * Read defensively: the one host that is not a VS Code window is the stub the tests run in.
 */
internal fun configuredWordWrap(editor: VsCodeEditor? = null): Boolean =
  wordWrapSetting(editor).let { it != null && it != VsCodeSettings.WORD_WRAP_OFF }

/**
 * The setting as VS Code answers it for this file, unmapped.
 *
 * The scope is the **document**, not its URI, and the difference is the whole of why this option
 * appeared to work and did nothing for so long. A `Uri` scope resolves the folder's value and stops
 * there; a document resolves the *language override* as well - the `[markdown]` block that turns
 * word wrap on, which is how people usually turn it on. Reading by URI answered `off` for a file VS
 * Code was wrapping, so every read agreed with every write and neither described the screen.
 */
private fun wordWrapSetting(editor: VsCodeEditor?): String? = try {
  val scope = editor?.nativeEditor?.document
  workspace.getConfiguration(VsCodeSettings.EDITOR, scope).get(VsCodeSettings.WORD_WRAP) as? String
} catch (e: Throwable) {
  null
}

/**
 * The indent options a user's `~/.vimrc` actually sets, against `TextEditorOptions`.
 *
 * ## Why these three and not the whole accepted group
 *
 * They pass all four of the questions `'wrap'` left behind, and pass them more easily than `'wrap'`
 * did. They can be *set* rather than toggled - `tabSize` and `insertSpaces` take absolute values.
 * Their default comes from the editor, by [seedIndent], because Vim's own defaults are `ts=8`,
 * `sw=8` and `noexpandtab`, and a host that applied those on open would re-indent every file
 * anybody opened. The read is scoped by construction: `TextEditorOptions` belongs to one editor and
 * there is no wider layer to be confused with. And the write lands exactly where the read comes
 * from, which is the whole of what took `'wrap'` three attempts - there is no settings file here, no
 * workspace target and no language block, because VS Code models indentation as editor state and
 * models the wrap as configuration.
 *
 * That last difference is worth keeping in mind before reaching for the next option: these are
 * *easier* than `'wrap'`, not harder, and the reason is the shape of VS Code's API rather than
 * anything about the options themselves.
 *
 * ## What is written
 *
 * `'tabstop'` is `tabSize`, `'shiftwidth'` is `indentSize`, `'expandtab'` is `insertSpaces`. The
 * three map one to one, which they did not before VS Code separated `indentSize` from `tabSize` in
 * 1.85; `'shiftwidth'` at zero is written as the string `"tabSize"`, which is that API spelling
 * Vim's "use `'tabstop'`" rule.
 *
 * `'softtabstop'` is not written, and that is not an omission. It governs what a *Tab keypress*
 * covers, and this host handles Tab itself in `VimEditorTab` rather than letting VS Code see it, so
 * writing it would tell VS Code about a key it never receives. `VsCodeIndentConfig.toNextTabStop`
 * is where it is read.
 *
 * Compared against what the editor is *showing* rather than against what was last written, for the
 * reason [applyLineNumbers] gives at length: VS Code owns these and resets them on its own account,
 * and a memory of our own writes cannot see that happen.
 */
internal fun applyIndent(editor: VimEditor) {
  if (seeding) return
  val vsCode = editor as? VsCodeEditor ?: return
  val options = vsCode.nativeEditor.options
  val scope = OptionAccessScope.EFFECTIVE(editor)

  val tabstop = injector.optionGroup.getOptionValue(VsCodeOptions.tabstop, scope).value
  if (tabstop > 0 && tabstop != options.tabSize) options.tabSize = tabstop

  val expandtab = injector.optionGroup.getOptionValue(VsCodeOptions.expandtab, scope).asBoolean()
  if (expandtab != options.insertSpaces) options.insertSpaces = expandtab

  // Zero is Vim's "however wide a tab is", and `"tabSize"` is VS Code's spelling of the same thing.
  val shiftwidth = injector.optionGroup.getOptionValue(VsCodeOptions.shiftwidth, scope).value
  val wanted: dynamic = if (shiftwidth > 0) shiftwidth else VsCodeSettings.INDENT_SIZE_TAB_SIZE
  if (wanted != options.indentSize) options.indentSize = wanted
}

/**
 * Starts an editor's indent options off at what VS Code says this file's indentation is.
 *
 * The alternative is Vim's defaults, and they would be actively destructive: `ts=8 sw=8 noexpandtab`
 * applied to a project of two-space files re-indents every one of them the first time anybody
 * presses `>>`. Deferring to VS Code is also what makes `>>` agree with pressing Tab in the same
 * file without Vim, which this port has always done and must go on doing.
 *
 * Once per editor, because [applyEditorOptions] runs after every keystroke and a `:setlocal
 * shiftwidth=2` typed into this window must survive the next key. Per editor rather than once
 * overall - unlike `'wrap'`, which has a single flag - because VS Code resolves indentation per
 * file, per language and, with `detectIndentation` on, from the file's own contents: two windows
 * genuinely have two answers.
 *
 * Not for an option the user has set. A `~/.vimrc` runs before any of this and its values are the
 * global ones, so an option whose global value has moved off its default was asked for and is left
 * alone. The one case that misses is a config setting an option to exactly Vim's default - `set
 * tabstop=8` - which is read here as not having asked. It is the same class of ambiguity `'wrap'`
 * has and it fails the safe way: towards the editor's own answer.
 */
private fun seedIndent(editor: VsCodeEditor) {
  if (editor.seededIndent) return
  editor.seededIndent = true

  // Read before any of it is written, and written with [applyIndent] held off. Seeding one option
  // changes its value, which fires the engine's change listener, which runs [applyIndent] - so
  // seeding `'tabstop'` wrote the *un-seeded* `'expandtab'` onto the editor, and the next line read
  // that back as the editor's own answer and seeded a file of spaces as a file of tabs. Reading
  // first fixes the value; the guard stops a settings write nobody asked for on the way through.
  val options = editor.nativeEditor.options
  val size = options.tabSize as? Int
  // Compared rather than cast: `insertSpaces` is `dynamic` because VS Code types it
  // `boolean | string`, and `as? Boolean` on a `dynamic` does not narrow the way `as? Int` does.
  val spaces: dynamic = options.insertSpaces

  seeding = true
  try {
    size?.takeIf { it > 0 }?.let { seed(VsCodeOptions.tabstop, editor, VimInt(it)) }
    if (spaces == true || spaces == false) {
      seed(VsCodeOptions.expandtab, editor, VimInt(if (spaces == true) 1 else 0))
    }
  } finally {
    seeding = false
  }
}

/**
 * Whether [seedIndent] is part-way through, so that its own writes do not look like a `:set`.
 *
 * One flag for the whole host rather than one per editor: seeding is synchronous and there is one
 * thread, so no two editors are ever inside it at once.
 */
private var seeding: Boolean = false

/** Sets an editor's local value, unless a config has already set the option globally. */
private fun <T : VimDataType> seed(option: Option<T>, editor: VsCodeEditor, value: T) {
  if (injector.optionGroup.getOptionValue(option, OptionAccessScope.GLOBAL(null)) != option.defaultValue) return
  injector.optionGroup.setOptionValue(option, OptionAccessScope.LOCAL(editor), value)
}

/** Everything Vim tells VS Code about an editor, applied together. */
internal fun applyEditorOptions(editor: VimEditor) {
  applyLineNumbers(editor)
  applyLanguage(editor)
  (editor as? VsCodeEditor)?.let {
    seedWordWrap(it)
    seedIndent(it)
  }
  applyWordWrap(editor)
  applyIndent(editor)
}

/**
 * What `env.appName` says, defensively.
 *
 * This is read while [VsCodeOptions] is initialising, which is early - and the one host that is not
 * a VS Code window is the stub the tests run in. A missing `appName` there would be a
 * `TypeError` thrown out of option registration, which is a bad way to find out; the fallback is
 * the name of the editor this extension is for.
 */
private fun appName(): String = try {
  env.appName.ifEmpty { "Visual Studio Code" }
} catch (e: Throwable) {
  "Visual Studio Code"
}

/** What `'ideastatusicon'` asks of the mode indicator. */
internal enum class StatusIcon {
  SHOWN,
  GRAY,
  HIDDEN,
}

/**
 * `'ideastatusicon'`, read.
 *
 * A function rather than a listener because the mode indicator is rewritten after every keystroke
 * anyway - see `refreshMode` in `Extension.kt` - so there is nothing for a listener to do that
 * asking here does not already do, and one fewer thing to unregister.
 */
internal fun statusIcon(): StatusIcon =
  when (injector.optionGroup.getOptionValue(VsCodeOptions.ideastatusicon, OptionAccessScope.GLOBAL(null)).asString()) {
    "disabled" -> StatusIcon.HIDDEN
    "gray" -> StatusIcon.GRAY
    else -> StatusIcon.SHOWN
  }

/** Whether `:w` saves every open file, which is `set ideawrite=all` - not the default here. */
internal fun writesEveryFile(): Boolean =
  injector.optionGroup.getOptionValue(VsCodeOptions.ideawrite, OptionAccessScope.GLOBAL(null)).asString() == "all"
