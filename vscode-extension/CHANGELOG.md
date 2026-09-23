# Changelog

The Marketplace renders this file on the extension's page, under Changelog.

## [Unreleased]

### Fixed

- `:set syntax` and `:set filetype` change one document instead of all of them. Setting the syntax
  in an untitled buffer turned every other open file into that language as you visited it, saved
  files included. The indent options - `'expandtab'`, `'tabstop'`, `'shiftwidth'`, `'softtabstop'` -
  were being reset by the same path and were simply harder to notice. All of these are local to the
  *buffer*, and Vimperor was keying buffer-local state by the editor object rather than by the
  document; VS Code hands out a fresh editor object whenever a hidden tab is shown, so every tab
  looked like a brand-new buffer and took its options from the global values. They are keyed by the
  document now, so a tab switch changes nothing, two views of one file agree, and closing a file
  forgets its buffer the way Vim does.

- ...and a language change is no longer mistaken for a closed file. VS Code fires the same event
  when a document is closed and when its language changes, and it works out the language of an
  untitled buffer by itself - so typing Java into a new tab made Vimperor forget everything it knew
  about that buffer, and `:set syntax` in another tab then reached it anyway. The word wrap was
  being lost the same way. Both are kept now; only the cached symbols are dropped, which is the one
  thing a language change really does invalidate.

## [0.0.8] - 2026-09-23

### Fixed

- `:action HideAllWindows` puts back only what it hid. It used to close the sidebar, the panel and
  the secondary sidebar and then, on the next press, open all three - so a panel you had
  deliberately left closed came back with the rest. Now it hides whatever is open, remembers
  exactly that, and restores exactly that; if you reopen something by hand, the next press hides it
  again rather than treating the press as a restore. This is what the same action does in IntelliJ.
  VS Code has no API that reports which of those three areas are showing, so the layout is read
  from the `when` clause on the Enter key that runs the command - which means it is exact when you
  type `:action HideAllWindows` at the prompt, and falls back to the old all-or-nothing alternation
  when the action is run from a mapping or from `<Action>(HideAllWindows)`. The restore also stopped
  using toggles, so it can no longer close something you opened while everything was hidden, and it
  hands focus back to the editor when it is done.

- Vim's messages go to the status bar, and a failed search no longer opens the output panel. A
  mistyped `/` used to throw the panel open over your code to say `E486: Pattern not found`, and
  the panel then stayed open. That one line now appears at the bottom of the window, in red, next
  to the mode - along with everything else Vim says on its message line: the `/pattern` echo,
  `search hit BOTTOM, continuing at TOP`, `1 match on 1 line` and the rest, about seventy messages
  that until now had nowhere to appear at all. The Vimperor output channel still receives every one
  of them, because it is the log a bug report is pasted out of, but it no longer opens itself: a
  panel that was hidden when a command ran is hidden after it. `:registers`, `:marks` and `:!cmd`
  are unchanged - that is output you asked to see. Hover the message to read a long one in full.

## [0.0.7] - 2026-09-22

### Fixed

- The wrap is per file now, and switching tabs no longer changes it. `'wrap'` is window-local in
  Vim, and Vimperor was writing it to `editor.wordWrap` - one setting for every editor showing the
  language - so `:set wrap` in one tab, a switch to the next and back, and the first tab had
  stopped wrapping with nothing said. Worse, that setting outlives the session: a window opening on
  a file that wrapped *because Vimperor had written it there* could decide it did not wrap and turn
  it off on arrival. `'wrap'` is now the editor's own word wrap - the one `Alt+Z` sets, which VS
  Code keeps per document - so each file keeps its own answer, `:set wrap?` always reports what the
  file is drawn with, and nothing is written to `settings.json` at all. A file opened from a window
  where you typed `:set wrap` wraps too, as Vim means it to, and `set nowrap` in a `~/.vimperorrc`
  now reaches every file rather than the first window. `Alt+Z` is claimed so that your own toggling
  is kept up with; *View: Toggle Word Wrap* from the Command Palette still cannot be seen, and the
  README says what that costs.

## [0.0.6] - 2026-09-17

### Added

- `'sidescrolloff'` (`'siso'`): with `set sidescrolloff=10`, a caret that goes off screen sideways in Normal,
  Visual or Select mode comes back ten columns from the edge rather than at it. The window scrolls in up
  to three quick steps, because VS Code can only be asked to show one column at a time. The margin is
  capped at 40 columns, so large values do not centre the caret the way they do in Vim: that would
  need the window's width, which VS Code does not tell an extension.
- `zl`, `zh`, `zL`, `zH`, `zs` and `ze` scroll the window sideways (with `nowrap`). `zL` and `zH` move it
  40 columns, because VS Code does not say how wide the window is. Unlike Vim, the caret is not moved
  when the scroll leaves it off screen; the next key that moves it brings the window back.

### Fixed

- `:set wrap` and `:set nowrap` were written to the user's settings for a file in no folder - an untitled
  tab - even when a folder's or workspace's settings held the value. Those layers win, so the write
  changed a file and nothing on screen. The wrap is now written into the layer the value comes from.
- `:set wrap` could do nothing in one tab, for as long as that tab stayed open. `editor.wordWrap` is one
  setting for every window, so turning the wrap off on another file of the same language moved it
  underneath - and Vimperor refused to write a value it remembered writing, however long ago. The
  memory now expires after two seconds. With `vimperor.trace` on, what the wrap read, wrote and where
  it wrote it now reaches the trace.
- With `nowrap`, scrolling the window sideways with the mouse and then pressing `j`, `zt`, `zz`, `zb` or
  any other key that moves the caret or scrolls left the caret off screen. In Normal, Visual and
  Select mode the caret is now brought into view on both axes the way VS Code brings a clicked cursor
  into view: with the least scrolling, and without moving the window up or down when the line is
  already visible. Insert mode, several carets and the command line keep the previous behaviour, which
  cannot reveal the caret on the few lines nearest the window's top and bottom edges.

## [0.0.5] - 2026-09-16

### Added

- `let g:commentary_block_comments = 0` makes `gc` always comment whole lines, as vim-commentary does.
  By default a range inside a line gets a block comment, so `gciw` comments out a single word.
- `'autoindent'` (`'ai'`), on by default. With it off, `o`, `O`, Enter, `cc` and `S` start the new line in
  column 0.

### Fixed

- `o` on the last line scrolled the file up, even with half the window empty below it. VS Code
  reports only the lines it has text for, so a tall window over a file that ends above its bottom row
  looks exactly like a window with no room left, and the new line looked off screen in both. Where
  the two cannot be told apart, Vimperor now asks VS Code to bring the caret into view, which scrolls
  by the minimum, or not at all.
- `<C-W>q` did nothing. It closes the window now, as `<C-W>c` does.
- `<BS>` after `:startreplace` or `:startgreplace` moved back over the typed text without putting back
  the characters it had replaced, as it does after `R`.
- `o<Esc>`, `O<Esc>`, `cc<Esc>`, `S<Esc>` and Enter then `<Esc>` left the new line holding nothing but its
  indent - trailing white space on every press. As in Vim, an indent you typed nothing after is taken back when
  you leave Insert mode, and a line you press Enter on again comes out empty, so `3o<Esc>` opens three empty
  lines. White space you typed yourself is never touched.
- `cc` on a file's only line lost its indent, `cc` on the last line after an empty line opened the new line
  in the wrong place, and Visual `c` over several lines took the indent of the last line rather than the first.

## [0.0.4] - 2026-09-11

### Added

- `easymotion`: `<Leader><Leader>w` labels every word start on screen, and the next key jumps to
  the one it names. `f`, `t`, `s`, `b`, `e`, `j`, `k`, `n` and their variants work the same way, and
  so they do under an operator - `d<Leader><Leader>w`, with `j` and `k` taking whole lines - and in
  Visual mode. Turn it on with `set easymotion` or `Plug 'easymotion/vim-easymotion'`;
  `g:EasyMotion_keys`, `g:EasyMotion_do_mapping`, `g:EasyMotion_startofline`, `g:EasyMotion_smartcase`,
  `g:EasyMotion_do_shade` and `g:EasyMotion_use_upper` are honoured, and labels can be typed on a
  Cyrillic layout. Labels are badges in your theme's own colours, and `:highlight EasyMotionTarget`,
  `EasyMotionTarget2First` and `EasyMotionShade` recolour them. Written for this fork rather than ported: IdeaVim's is a separate GPL plugin built
  on AceJump, and this follows vim-easymotion, which is MIT.
- Vimperor is published to [Open VSX](https://open-vsx.org/extension/neshkeev/vimperor) as well as
  the Visual Studio Marketplace, so **Cursor, Windsurf, VSCodium** and the other VS Code forks can
  install it by searching for it. They cannot use Microsoft's Marketplace; nothing in the extension
  was specific to VS Code, only its distribution was. Every release also attaches the `.vsix` to its
  GitHub release, for anything that searches neither.

### Fixed

- Switching to another tab in Visual mode took Visual mode with it: `j` there went on selecting in
  the new document, and back in the first tab its lines stayed selected, with `<Esc>` unable to clear
  them - only a click could. Visual and Select mode now end in the editor you leave, the way `<Esc>`
  ends them, so `gv` back there restores the selection; a `:` prompt opened from Visual mode is
  closed too. The same document shown again, or in a split, keeps it.
- When a selection change from outside the keyboard - a click, or another extension - ended Visual
  mode, the status bar went on saying VISUAL until the next key, so the selection seemed to vanish
  for no reason. The indicator follows the mode now, and `vimperor.trace` records every selection
  change VS Code reports, what caused it, and what Vimperor made of it - except in the Output panel
  the trace is written to, where each line it wrote caused another.
- `:'<,'>s/pattern/` over selected lines threw you out of the command line at the first character of
  the pattern, into Visual mode with a different selection. With `'incsearch'` on, the preview was
  meant to drop the selection and did not, so it dragged the selection to the first match, which
  would also have narrowed `'<,'>` to that one line; and VS Code's report of that change was taken
  for a selection you had made yourself.
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
