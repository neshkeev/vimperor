# Changelog

The Marketplace renders this file on the extension's page, under Changelog.

## [Unreleased]

### Added

- `vimperor.loadAllConfigs` reads every config file you have instead of only the first. Vimperor
  normally reads one - `~/.vimperorrc`, or `~/.ideavimrc`, or `~/.vimrc`, whichever it finds first -
  the way Vim and IdeaVim each read one. Turn this on and it sources all three, least specific first
  (`~/.vimrc`, then `~/.ideavimrc`, then `~/.vimperorrc`), so the file written for this editor still
  has the last word where two of them disagree. The startup line names them in the order they were
  read. Within a family the first spelling still wins, and a path in `IDEA_VIM_CUSTOM_VIMRC` is still
  read on its own.

  It is a VS Code setting rather than a `set` line because the answer is needed before any config has
  run - an option could only be set in one of the files it decides the order of.

### Fixed

- Editing a config that was not the one in force did nothing and said nothing. All the paths were
  being watched, but a change only counted when it changed the *winning* file - so with a
  `~/.vimperorrc` present, saving `~/.ideavimrc` or `~/.vimrc` was silence indistinguishable from a
  watch that had not noticed. With `vimperor.loadAllConfigs` on, every file in force is now part of
  what a change is measured against, and the question names the one you changed.


- `:set wrap` could unwrap a long line and `:set nowrap` wrap it - the option working backwards.
  VS Code's word-wrap toggle acts on whichever editor has focus and cannot be aimed at another, so
  this extension waited for the file to be the one on screen before pressing it. Waiting for the
  *activation* is what was wrong: an extension is told a tab became active only after it has, so the
  press could arrive once you had already switched on again, and flip the wrap of the file you had
  moved to. That file's wrap was then the opposite of what Vimperor believed, and every `:set wrap`
  after it meant `nowrap`. A wrap asked for in a file that is not in front of you now waits for a
  keystroke in that file, which is the only proof of which editor the toggle will reach.

## [0.0.12] - 2026-10-22

### Fixed

- With `'sidescrolloff'` set, the caret could be seen jumping to the middle of the line and back on
  every keystroke. Keeping the caret that many columns from the window's edges means asking VS Code
  to show a column either side of it, and this extension was asking by sending the real cursor there
  and back - which VS Code is free to paint before the cursor returns. The columns are revealed
  instead, so no cursor moves. Where that cannot be done without also scrolling the window up or
  down - within five rows of the top or bottom - the margins are left alone rather than walked, so
  `'sidescrolloff'` has no effect that close to an edge.

- A characterwise Visual selection can reach the end of the line, which it could not before. The
  clearest case is an empty line: `v` on one selected nothing at all, so `vd` there deleted nothing.
  Selecting a word or a paragraph that ends on a line boundary was off by one character for the same
  reason, and so was `D` on an empty line, `gJ` over a selection, and `zf` in Visual mode folding one
  line too many. IdeaVim carried this as a known divergence from Vim for years - its own tests
  described the results as "off by one because IdeaVim does not allow selecting a newline char" - and
  it is fixed upstream and ported here, with sixteen more of IdeaVim's own tests now replayed against
  this host as a result.

  One consequence is worth spelling out because it looks like a new bug: `d}` on a line followed by a
  blank one now takes that line's newline with it. That is Vim's `:h exclusive` rule for a motion
  that starts at or before the first non-blank and ends in column 1 - it becomes linewise - and this
  extension used to stop one character short.

- A keyboard layout could translate a key twice, turning one command into another. When a mapping
  starts to match and the next key spoils it - `nmap gp ll` declared, and you type `g` then
  something that is not `p` - the keys already typed are replayed, and they were replayed as though
  freshly typed, so `'langmap'` translated them a second time. The three bundled layouts cannot
  reach this, because none of them translates an ASCII character, but a hand-written `'langmap'`
  whose target is also a source does: with `set langmap=ig,gE` the `gg` you typed ran as `gE`.
  Ported from IdeaVim.

- The tutor taught two things the rest of the documentation contradicts. It told you to create
  `~/.ideavimrc`, which was right when that was the only config file and has been the second choice
  since `~/.vimperorrc` arrived - all three families are named now, in the order they are searched,
  with a line saying why the other two are there. And it said Vim plugins were not supported and to
  leave `Plug` lines out, which would have stopped a reader ever trying `set surround`: an arbitrary
  plugin still cannot be fetched, because there is no plugin manager, but twenty-nine are bundled and
  `set <name>` is how you turn one on. Reach it with `:tutor`, `:vimtutor` or `:vimperortutor`.

- A dead key or an input method could delete text, and leaving Visual mode was where it showed. The
  cause is that `type` is not the only command VS Code types through: a composition - what a dead
  key or an IME sends while a character is still being chosen - arrives over four more, three of
  which edit the document, and Vimperor claimed only `type`. So the composed text went straight past
  Vim into the buffer, and the count it carried was the length of the composition's *own* preview
  applied at wherever the caret had since been moved to by Vim commands. A composition twenty-seven
  characters long took twenty-seven characters off the line the caret was on. It waited for the
  `<Esc>` because VS Code cannot apply a composition while a selection is up, which is why it read
  as Visual mode altering the text.

  Composed text is now kept out of the document in Normal, Visual, Operator-pending and at the
  command line, and the character it commits to arrives as a Vim key instead - so `vi'` typed with a
  dead-key quote selects inside the quotes, and `~` typed as a dead key over a Visual selection
  toggles the case. Insert, Replace and Select modes are untouched: that is where composed text is
  meant to go into the document, and where you need to see it while you compose. Ported from IdeaVim
  (VIM-1503).

- `vimperor.trace` logs changes to the document, with the offset, how much was removed and what was
  put there. Every change to the *selection* was already traced in detail and a change to the text
  was not traced at all, so a report of text going missing arrived with no line for whatever had
  removed it.

## [0.0.11] - 2026-10-15

### Added

- Vimperor notices when your config changes on disk and offers to load it. Save `~/.vimperorrc` and
  a notification appears with **Reload** and **Restart Extensions**. Reload re-runs the file in
  place, which is instant and keeps every editor, undo history and unsaved change, while Restart
  restarts the extension host so the config is read exactly as at startup. The difference matters
  and it is Vim's own: re-running a config applies what the file says and cannot undo an option it
  no longer sets, so deleting `set nonumber` needs the restart. `set vimrcreload=autoreload` and
  `=autorestart` pick one without asking at all, `=none` stops the watching, and `prompt` is the
  default.

  Every path in the config order is watched, not just the file that loaded - creating a
  `~/.vimperorrc` while only `~/.vimrc` is there changes which config wins, and so does deleting
  the one that won. A save that rewrote the file without changing a byte asks nothing, which is
  what format-on-save does. It is VS Code's own file watcher, which reaches outside the workspace
  when its pattern carries a base directory, so there is no polling and nothing per-platform.

- **Vimperor: Reload Config** in the Command Palette re-runs your config whenever you ask, without
  waiting to be prompted. It is a contributed command, so `:actionlist` lists it and
  `:action vimperor.reloadConfig` runs it from a mapping like any other.

### Fixed

- `zb` did not always put the last line on the bottom row. Two causes, with the same symptom: a
  `zb` that did nothing at all, with blank rows under the line. VS Code tells an extension which
  lines it painted and never how many rows a window has, so the height every scroll command is
  computed from is worked out from what was painted - and two kinds of report cannot measure it. An
  editor that has just been activated answers from a window it has not laid out yet, reporting a
  range narrower than the window actually is, which looks exactly like a measurement; that one is
  now believed only when the caret is inside it, because a window VS Code is showing has the cursor
  in it. And while the view already reaches the end of the file *every* report is clipped to the
  text, so a window that changed size there - the output panel opening or closing, which an
  extension is told nothing about - can never be measured at all. `zb` hands the placing to VS Code
  in that case, which is the one API that names a position relative to the window rather than to
  the file, and corrects the padding it adds. One correct `zb` is also what teaches the height, so
  the next one is arithmetic again.

- `G` on the last line of a long file left it halfway up the window, with blank rows underneath.
  Arriving somewhere after a jump of a whole window or more does put the line in the middle, which
  is Vim's rule and is kept - but Vim does not do it at the end of a file, where it fills the window
  with the lines above instead and leaves the cursor on the bottom row. Both behaviours are the same
  function in Vim, told apart by one flag, and only one half of it had been ported. `zz` on the last
  line still centres it, with blank rows below, exactly as Vim and IdeaVim do.

- `:action HideAllWindows` put the cursor in the Output panel when the panel came back. Every way of
  showing a closed panel focuses it, and the focus lands a turn or more after the command that asked
  for it returned - after the request for the editor that was sent beside it. So the editor is now
  asked for twice: once straight away, which is all the sidebar and the auxiliary bar need, and once
  after the workbench has settled.

## [0.0.10] - 2026-10-14

### Added

- `:action EditorCloneCaretBelow`, `EditorRight` and `EditorDown` work. All three are IntelliJ's
  names, and an `.ideavimrc` carried over from IdeaVim that maps one of them - `:map <A-n>
  <Action>(EditorCloneCaretBelow)` is the common line - used to report that this VS Code has
  nothing that does it. It does: `editor.action.insertCursorBelow`, `cursorRight` and `cursorDown`.
  The clone is the one worth having, since Vim has no key for a second caret; the two moves are
  there so a config loads rather than because `l` and `j` need help.

- `clever-f`, the twenty-ninth bundled extension and the last of IdeaVim's thirty. `f` pressed again
  repeats its own search, so `;` and `,` are not needed: `fa` goes to the next `a`, another `f` goes
  to the one after, and `F` turns round. A search ends as soon as the cursor moves somewhere else,
  the text changes or an insert starts, after which `f` asks for a character again. Searches cross
  line boundaries, unlike Vim's own `f`, and the characters the search can reach on the cursor line
  are highlighted - in `:highlight CleverFDefaultLabel` if you have defined it, and otherwise in the
  colours your theme gives a search match. `<CR>` at the prompt repeats the last character searched
  for. It works under an operator with Vim's own rules, which is the part that is easy to get wrong:
  `dfa` takes the `a` with it, `dta` stops in front of it, `dFa` searches back. `g:clever_f_*` are
  read - `across_no_line`, `ignore_case`, `smart_case`, `fix_key_direction`, `mark_char`,
  `timeout_ms`, `chars_match_any_signs`, `repeat_last_char_inputs` and
  `not_overwrites_standard_mappings` - and `<Plug>(clever-f-f)`, `<Plug>(clever-f-repeat-forward)`,
  `<Plug>(clever-f-repeat-back)` and `<Plug>(clever-f-reset)` can be mapped by hand. If `sneak` is
  already on it keeps `f`, as in IdeaVim. Ported from IdeaVim.

- `vim-visual-multi`, the twenty-eighth bundled extension and the largest one IdeaVim has. It is
  multiple cursors with a *session* around them, which is what makes the keys inside one worth
  having: `<C-n>` starts a session on the word under the caret, then `n` and `N` add the next and
  the previous occurrence, `q` and `Q` skip and remove a region, `[` and `]` walk between them,
  `<Tab>` switches the regions to plain cursors and back, `i`/`a`/`I`/`A` start Insert mode at every
  region, and `<Esc>` exits - after which `\\gS` brings the regions back. `<C-Down>` and `<C-Up>`
  add a cursor a line at a time, a Visual selection becomes regions with `\\a` or cursors with
  `\\c`, and inside a session `\\z` runs a `:normal` command at every cursor, `\\@` a macro,
  `\\C` a case conversion and `\\a` an align. Outside a session every one of those keys still
  means what Vim means by it. `g:VM_maps`, `g:VM_leader`, `g:VM_case_setting`,
  `g:VM_skip_shorter_lines` and `g:VM_default_mappings` are read, and a changed `g:VM_maps` is
  picked up when an editor next takes the focus. Ported from IdeaVim (VIM-2293).

- `:help` now answers out of Vimperor's own command list instead of opening Vim's website. Every
  command describes itself in one line, and `:help` renders those into a help file in Vim's own
  format - `:help dd`, `:help i_CTRL-W`, `:help v_d`, `:help :s` all jump to their tag, and `:help`
  with nothing shows the start. 691 commands are described. An unknown subject is `E149`, as in Vim.
  The trade is real: Vim's own prose is far richer than one line, but this answers the question a
  Vimperor user actually asks - "does this extension have it?" - offline, instantly, and with no way
  to drift from the code, because it is rendered from the commands rather than written by hand.
  Ported from IdeaVim.

- `keylog`, the twenty-seventh bundled extension: every key you type, in Vim's own notation,
  appended to a file. `set keylog` turns it on and `let g:keylog_file = '...'` says where. Only what
  you pressed is written - a mapping's right-hand side, a macro being played back and a `.` repeat
  are not keys anybody typed, and none of them is logged. It buffers and writes on a timer, so the
  disk is never in front of the caret, and a typed `<` is written `<lt>` so the log can be read back
  as keys. Ported from IdeaVim (VIM-1306).

- Pasting in Replace mode replaces, as Vim does. `R` and then `Cmd+V` (`Ctrl+V` elsewhere) used to
  insert the clipboard and push the rest of the line along, where Vim puts the pasted characters on
  top of the ones that follow the caret. Replacing stops at the end of the line rather than eating
  the break, a newline in the pasted text splits the line and carries on replacing after it, and
  backspace puts the original text back - the paste is recorded in the replace mask one character at
  a time even though it is a single edit. The chord is claimed for Replace mode only; pasting in
  Insert mode is still VS Code's own. Ported from IdeaVim (VIM-1314).

- `:function` lists what a config has defined, which is the command you reach for when a
  `.vimperorrc` has quietly not worked: it runs with errors suppressed, so a `function!` that failed
  to parse left no trace and there was no way to ask. `:function` lists the user-defined functions,
  `:function {name}` answers for one, and `:function /{pattern}` for the ones that match - which is
  also the only way to see a lambda, since the plain listing leaves the `<lambda>N` names out. A
  definition with no `:endfunction` is now `E126` instead of being silently half-made. Ported from
  IdeaVim.

- `|` between ex commands is now the command's own business, the way Vim's `TRLBAR` flag makes it.
  A chain of plain commands already worked, but the commands whose argument may *hold* a bar were
  being cut at the first one: `:normal 0|w` pressed `0` and then ran `:w`, so a line meant to move
  the caret saved the file. `:normal`, `:global`, `:vglobal`, `:command`, `:lockvar`, `:unlockvar`,
  `:help`, `:read`, `:vertical`, `:loadkeymap` and `:!` keep the bar now. In the other direction
  `:s` and `:sort` can be followed by another command - `:%s/a/b/ | nohlsearch` - because their
  delimited sections are counted first, so a bar inside a pattern or a replacement stays in it.
  A failure abandons the rest of the line, as in Vim. Ported from IdeaVim (VIM-748).

### Fixed

- Typing an ex command over a Visual selection could edit the document. `V`, then `:`, then a
  pattern - `:'<,'>s/-` on a line with a `-` in it - and the buffer changed under the prompt.
  While a command line is open the caret in the buffer belongs to the `'incsearch'` preview, which
  borrows it to show where the match is and puts it back when the prompt closes; but a caret or
  selection change the window attributed to *the keyboard* was still being adopted as though the
  user had clicked. The engine's caret followed the window away from the match, and a report that
  carried a selection went further and reset the key handler - which closed the prompt and left
  whatever was typed next to the editor instead. Vim's command line owns the cursor, so the host
  now ignores what the window says about it until the prompt closes.

- `%` jumped to the nearest bracket instead of the matching one, on any line holding two strings.
  A bracket inside a string is text rather than a bracket, so the scan asks whether each one is
  quoted - and it was asking with the code that implements `i"`, which answers a different
  question: with the cursor *between* two strings, Vim's `i"` reaches from the previous quote to
  the next one, across a gap that is no string at all. So every bracket between two strings on a
  line was taken for quoted and never counted. Reported against SQL, where
  `select x->>'a' from f('b.csv')` puts a bracket between two strings on every line; it was never
  about SQL. Block text objects - `di(`, `ci{`, `ya[` - counted the same brackets and are fixed
  with it.

- The third `<C-n>` of `multiple-cursors` added nothing. The search for the next occurrence asks
  the current cursor what it is selecting, and no cursor could be made the current one, so after
  the second it kept searching from the first occurrence and reported "no more matches".

- A paste over a line-wise Visual selection that reached the last line of a file with no trailing
  newline put the text on the end of the line *above* it. `jVjp` on a three-line file turned
  `four five six` and `seven eight` into text appended to `one two three`. Vim appends a line
  there, and that is what happens now; `grr` with a count reached the same bug.

- `:for x in [1, 2]` with a `:continue` in it never finished. In a config that is an editor that
  never finishes starting. The range form, `for x in range(3)`, was never affected.

- `slice()` read an end index of zero as the whole collection: `slice([1, 2, 3], 0, 0)` answered
  `[1, 2, 3]` where Vim answers `[]`. The sublist form `expr[a : b]`, whose end is inclusive, is
  unchanged.

- A search used as a range no longer throws when its pattern is empty. `:/`, `://d` and `:/,+2d`
  failed with an index error; an empty pattern now means the last search pattern, as in Vim, and
  `E35` when there has not been one.

- `histadd('?', ...)`, `histdel('?')`, `histget('?')` and `histnr('?')` reach the search history.
  Vim names it with both of the keys that open it and only `/` was recognised, so all four quietly
  did nothing.

- `gr` over a selection that crosses a line break keeps the rest of both lines. It was treating any
  multi-line range as line-wise, so the text before the start of the range on its first line and
  after the end of it on its last one was deleted too.

- `%` on a line with nothing on it answers no match deliberately rather than by accident, and no
  longer reads past the end of the line.

- A keystroke is no longer written into the file as text. VS Code answers `null` for its own active
  editor while focus flickers out of the editor area and back, and at startup before a restored
  editor has been focused; Vimperor read that answer and, finding no editor, handed the character to
  VS Code - which inserts it. So `0` pressed in Normal mode put a zero in the buffer instead of
  moving the caret to the start of the line, and a `/` typed into a `:%s/ /1/` command landed in the
  file instead of the command line. Keys now go to the editor the host last handled one in, which is
  the editor that still has the keyboard. Named keys were being dropped outright in the same window,
  so an `<Esc>` could leave the mode where it was; they take the same route now.

- `vimperor.trace` now records a key that did not reach an editor. Both of the paths above wrote no
  line at all, so the keystroke was simply missing from the log - which reads like a key that was
  never pressed.

## [0.0.9] - 2026-09-24

### Fixed

- Line numbers no longer disappear. VS Code numbers the gutter without being asked and Vim's
  `'number'` defaults to off, so Vimperor pushed Vim's answer and turned the numbers off in every
  file it was loaded into - and because nobody had set the option, nobody could unset it. Both
  `'number'` and `'relativenumber'` now start at whatever the editor was already drawing, which is
  what `'expandtab'`, `'tabstop'` and `'shiftwidth'` have always done. `set nu` and `set rnu` in a
  config still win over it, `:set nonumber` still empties a numbered gutter, and a gutter set to
  VS Code's every-tenth-line style is left alone by `:set number` rather than flattened.

- A mapping made for Visual mode now runs as a Visual mode command when you trigger it from Select
  mode, which is what Vim does. Select mode replaces the selection with whatever you type, and the
  mapping's keys were being fed back as exactly that - so `:noremap j d` with a selection in Select
  mode did not delete it, it replaced it with the letter `d`. Select mode is handed back afterwards
  unless the mapping ended the selection itself. Ported from IdeaVim (VIM-4326). A mapping written
  with `:snoremap` is untouched, because that one really does mean Select mode.

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
