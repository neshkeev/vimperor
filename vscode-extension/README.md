# Vimperor

Vim for VS Code, powered by IdeaVim's engine.

This is not another Vim written from scratch, and it is not a Neovim running as a subprocess. It is
JetBrains' `vim-engine` — the one IdeaVim ships, with its Vimscript parser, its regex engine and its
test corpus — compiled to JavaScript and loaded into the extension host. Modes, operators, text
objects, registers, macros, marks and Vimscript are all the engine's own, so they behave the way
they behave in IdeaVim rather than the way somebody reimplemented them.

## Getting started

Install it, restart the window, and start typing. The mode appears in the status bar.

Vimperor reads **`~/.ideavimrc`** — the same file IdeaVim reads, and the XDG location
(`$XDG_CONFIG_HOME/ideavim/ideavimrc`) if you keep it there. If you have a `~/.vimrc` you want to
use, `source ~/.vimrc` from it.

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
rest of the families a `~/.vimrc` is written with. Around 380 ex commands.

More than a hundred options, including `'ignorecase'`, `'smartcase'`, `'scrolloff'`, `'clipboard'`,
`'timeoutlen'`, `'iskeyword'`, `'virtualedit'`, `'whichwrap'` and `'wrapscan'`.

## What does not

Some things are genuinely absent rather than unfinished, and it is worth knowing which:

- **Vim plugins.** There is no `pack/` directory and no `:Plug`. IdeaVim's bundled extensions —
  surround, commentary, easymotion — are not ported yet.
- **Windows and tabs are VS Code's.** `:split` and `:vsplit` open its editor groups; there is no Vim
  window layout underneath, so `<C-w>` movements go where VS Code's do.
- **The command-line window** (`q:`, `q/`) and the preview window.
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
licence that makes the code free to use does not make it free to imply an endorsement with. What
the extension keeps is the attribution, and the config file: `~/.ideavimrc` is the file this engine
has always read, and the one its users already have.

`DEVELOPMENT.md` in the repository is the long version — how it is built, how it is tested, and
what only a real editor window ever found.
