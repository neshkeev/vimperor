# Vimperor

Vim for VS Code, powered by IdeaVim's engine.

This is not another Vim written from scratch, and it is not a Neovim running as a subprocess. It is
IdeaVim's `vim-engine` — the engine that plugin ships, with its Vimscript parser, its regex engine
and its test corpus — compiled to JavaScript and loaded into the extension host. Modes, operators, text
objects, registers, macros, marks and Vimscript are all the engine's own, so they behave the way
they behave in IdeaVim rather than the way somebody reimplemented them.

## Getting started

Install it, restart the window, and start typing. The mode appears in the status bar.

### Cursor, Windsurf, VSCodium and other forks

Vimperor runs in all of them — nothing in it is specific to VS Code, and `&ide` answers with
whichever editor it is in, so a config can branch on it. Only *installing* differs, because a fork
cannot use Microsoft's Marketplace: search for **Vimperor** and you are searching
[Open VSX](https://open-vsx.org/extension/neshkeev/vimperor), which is where every release is
published alongside the Marketplace.

If your editor searches neither, every release also attaches the `.vsix` to its
[GitHub release](https://github.com/neshkeev/vimperor/releases), and any VS Code fork can install
one by hand — "Install from VSIX…" in the Extensions view, or:

```bash
codium --install-extension vimperor-0.0.3.vsix   # cursor, windsurf, code-insiders, ...
```

The extension asks for VS Code 1.85 or newer, which every current fork is well past.

### The config file

Vimperor's own config file is **`~/.vimperorrc`**. If you do not have one, it reads whichever of
these you do — the first found wins, and nothing is merged:

| file | for |
|---|---|
| **`~/.vimperorrc`** | **Vimperor. Read first, from wherever it is** |
| `~/.ideavimrc` | shared with IdeaVim in IntelliJ, unchanged |
| `~/.vimrc` | Vim's own — nothing to copy, nothing to rename |

So **an existing setup works untouched**, and you only need a `~/.vimperorrc` when you want
something to apply here and not in IntelliJ. When you do, pull in the rest on its first line:

```vim
" ~/.vimperorrc
source ~/.ideavimrc
nnoremap <leader>f <Action>(workbench.action.quickOpen)
```

Each name is also looked for as `_name` and in its XDG directory
(`$XDG_CONFIG_HOME/vimperor/vimperorrc` and so on), and Vim's adds `~/.vim/vimrc`. A whole family
is searched before the next one starts, so a `vimperorrc` anywhere beats an `ideavimrc` anywhere —
the name says which editor the file was written for.

If all you have is a `~/.vimrc`, it is read as it is — **but expect part of it not to apply.**
There are no Vim plugins here, so a plugin manager does nothing, and neither do autocommands,
`syntax` or `colorscheme`. Those lines are stepped over without complaint and the rest of the file
takes effect. The Vimperor output channel names the file it loaded, and says when it was Vim's own.

Whichever file you use, it is ordinary Vimscript:

```vim
set number relativenumber
set incsearch hlsearch
set clipboard^=unnamed
set scrolloff=5

let mapleader = " "
nnoremap <leader>w :w<CR>
nnoremap <leader>f <Action>(workbench.action.quickOpen)
```

`<Action>(id)` runs any VS Code command from a mapping — `:actionlist` lists them, with the
keyboard shortcut each one already has. `:action {id}` runs one from the command line.

That is also how to change what a Vim key does. `=` re-indents, which is all Vim's `=` has ever
done — it fixes leading whitespace and never splits or joins a line, so on a document that is
already one line it correctly does nothing. If you would rather it ran the editor's formatter, the
way IdeaVim's `=` does:

```vim
xnoremap = <Action>(editor.action.formatSelection)
```

### Cyrillic keyboard layouts

Vim commands are Latin letters, so with a Cyrillic layout active `dw` is typed as `вц` and reaches
nothing. Set a layout and it does:

```vim
set keyboardlayout=russian
```

`russian`, `ukrainian` and `belarusian` are built in, and each is ЙЦУКЕН with a few keys moved.
Once one is set, `вфц` deletes a word, `су` changes to the end of one, `Эйнн` yanks into register
`q`, and `ьф` sets mark `a` — counts, registers, marks, operators, text objects and `.` all follow,
because this is a translation of the key rather than a set of mappings.

**Insert mode is untouched**, which is the point: `ш` enters it and everything you type after that
is the word you meant to write. Search patterns and `:` commands are text too, and are also left
alone — as is `f`'s argument, so `fв` still finds a `в`.

This is Vim's `'langmap'` with the table filled in. `'langmap'` still works and is consulted first,
so a single key can be corrected without giving up the rest:

```vim
set keyboardlayout=russian
set langmap=цq          " this one key goes somewhere else
```

**Seven keys are deliberately left out**: `$`, `^`, `@`, `&`, `/`, `?` and `|`. On a Cyrillic
layout those sit on keys that emit ASCII — Shift+4 gives `;`, not `$` — and a character arrives
here with no record of which layout produced it. Translating them would make `$` reachable and take
`.`, `,`, `;`, `:`, `/` and `?` away from anyone typing in Latin. Since the whole point is that
**both layouts keep working**, they are left alone; use the Latin layout for those seven, or, if
you never issue commands from it, add them yourself:

```vim
" Takes `.`, `,`, `;`, `:`, `/` and `?` away from the Latin layout. Only if you never use it.
set langmap=\"@\\;$:^?&./\\,?/\|
```

**A whole command line typed in the wrong layout is corrected.** `:ыуе тщцкфз` runs `:set nowrap`,
and says so on the status line. This is not `'langmap'` reaching the command line — a command line
carries text as well as commands, and `:s/привет/пока/` must mean what it says. It is a correction,
allowed only where it cannot destroy anything: the line has to contain **no Latin letter at all**,
the command as typed has to be no command, and the corrected one has to be a real command. Nothing
that works today changes, and nothing has run when the decision is made.

The rule about Latin letters is what separates an accident from a decision. Someone who forgot to
switch layouts typed the whole line in Cyrillic, argument included; someone who typed
`:w привет.txt` switched on purpose, and that Cyrillic is a filename. So a mixed line is left alone
— including `:%ы/one/ONE/g`, where the `s` was a slip. Digits and punctuation are not letters, so
`:ыуе еы=4` is still corrected.

Mapping the keys instead — `nmap ш i` and sixty more — is the thing to avoid. It cannot reach a
register name, a mark name or a count, it is recursive unless every line says `nnoremap`, and it
leaves Visual mode out unless you remember `xmap` as well.

## Only one Vim at a time

An extension can only see ordinary typing by taking over VS Code's `type` command, and `type` has
one owner. **Disable any other Vim extension** before enabling this one, or one of them will get
your keystrokes and the other will not.

## What works

Normal, insert, visual, visual block, select, replace and operator-pending modes. Counts, registers,
macros, marks, jumps, the change list, text objects, `.`, undo and redo. Search with `incsearch` and
`hlsearch`, `:substitute` with its flags, `:global`, `:normal`, `:sort` and `:uniq`.

Vimscript: functions, conditionals, loops, dictionaries, lists, `:let`, `:execute`, and around 170
builtin functions — `printf`, `substitute`, `matchstr`, `expand`, `glob`, `getreg`, `getpos` and the
rest of the families a `~/.vimrc` is written with. Just over 400 ex commands.

More than a hundred options, including `'ignorecase'`, `'smartcase'`, `'scrolloff'`, `'clipboard'`,
`'timeoutlen'`, `'iskeyword'`, `'virtualedit'`, `'whichwrap'` and `'wrapscan'`.

Files and places: `gf` opens the file under the caret, `%` on the command line is the current file
and takes Vim's `:p`, `:h`, `:t`, `:r` and `:e` modifiers — so `:e %:h/other.kt` and `:!wc %` mean
what they mean in Vim — and `<Tab>` completes a file name, through a `%` and on into a directory.
`"%` and `"#` hold the current and alternate file names.

The tag stack is real rather than a wrapper around Go to Definition: a `tags` file is read, and
`:tag`, `:tselect`, `:tjump`, `:tnext`, `:tprevious`, `:pop` and `:tags` work over it, with `<C-]>`
and `<C-T>` walking it. `:sign` defines and places signs in the gutter.

The command-line window is there — `q:`, `q/` and `q?` open the history as a buffer you can edit
and run with `<CR>`.

## What does not

Some things are genuinely absent rather than unfinished, and it is worth knowing which:

- **`:set nowrap` in an editor you have pressed `Alt+Z` in.** VS Code lets a single editor carry a
  word wrap of its own, on top of the `editor.wordWrap` setting — `Alt+Z` sets one, and so does
  *View: Toggle Word Wrap*. It wins, and no extension can read it or clear it. `:set wrap` and
  `:set nowrap` write the setting correctly and that editor goes on ignoring them; pressing `Alt+Z`
  again, or closing and reopening the file, is the way out. It is the only option that can be set
  correctly and still appear to do nothing.

- **Vim plugins.** There is no `pack/` directory, and 2 of IdeaVim's 27 bundled extensions are not
  available — `matchit` and `VimEverywhere` want IDE machinery VS Code has nothing shaped like. The
  other twenty-five work, and they are enabled the way you would in IdeaVim — with `set <name>`, or
  with a `Plug` line for a config borrowed from Vim:

  ```vim
  Plug 'vim-scripts/ReplaceWithRegister'   " gr{motion}, grr — replace with a register, keeping it
  Plug 'dbakker/vim-paragraph-motion'      " { and } stop at a line of only whitespace
  Plug 'kana/vim-textobj-entire'           " ae and ie — the whole buffer as a text object
  Plug 'kana/vim-textobj-line'             " al and il — the line, with and without its indentation
  Plug 'echasnovski/mini.ai'               " ci( and friends, from anywhere on the line
  Plug 'bkad/CamelCaseMotion'              " ,w ,b ,e move by the parts of an identifier
  Plug 'jeetsukumaran/vim-indentwise'      " [- ]- [+ ]+ [= ]= move by indentation level
  Plug 'kana/vim-textobj-user'             " textobj#user#plugin() — declare text objects of your own
  Plug 'wellle/targets.vim'                " ci( from anywhere, cin( for the next pair, cil( the last
  Plug 'tpope/vim-abolish'                 " crs crc crm recase a word; :S, a case-carrying :s
  Plug 'michaeljsmith/vim-indent-object'   " ai ii aI — the block at the caret's indentation level
  Plug 'vim-scripts/argtextobj.vim'        " ia aa — one argument of a call, nesting and strings and all
  Plug 'tpope/vim-commentary'              " gc{motion}, gcc, gcu and :Commentary
  Plug 'machakann/vim-highlightedyank'     " the text a yank covered flashes
  Plug 'tommcdo/vim-exchange'              " cx{motion} marks a region, cx on a second swaps them
  Plug 'justinmk/vim-sneak'                " s{char}{char} jumps to the pair; S back, ; and , repeat
  Plug 'tpope/vim-surround'                " ys{motion}{char}, cs{from}{to}, ds{char}, S in visual
  Plug 'terryma/vim-multiple-cursors'      " <C-n> a caret on the next occurrence, <C-x> skip, <C-p> back
  Plug 'vim-scripts/YankRing.vim'          " every yank in a ring; <C-P>/<C-N> cycle the last paste
  Plug 'kana/vim-textobj-function'         " am aM im — a function without its doc comment, with it, its body
  Plug 'kana/vim-textobj-class'            " ac — the class, interface, struct or enum you are inside
  Plug 'bronson/vim-visual-star-search'    " * and # in visual mode search for the selection
  Plug 'kshenoy/vim-signature'             " your a-z marks drawn in the gutter
  Plug 'preservim/nerdtree'                " :NERDTree and friends, plus j k o s in the Explorer
  Plug 'easymotion/vim-easymotion'         " <Leader><Leader>w labels every word start; the next key jumps
  set youcompleteme                        " Tab walks the completion list instead of accepting
  ```

  `easymotion` is the one in that list IdeaVim does not bundle: there it is a separate plugin,
  IdeaVim-EasyMotion over AceJump, and both are GPL. This one is written for Vimperor and follows
  vim-easymotion itself, so `set easymotion` in an `.ideavimrc` does what it did there.
  `<Leader><Leader>` followed by `w`, `b`, `e`, `j`, `k`, `n`, or `f`, `t` or `s` and a character,
  labels every target on screen, and typing a label jumps there — under an operator too, so
  `d<Leader><Leader>w` deletes to the word you pick, and `j` and `k` take whole lines.
  `g:EasyMotion_keys` chooses the label keys, and labels can be typed on a Cyrillic layout. Not
  yet: the multi-character finds (`s2`, `sn`), `repeat`, `next`, `prev`, `jumptoanywhere` and the
  `line*` motions, and `.` does not repeat a jump. The labels sit over the text by way of a styling
  trick VS Code does not document; if an update breaks it, they will appear beside their targets
  rather than on them.

  `gc` comments with whatever syntax VS Code knows for the file, which is the same knowledge that
  drives its own Toggle Line Comment. `dgc`, the text object over a run of comment lines, needs a
  syntax tree and does nothing here.

  `yankring`'s `<C-P>` undoes the paste and re-pastes an older entry, and VS Code's undo is a
  command that finishes after the extension has moved on — so the re-paste happens a tick later,
  once the document has caught up, rather than in the keystroke that asked for it. You will not see
  the difference; the extension does, and says so at the code.

  `am`, `aM`, `im` and `ac` come from the same knowledge VS Code's own Outline view draws, kept in
  a cache that refreshes in the background — because a text object is asked its range in the middle
  of a keystroke and a language server answers over a promise. For the moment between an edit and
  the answer, those four keys do nothing rather than using a range that has moved. In a language
  with no symbol provider they do nothing at all, which is the same answer the Outline view gives.

  `signature` draws your `a`-`z` marks in the gutter, as Vim signs — so `:sign place` lists them
  and a `:sign unplace` of your own cannot sweep them away, because they sit in a sign group of
  their own. Only the lowercase marks: `A`-`Z` are IntelliJ bookmarks over there, drawn by the IDE
  itself, and two icons for one mark is worse than none.

  `NERDTree` is half an extension here, and it is worth knowing which half. Its ex commands work —
  `:NERDTree`, `:NERDTreeFocus`, `:NERDTreeToggle`, `:NERDTreeClose`, `:NERDTreeFind`,
  `:NERDTreeRefreshRoot` — over the Explorer. The keys it maps *inside* the tree are a different
  matter: a key pressed in VS Code's sidebar never reaches an extension, so they are declared in
  the extension's manifest and run VS Code's own commands, and they apply only while you have
  `NERDTree` enabled. Seventeen of the thirty have equivalents:

  `j` `k` `gg` `G` to move, `o` to open or expand, `s` to open beside, `O` to expand everything,
  `x` to collapse, `r` and `R` to refresh, `q` to close the sidebar, `n` and `N` for a new file and
  folder, `d` to delete, `y` and `v` to copy and paste, `ctrl+r` to rename.

  What has no equivalent: `i`, `gi`, `gs`, `go` and `T` (VS Code opens beside or not at all), `p`,
  `P`, `J`, `K`, `<C-J>` and `<C-K>` (its list commands move by row and know nothing about depth),
  `X`, `I`, `f`, `F`, `B`, `m`, `A`, and everything that changes the tree's root — the Explorer is
  rooted at the workspace folder and an extension cannot move it. These keys are left alone rather
  than bound to something approximate.

  `VimEverywhere` is absent, and it was tried. On IntelliJ it puts Vim keys in every tree and
  table, moves between panes with `<C-W>hjkl`, and — the part it is named for — labels every
  clickable thing in the window so you can click it by typing two letters. The first of those maps
  onto VS Code's lists cleanly; the last cannot be done at all, because an extension cannot draw
  over the workbench or enumerate what is on it. Shipping the navigation under the name of an
  extension whose point is the hints would have been the wrong trade.
- **Windows and tabs are VS Code's.** `:split` and `:vsplit` open its editor groups; there is no Vim
  window layout underneath, so `<C-w>` movements go where VS Code's do.
- **The preview window.** `:pedit` and the `preview` window flag have nothing to open into.
- **A terminal, channels and jobs.** No `:terminal`, no `job_start()`, no `timer_start()`.
- **`:syntax`, `:colorscheme`, `:filetype`.** VS Code decides all three for itself. The commands are
  accepted so a borrowed `~/.vimrc` loads without a wall of errors, and they say so when you ask
  them to change something.

`:help` opens VS Code's own documentation rather than Vim's.

## Reporting a problem

`vimperor.trace` in settings writes what each keystroke did — the mode, the carets, the selections
handed to VS Code — to the Vimperor output channel. A trace from a real window is worth more than a
description, because most of what is left to get wrong lives in the gap between the engine and the
editor.

Issues: <https://github.com/neshkeev/vimperor/issues>

## Licence and attribution

MIT. The engine is IdeaVim's, © the IdeaVim authors, MIT-licensed; see `ThirdPartyLicenses.md`.

The name is deliberately not IdeaVim's — "Idea" means IntelliJ IDEA and means nothing here, and a
licence that makes the code free to use does not make it free to imply an endorsement with. So the
extension has its own name and its own config file, `~/.vimperorrc`. What it keeps is the
attribution, and the ability to read `~/.ideavimrc` unchanged: that is the file this engine has
always read and the one its users already have, and taking a new name is no reason to make them
copy it.

`DEVELOPMENT.md` in the repository is the long version — how it is built, how it is tested, and
what only a real editor window ever found.
