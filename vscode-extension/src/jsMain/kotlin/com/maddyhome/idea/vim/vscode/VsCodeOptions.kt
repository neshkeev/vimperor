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
import com.maddyhome.idea.vim.options.OptionDeclaredScope.GLOBAL_OR_LOCAL_TO_BUFFER
import com.maddyhome.idea.vim.options.OptionDeclaredScope.GLOBAL_OR_LOCAL_TO_WINDOW
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
  // Only on a change. This runs after every keystroke - see [VimHost.handle] - and writing an
  // editor option is a round trip to VS Code, which is not something to do fifty times for a typed
  // word.
  if (style == vsCode.lastLineNumbers) return
  vsCode.lastLineNumbers = style
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
}

/** Everything Vim tells VS Code about an editor, applied together. */
internal fun applyEditorOptions(editor: VimEditor) {
  applyLineNumbers(editor)
  applyLanguage(editor)
}
