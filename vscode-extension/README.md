# Vimperor

Vim for VS Code, powered by IdeaVim's engine.

This is not another Vim written from scratch, and it is not a Neovim running as a subprocess. It is
IdeaVim's `vim-engine` — the engine that plugin ships, with its Vimscript parser, its regex engine
and its test corpus — compiled to JavaScript and loaded into the extension host. Modes, operators, text
objects, registers, macros, marks and Vimscript are all the engine's own, so they behave the way
they behave in IdeaVim rather than the way somebody reimplemented them.

## Getting started

Install it, restart the window, and start typing. The mode appears in the status bar.

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

- **Vim plugins.** There is no `pack/` directory, and IdeaVim's bundled extensions — surround,
  commentary, targets and the rest — are not ported yet.
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
licence that makes the code free to use does not make it free to imply an endorsement with. So the
extension has its own name and its own config file, `~/.vimperorrc`. What it keeps is the
attribution, and the ability to read `~/.ideavimrc` unchanged: that is the file this engine has
always read and the one its users already have, and taking a new name is no reason to make them
copy it.

`DEVELOPMENT.md` in the repository is the long version — how it is built, how it is tested, and
what only a real editor window ever found.
