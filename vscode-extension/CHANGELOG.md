# Changelog

The Marketplace renders this file on the extension's page, under Changelog.

## [Unreleased]

### Added

- `easymotion`: `<Leader><Leader>w` labels every word start on screen, and the next key jumps to
  the one it names. `f`, `t`, `s`, `b`, `e`, `j`, `k`, `n` and their variants work the same way, and
  so they do under an operator - `d<Leader><Leader>w`, with `j` and `k` taking whole lines - and in
  Visual mode. Turn it on with `set easymotion` or `Plug 'easymotion/vim-easymotion'`;
  `g:EasyMotion_keys`, `g:EasyMotion_do_mapping`, `g:EasyMotion_startofline`, `g:EasyMotion_smartcase`,
  `g:EasyMotion_do_shade` and `g:EasyMotion_use_upper` are honoured, and labels can be typed on a
  Cyrillic layout. Written for this fork rather than ported: IdeaVim's is a separate GPL plugin built
  on AceJump, and this follows vim-easymotion, which is MIT.
- Vimperor is published to [Open VSX](https://open-vsx.org/extension/neshkeev/vimperor) as well as
  the Visual Studio Marketplace, so **Cursor, Windsurf, VSCodium** and the other VS Code forks can
  install it by searching for it. They cannot use Microsoft's Marketplace; nothing in the extension
  was specific to VS Code, only its distribution was. Every release also attaches the `.vsix` to its
  GitHub release, for anything that searches neither.

### Fixed

- `zb` did nothing after `zt` on a line near the end of a file. VS Code lets the view scroll past the
  last line, and then reports only the lines it has text for, so a ten-line window looked one line
  tall - and putting a line at the bottom of a one-line window left it where it was.


## [0.0.3] - 2026-09-09

### Added

- `%` on the command line is the current file, and takes Vim's `:p`, `:h`, `:t`, `:r` and `:e`
  filename modifiers - so `:e %:h/other.kt` opens a file beside this one and `:!wc %` counts this
  one. `:!` expanded `%` before, to a string beginning `file://` that no shell could use.
- `<Tab>` on a file argument completes a file name, through a `%` and on into a directory. It had
  never done anything: the engine asked the host to list a directory and the host answered nothing.
- `"%` and `"#` hold the current and alternate file names, and `:registers` lists them. Both
  characters were accepted as register names before and neither ever had a value.
- The tag stack reaches `<C-]>` and `<C-T>`. A `tags` file and its ex commands were already here;
  the two keys were not connected to them - `<C-T>` popped the *jump list*, which is a different
  stack and only looks the same immediately after a jump. `'tagstack'` turns the recording off.
- `'keyboardlayout'` gives Vim's `'langmap'` the table it has always been missing: `set
  keyboardlayout=russian` (or `ukrainian`, or `belarusian`) and Vim commands work with a Cyrillic
  layout active - `вфц` is `daw`, `Эйнн` yanks into register `q`, `ьф` sets mark `a`. Insert mode,
  search patterns and `:` commands stay untouched, so text you type is still the text you meant.
  `'langmap'` still works and wins where the two disagree. The keys whose Cyrillic position emits
  ASCII - `$`, `^`, `@`, `&`, `/`, `?`, `|` - are deliberately left out, so that `.`, `:` and `;`
  keep working for anyone who also types in Latin; the README has the line to add them.
- A whole `:` command line typed in the wrong keyboard layout is corrected and echoed: `:ыуе
  тщцкфз` runs `:set nowrap`. Only when the line holds no Latin letter at all, the command as typed
  is not a command, and the corrected one is - so `:s/привет/пока/` and `:w привет.txt` are
  untouched.
- Three more of IdeaVim's bundled extensions, taking it to twenty-five of twenty-seven:
  `textobj-line` (`al` and `il` - the line with and without its indentation), `visual-star-search`
  (`*` and `#` in visual mode search for the selection) and `signature` (your `a`-`z` marks drawn
  in the gutter).

### Fixed

- `y<C-V>` threw instead of yanking a block, on any block taller than one line.
- `gf` and `<C-R><C-F>` threw on the last character of a file.
- `daw` on the first character of a file threw.
- `g8` threw when there was no character under the caret, and reported the code point where Vim
  reports the character's UTF-8 bytes.
- `gx` recorded a jump. It does not move the caret, so it only reset a `<C-O>` traversal.
- `r` in visual mode left the caret at the end of the selection rather than the start.
- `gv` restored a selection one character too wide.
- `:normal` run from insert mode - which is what an autocmd fired by an insert does - fed its keys
  into insert mode as text, and did not return to it afterwards.
- `<NL>`, `<NewLine>`, `<LineFeed>` and `<LF>` were inserted as literal text instead of being read
  as keys, and `:execute "normal! ..."` ended early at a newline in its argument.
- A delete or yank whose range ended immediately before real text was wrongly promoted to linewise.
- `<C-R><C-W>` on the command line inserted at the caret rather than at the offset it was given.
- A config line that will not parse now reports `E492: Not an editor command: ...` rather than the
  parser's own diagnostic and its list of every token that would have been legal.


## [0.0.2] - 2026-09-08

**No change to the extension.** Everything that runs is byte-for-byte 0.0.1: the only files that
moved between the two are this one and `PUBLISHING.md`, which is not shipped.

It exists to prove the release workflow end to end. 0.0.1 was published by hand because the
automated path had never been run - and when it was finally run it failed twice, on a test step
excluding two Gradle projects that no longer exist and on a Node executable resolved by asking a
cold runner for a file it had not downloaded yet. Both are fixed, and a `workflow_dispatch` has
gone green, but a dispatch skips the one step that matters most and cannot be rehearsed: the
publish itself. This release is that step.

## [0.0.1] - 2026-09-08

Published by hand rather than by the release workflow, so there is no `vimperor-v0.0.1` tag and
there must not be one: the workflow publishes on a `vimperor-v*` tag, and the Marketplace refuses
a version it already has. Every release after this one goes through the tag.

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
