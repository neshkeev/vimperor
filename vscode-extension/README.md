# Vimperor

Vim for VS Code, powered by IdeaVim's engine.

This is not another Vim written from scratch, and it is not a Neovim running as a subprocess. It is
IdeaVim's `vim-engine` — with its Vimscript parser, its regex engine and its test corpus — compiled
to JavaScript and loaded into the extension host. Modes, operators, text objects, registers, macros,
marks and Vimscript are the engine's own, so they behave the way they do in IdeaVim rather than the
way somebody reimplemented them. **It reads your existing `~/.ideavimrc` unchanged.**

![Vim commands on a Russian keyboard layout](https://raw.githubusercontent.com/neshkeev/vimperor/master/vscode-extension/images/cyrillic_normal.gif)

*The same edit twice. First `vf,x` — select to the first comma, delete it — and `u` to put it back.
Then `ма,ч` and `г`, which is those same keys struck on a Russian layout, with nothing switched.
The comma stays a comma: it is `f`'s argument, and an argument is text.*

## Install

Install it, reload the window, and start typing. The mode appears in the status bar.

**Cursor, Windsurf, VSCodium** and the other forks cannot use Microsoft's Marketplace, so every
release also goes to [Open VSX](https://open-vsx.org/extension/neshkeev/vimperor), which is what
they search — and attaches the `.vsix` to its
[GitHub release](https://github.com/neshkeev/vimperor/releases) for anything that searches neither.
Install one by hand with *Install from VSIX…* in the Extensions view, or from the command line:

```bash
codium --install-extension vimperor-0.0.9.vsix   # cursor, windsurf, code-insiders, ...
```

Nothing in the extension is specific to VS Code, and `&ide` answers with whichever editor it is in.

> **Disable any other Vim extension first.** Seeing ordinary typing means taking over VS Code's
> `type` command, and `type` has one owner. With two installed, one of them gets your keystrokes
> and the other does not.

## Your config

Vimperor's own file is **`~/.vimperorrc`**. If you do not have one it reads whichever of these you
do — first found wins, nothing is merged:

| file | for |
|---|---|
| **`~/.vimperorrc`** | **Vimperor's own. Read first** |
| `~/.ideavimrc` | shared with IdeaVim in IntelliJ, unchanged |
| `~/.vimrc` | Vim's own — nothing to copy, nothing to rename |

So **an existing setup works untouched.** You only need a `~/.vimperorrc` when something should
apply here and not in IntelliJ; when you do, pull in the rest on its first line:

```vim
" ~/.vimperorrc
source ~/.ideavimrc

set number relativenumber
set incsearch hlsearch
set clipboard^=unnamed

let mapleader = " "
nnoremap <leader>f <Action>(workbench.action.quickOpen)
```

`<Action>(id)` runs any VS Code command from a mapping — `:actionlist` lists them all, with the
shortcut each already has. Each config name is also looked for as `_name` and in its XDG directory.

A borrowed `~/.vimrc` is read as it is, **but expect part of it not to apply**: there are no Vim
plugins here, so a plugin manager does nothing, and neither do autocommands, `syntax` or
`colorscheme`. Those lines are stepped over and the rest takes effect. The Vimperor output channel
names the file it loaded.

## Cyrillic keyboard layouts

Vim commands are Latin letters, so with a Cyrillic layout active `dw` is typed as `вц` and reaches
nothing. Set a layout and it does:

![Setting the Russian keyboard layout](https://raw.githubusercontent.com/neshkeev/vimperor/master/vscode-extension/images/edit_with_cyrillic_layout.gif)

```vim
set keyboardlayout=russian
```

`russian`, `ukrainian` and `belarusian` are built in. Counts, registers, marks, operators, text
objects and `.` all follow, because this translates the key rather than adding mappings — which is
what `nmap ш i` and sixty more lines can never do, since a mapping cannot reach a register name, a
mark name or a count.

**Insert mode is untouched**, which is the point: `ш` enters it and what you type after that is the
word you meant. Search patterns, `:` commands and `f`'s argument are text too, and are left alone.

This is Vim's `'langmap'` with the table filled in — the half Vim has never shipped. `'langmap'`
still works and is consulted first, so a single key can be redirected without giving up the rest.
**Seven keys are deliberately left out** (`$`, `^`, `@`, `&`, `/`, `?`, `|`): on a Cyrillic layout
those sit on keys that emit ASCII, and translating them would take `.`, `,`, `;`, `:`, `/` and `?`
away from anyone typing in Latin. Both layouts keep working.

**A whole command line typed in the wrong layout is corrected**, and says so:

![A command line typed in the wrong layout, corrected](https://raw.githubusercontent.com/neshkeev/vimperor/master/vscode-extension/images/wrap_nowrap.gif)

`:ыуе тщцкфз` runs `:set nowrap`. This is not `'langmap'` reaching the command line — a command
line carries text as well as commands, and `:s/привет/пока/` must mean what it says. The correction
is allowed only where it cannot destroy anything: the line must contain **no Latin letter at all**,
the command as typed must be no command, and the corrected one must be real. So `:w привет.txt` is
left alone, and nothing has run when the decision is made.

## What works

Normal, insert, visual, visual block, select, replace and operator-pending modes. Counts, registers,
macros, marks, jumps, the change list, text objects, `.`, undo and redo. Search with `'incsearch'`
and `'hlsearch'`, `:substitute` with its flags, `:global`, `:normal`, `:sort`. Vimscript with
functions, conditionals, loops, dictionaries and around 170 builtin functions. Just over 400 ex
commands and more than a hundred options.

`gf` opens the file under the caret; `%` on the command line is the current file and takes Vim's
`:p`, `:h`, `:t`, `:r` and `:e` modifiers. The tag stack is real rather than a wrapper around Go to
Definition — a `tags` file is read, and `<C-]>` and `<C-T>` walk it. `q:`, `q/` and `q?` open the
history as a buffer you can edit. `:sign` draws in the gutter. Vim's own tutor is built in:
`:vimtutor`, or *Vimperor: Open Vim Tutor* in the Command Palette.

**25 of IdeaVim's 27 bundled extensions are here, plus `easymotion` written for this fork** —
twenty-six in all, enabled with `set <name>` or a `Plug` line:
`surround`, `sneak`, `commentary`, `targets`, `easymotion`, `multiple-cursors`, `highlightedyank`,
`exchange`, `abolish`, `argtextobj`, `camelcasemotion`, `indentwise`, `yankring`, `signature`,
`NERDTree`, `ReplaceWithRegister`, the `textobj-*` family and the rest. The
[repository README](https://github.com/neshkeev/vimperor#readme) lists them all.

## What does not

- **`:set wrap` after *View: Toggle Word Wrap* from the Command Palette.** `'wrap'` is the editor's
  own word wrap — the one `Alt+Z` sets — so it is per file, survives a tab switch, and writes
  nothing to your settings. Vimperor claims `Alt+Z` so that your own toggling is kept up with, but
  the Command Palette entry runs a built-in command no extension can see. After it, `:set wrap` and
  `:set nowrap` mean the opposite in that file until you close it or press `Alt+Z`. Two splits of
  one file share a wrap, because VS Code attaches it to the document.
- **Vim plugins.** There is no `pack/` directory. 2 of IdeaVim's 27 bundled extensions are absent —
  `matchit` and `VimEverywhere` want IDE machinery VS Code has nothing shaped like.
- **Windows and tabs are VS Code's.** `:split` and `:vsplit` open its editor groups, so `<C-w>`
  movements go where VS Code's do. No preview window, so `:pedit` has nothing to open into.
- **A terminal, channels and jobs.** No `:terminal`, no `job_start()`, no `timer_start()`.
- **`:syntax`, `:colorscheme`, `:filetype`.** VS Code decides all three. The commands are accepted
  so a borrowed `~/.vimrc` loads without a wall of errors, and say so when asked to change
  something. `:help` opens VS Code's own documentation rather than Vim's.

## Reporting a problem

`vimperor.trace` in settings writes what each keystroke did — the mode, the carets, the selections
handed to VS Code — to the Vimperor output channel. A trace from a real window is worth more than a
description, because most of what is left to get wrong lives in the gap between the engine and the
editor.

Issues: <https://github.com/neshkeev/vimperor/issues>

## More

The [repository README](https://github.com/neshkeev/vimperor#readme) is the long version: the full
extension list and what each one does here, how the port was built and tested, and what the
replayed corpus of IdeaVim's own tests says about it.

## Licence and attribution

MIT. The engine is IdeaVim's, © the IdeaVim authors, MIT-licensed; see `ThirdPartyLicenses.md`.

The name is deliberately not IdeaVim's — "Idea" means IntelliJ IDEA and means nothing here, and a
licence that makes code free to use does not make it free to imply an endorsement with. What the
fork keeps is the attribution, and the ability to read `~/.ideavimrc` unchanged.
