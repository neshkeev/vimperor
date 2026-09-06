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
  injector.optionGroup.addEffectiveOptionValueChangeListener(VsCodeOptions.wrap) {
    VsCodeOptions.wrapWasAsked = true
    applyWordWrap(it)
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
 */
internal fun applyWordWrap(editor: VimEditor) {
  val vsCode = editor as? VsCodeEditor ?: return
  val wanted = injector.optionGroup
    .getOptionValue(VsCodeOptions.wrap, OptionAccessScope.EFFECTIVE(editor))
    .asBoolean()
  val configured = configuredWordWrap(vsCode)
  // Not on every keystroke - this runs after each one, and a settings write is a round trip and a
  // file on disk. Only when Vim's answer and the editor's have actually parted company.
  if (wanted == configured) return
  traceWrap(editor, "wrap: want ${named(wanted)}, editor says ${named(configured)} - writing")
  writeWordWrap(vsCode, wanted)
}

private fun named(wrapping: Boolean) = if (wrapping) "wrap" else "nowrap"

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

/** `editor.wordWrap` on or off, wherever this window can write it. */
private fun writeWordWrap(editor: VsCodeEditor, wrapping: Boolean) {
  val target =
    if (workspace.workspaceFolders.isNullOrEmpty()) ConfigurationTarget.Global else ConfigurationTarget.Workspace
  val value = if (wrapping) VsCodeSettings.WORD_WRAP_ON else VsCodeSettings.WORD_WRAP_OFF
  try {
    workspace.getConfiguration(VsCodeSettings.EDITOR, editor.nativeEditor.document.uri)
      .update(VsCodeSettings.WORD_WRAP, value, target)
      .then(
        { traceWrap(editor, "wrap: wrote ${VsCodeSettings.WORD_WRAP}=$value to target $target") },
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
internal fun configuredWordWrap(editor: VsCodeEditor? = null): Boolean = try {
  val scope = editor?.nativeEditor?.document?.uri
  val value = workspace.getConfiguration(VsCodeSettings.EDITOR, scope).get(VsCodeSettings.WORD_WRAP)
  value != null && value != VsCodeSettings.WORD_WRAP_OFF
} catch (e: Throwable) {
  false
}

/** Everything Vim tells VS Code about an editor, applied together. */
internal fun applyEditorOptions(editor: VimEditor) {
  applyLineNumbers(editor)
  applyLanguage(editor)
  (editor as? VsCodeEditor)?.let { seedWordWrap(it) }
  applyWordWrap(editor)
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
