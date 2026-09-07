# Changelog

The Marketplace renders this file on the extension's page, under Changelog.

## [Unreleased]

First release. Vim for VS Code over IdeaVim's `vim-engine`, compiled to JavaScript.

- Normal, insert, visual, visual block, select, replace and operator-pending modes, with counts,
  registers, macros, marks, jumps, the change list, text objects, `.`, undo and redo.
- Search with `'incsearch'` and `'hlsearch'`, `:substitute` and its flags, `:global`, `:normal`,
  `:sort`, `:uniq`.
- Around 380 ex commands and 170 Vimscript builtin functions.
- More than a hundred options, read from the first config found of `~/.vimperorrc`, `~/.ideavimrc`
  and `~/.vimrc` - so an existing Vim or IdeaVim setup needs nothing copied or renamed. XDG
  locations included.
- `<Action>(id)` and `:action` to run any VS Code command; `:actionlist` to find one, with the
  keyboard shortcut it already has.
- `:sign`, `:highlight`, `:match`, `:redir`, `:messages` and the quickfix and location lists.
- Twenty-two of IdeaVim's twenty-four bundled extensions: `ReplaceWithRegister` (`gr`, `grr`),
  `vim-paragraph-motion` (`{` and `}` stopping at whitespace-only lines), `textobj-entire`
  (`ae`, `ie`), `mini-ai` (`ci(` from anywhere on the line), `CamelCaseMotion`, `indentwise`,
  `textobj-user` — `textobj#user#plugin()`, for declaring text objects of your own — `targets`
  (seeking text objects: `cin(` for the next pair, `cil(` for the last), `abolish` (`crs` `crc`
  `crm` to recase a word, and `:S`, a substitute that carries the case of what it replaced) and
  `textobj-indent` (`ai` `ii` `aI` — the block at the caret's indentation level), `argtextobj`
  (`ia` `aa` — one argument of a call), `commentary` (`gc{motion}`, `gcc`, `gcu`, `:Commentary`) and
  `highlightedyank` (the text a yank covered flashes), `exchange` (`cx{motion}` marks a region,
  `cx` over a second one swaps the two) and `sneak` (`s{char}{char}` jumps to the pair, `S`
  backwards, `;` and `,` repeat) and `surround` (`ys{motion}{char}` wraps, `cs{from}{to}` changes
  what wraps, `ds{char}` unwraps, `S` in visual) and `multiple-cursors` (`<C-n>` puts a caret on the
  next occurrence, `<C-x>` skips one, `<C-p>` takes the last back), `yankring` (`<C-P>` and `<C-N>`
  cycle back through what you have yanked, replacing the paste in place), `functextobj` (`am` `aM`
  `im` — a function, from the language server's own symbol tree), `classtextobj` (`ac` — a class),
  `NERDTree` (`:NERDTree`, `:NERDTreeToggle`, `:NERDTreeFind`, and seventeen of its keys inside the
  Explorer) and `youcompleteme` (`<Tab>` cycles the completion list rather than accepting from it).
  Each is enabled by `set <name>`, by `:Plug`, or by `:packadd` — the three ways IdeaVim's own
  documentation and a borrowed Vim config use.

  The two that are not here are `matchit` and `VimEverywhere`, and neither is waiting on anything:
  `matchit` needs to know what a *token* is and VS Code only exposes a symbol tree, and
  `VimEverywhere` labels every clickable component in the IntelliJ window through the Swing
  accessibility tree, which the VS Code workbench has no equivalent of.

- `gf` opens the file named under the caret, using `'isfname'` to find the name and looking beside
  the current file and then under the working directory, as Vim's default `'path'` does. IdeaVim
  has no `gf`; this is the first key Vimperor adds that it does not have.

- `'expandtab'`, `'tabstop'`, `'shiftwidth'` and `'wrap'` reach the editor rather than being
  accepted and ignored. Each is seeded from VS Code's own answer for the file, so a user who has
  set nothing keeps the indentation VS Code would have used, and `>>` agrees with pressing Tab.

- `q:`, `q/` and `q?` open the command-line window: the history in an editable buffer, where `<CR>`
  runs the line under the caret.
