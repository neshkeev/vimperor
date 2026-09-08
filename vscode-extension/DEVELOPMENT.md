# Vimperor - how it is built

The long account, for somebody reading the source. `README.md` next to this file is the short one,
and it is the page the Marketplace shows; this is the one that says *why*, and it is deliberately
kept out of the published package.

Vim for VS Code, powered by IdeaVim's engine: its `vim-engine` compiled to JavaScript and loaded
as a VS Code extension.

That is the whole of what makes this different from the extensions already in the marketplace. They
are either a Vim written from scratch in TypeScript, or a real Neovim running as a subprocess. This
is neither: it is a mature Vim implementation - the one IdeaVim ships, with its Vimscript parser,
its regex engine and its 1,049 test fixtures - running in the extension host with nothing between
it and the editor.

The name is not IdeaVim's, deliberately. "Idea" means IntelliJ IDEA and means nothing here, and the
engine being MIT-licensed makes it free to use but not free to imply an endorsement with. So the
extension has its own name and its own config file, `~/.vimperorrc`. What it keeps is the
attribution, and the ability to read `~/.ideavimrc` unchanged when there is no `~/.vimperorrc` -
that is the config this engine has always read and the one its users already have, and a new name
is no reason to make anybody copy it.

## Running it

```bash
./gradlew :vscode-extension:assembleExtension
code --extensionDevelopmentPath="$PWD/vscode-extension"
```

`package.json` points `main` directly at the build output, so there is no packaging step: build,
then reload the extension host window. Type in any editor and Vim's mode appears in the status bar.

The extension takes over VS Code's `type` command, which is the only way for an extension to see
ordinary typing - keybindings cover named keys, not letters. That also means no other Vim extension
can be enabled at the same time: `type` has one owner.

Named keys arrive as keybindings instead, each carrying the Vim notation it stands for, so the
manifest and the engine agree without a lookup table in between. Escape, backspace, delete, enter,
tab and the arrows are bound; enter, tab and the vertical arrows step aside while the suggest widget
is up, because accepting a completion is what those keys mean there.

Tab also steps aside for ghost text - Copilot's inline suggestions and inline edits - so that Tab
accepts a suggestion when one is showing and is Vim's otherwise. When it is Vim's, it indents the
way the file does. A `when` clause is the only way to
do this: context keys are write-only for an extension, so the extension cannot ask whether a
suggestion is up, and VS Code has to decide before the key is dispatched. Handing the key back
rather than reimplementing the decision also inherits VS Code's own rule about indentation, which
is that Tab indents instead of accepting when the suggestion is indented further than a tab stop.

Escape is *not* guarded this way, and that is a decision rather than an omission: dismissing ghost
text with Escape also leaves Insert mode. Guarding it would mean Escape stops leaving Insert mode
whenever a suggestion happens to be up, which is a worse trade - the suggestion goes away when
Insert mode ends anyway, and a mode you cannot reliably leave is the one thing a Vim user cannot
work around.

`ctrl+r` is the one control key bound, and it takes *Open Recent* away from an editor with focus.
It is here because it is Vim's redo and there is otherwise no way to reach redo at all - the `:`
prompt is not built. Every other control key is a decision of the same kind, made one at a time
rather than as a sweep.

## What works

Normal-mode editing and insert mode: motions, `x`, `d` with a motion, `c`, `i`/`a`/`I`/`A`,
`o`/`O`, counts, registers, `u` and `<C-R>`. Visual mode works in both directions: `v`, `V` and
motions drive VS Code's selection, and dragging with the mouse enters visual mode, because in Vim a
selection *is* a mode. The `:` and `/` prompts work, with history, so `:s`, ranges and search are
all reachable the way a user reaches them.

Vim's command line turned out not to need VS Code's `showInputBox` at all. It is not a dialog that
collects a string - it is a text buffer the engine owns keystroke by keystroke, with its own caret
and history, so the host supplies a string and somewhere to draw it. Both are synchronous. It is
drawn on the status bar, next to the mode.

`:s///c` - the substitute that asks before each replacement - had been written off for the same
reason and turned out to be the same mistake. It is a *modal input* rather than a command line: no
text buffer, and one keystroke is the whole answer. The engine asks on every keystroke whether a
prompt is open and routes the key to its interceptor, so the host draws a label and remembers which
prompt is up. `y`, `n`, `a`, `q` and `l` all work.

What genuinely cannot be done is the other half of that interface. `activate` blocks on a modal
event loop until a key arrives, and JavaScript has one thread and no way to stop it.

That used to be recorded here as the reason IdeaVim's extensions could not be ported, and it was
wrong twice over. `getchar()` is a Vimscript function nothing here calls; what two extensions did
call was `injector.keyGroup.getChar`, which blocks. The answer was not to block but to stop needing
to: `readKeys` in the engine reads through *modal input*, the same non-blocking route `:s///c` asks
its question by, and `surround` and `sneak` are both ported. The cost is that an extension returns
before it knows what was typed and continues a keystroke or two later.

`:!cmd` runs a shell command and `:%!sort` filters the buffer through one, which is the third time
the *synchronous* Node API has been the answer where the VS Code one was the wrong shape - after
`readFileSync` and `writeFileSync`, now `child_process.spawnSync`. Every other way of running a
command from an extension is a promise or a terminal, and the engine needs the output before it can
replace the lines it handed over. The shell comes from `'shell'` and `'shellcmdflag'`, which are
Vim's options rather than the host's.

Those two - `:!` and `:read` - were missing without ever appearing on a list, and that is worth more
than the commands themselves. The ex command sweep walks the *engine's* registry, and IdeaVim
declares eight commands in its IntelliJ module instead. A command that was never registered is not a
hole in the sweep's eyes; it is not anything. `:!` and `:read` had no IntelliJ in them worth the
name - a process and a file read, both of which the engine already asks a host for through an
interface - so they moved into `vim-engine`. `ExCommandsOnlyInIntelliJTest` lists what
remains and says what each of them needs. `:help` went the same way afterwards - it opens the online
Vim documentation in a browser, and which browser is a host question that `gx` was already asking.

`:ls`, `:files`, `:buffers` and `:buffer` moved for a different reason: the note saying they could
not was wrong. It read "VS Code has tabs rather than buffers, and its tab model does not carry the
modified/loaded state Vim prints in that table". A tab holding one file instead of a buffer is a
real difference and not one this table cares about; the second half was simply not true. `Tab` has
carried `isDirty` since 1.68 and `isActive` beside it, which are Vim's `+` and `%` - the two flags
anyone actually reads `:ls` for. `window.tabGroups` is also the rare workbench API that answers
synchronously, which is what makes reading it possible at all. That is now three of these notes that
turned out to be about which API had been looked at rather than about VS Code, so a line on that list
is a claim to be checked rather than a decision already made.

Two of Vim's columns stay empty and say so. There is no read-only flag on a tab, so `=` never
appears; and `#`, the alternate file, is something VS Code will *go* to - that is how `<C-^>` works -
and will not name. Guessing either would be worse than a blank. The list also fixed a disagreement
nobody had noticed: `:buffer 3` used to send `workbench.action.openEditorAtIndex3`, which counts
within one editor group and stops at nine, so it and the third row of `:ls` were not necessarily the
same file. Both read the same list now, which is the only thing that makes a buffer number mean
anything. `:bdelete N` works for the same reason.

`gx` is the third shape of blind spot, and worth naming beside the other two. The key sweep presses
every key on a buffer of ordinary text, so `gx` returns before it ever asks for the service that
opens a URL. A hole that needs the right *text* under the caret as well as the right key is
invisible to a sweep that only varies the key.

Your config is read at startup, so mappings and options come from the file you already have. The
order is `IDEA_VIM_CUSTOM_VIMRC`, then three families, each searched to the end before the next one
starts: `vimperor`, `ideavim`, and Vim's own `vimrc`. Each family is the dotted name in `$HOME`, the
underscored one, and the XDG directory - and Vim's adds `~/.vim/vimrc`, which is one of the three
locations `:h vimrc` lists.

A family at a time rather than a location at a time, because the name is the intent: a file called
`vimperorrc` was written for this editor, and should not lose to an `.ideavimrc` that happens to sit
somewhere searched earlier. Only the first file found is read; a `~/.vimperorrc` that wants the rest
says `source ~/.ideavimrc` on its first line.

Reading `~/.vimrc` is what lets somebody who has never installed IdeaVim get a working editor with
nothing to copy or rename. Their file will contain a plugin manager, autocommands and `syntax on`,
none of which exist here - and **none of which say so**, because `executeFile` runs a config with
`indicateErrors = false`. That is IdeaVim's behaviour and it is right for a file that runs before
there is a window to complain in, but it means a config that half worked is indistinguishable from
one that worked. `isVimsOwnConfig` exists for that: when the file found is Vim's own, startup says
in the output channel that unimplemented lines were skipped. A line that fails does not stop the rest, the way Vim carries
on after an error in a vimrc.

Files are read through Node's `fs` rather than VS Code's `workspace.fs`, for the same reason the
command line does not use `showInputBox`: `readFileSync` returns the contents and `workspace.fs`
returns a promise, and `:source` has to return with the file. That holds over SSH and in dev
containers, where the extension host runs on the remote machine and reads the config that is
actually there. A web-only workspace has no Node and will need an asynchronous load at startup.

`'hlsearch'` paints every match, in the editor's own find colours so it looks right in whatever
theme you use, and `'ignorecase'` and `'smartcase'` both apply. `'incsearch'` moves the caret to the
match as you type and puts it back if you cancel - which is also what makes `ve/dolor` extend the
Visual selection to the match, since moving a caret in Visual mode is what moves the end of a
selection. It is worth saying why it was written off first: the preview needs the pattern as typed
so far, and that arrives on the command line rather than through the search group. Both halves are
true and the
conclusion was wrong. That reasoning was about IntelliJ's command line, which is a text field with a
document listener; this one is a buffer this module owns keystroke by keystroke, so the pattern as
typed so far is a string it already has. The note was never revisited after the command line stopped
being a widget.

What is deliberately missing from it is Vim's cursor preview. Vim moves the caret to the match while
you type and puts it back if you cancel; this scrolls the match into view and leaves the caret where
it is. The caret is engine state, and a host that moved it would be lying to the engine about where
the user is for as long as the prompt was open.

`"+y` and `"+p` use the system clipboard. VS Code will only talk about it in promises, and `"+p` is
a register read in the middle of a command, so it answers with what the clipboard last said and
re-reads whenever the window regains focus - which is what a user copying in a browser and switching
back does. Copying elsewhere *while* VS Code has focus and pasting without clicking away is the case
that stays wrong; fixing it means making paste asynchronous, which is a change to the key path.

`"*` and `"+` are the same clipboard here, on every platform including X11. In Vim `"*` is the
primary selection - the one middle-click pastes, separate from the clipboard - but reaching it takes
an X connection, and `env.clipboard` is the whole of what an extension gets.

`'clipboard'` decides whether a plain `y`, `d`, `c` or `x` goes there too. `unnamed` and
`unnamedplus` both do that; `autoselect`, `autoselectml` and `autoselectplus` are accepted and do
nothing, because they publish the visual selection as it is made and the store they publish to is
meant to be separate from the clipboard - doing it to the clipboard would wipe it on every `v`.
`html` and `exclude:{pattern}` are X11 and GUI concerns and are no-ops for the same reason, which is
what Vim does on a build without `+X11`. `ideaput` is IntelliJ's.

`'number'` and `'relativenumber'` work, with their short forms: they write `lineNumbers` on the
editor, which VS Code lets an extension set per window - exactly what a window-local Vim option is.
Vim has four states there and VS Code has three, so `set rnu` without `nu` gets the absolute number
on the caret's line rather than a `0`.

Getting that to hold for a file opened *later* is most of the work, and it is not about line
numbers. A window-local option belongs to one window, and Vim carries it from the window you opened
from - evaluating the config in the context of the first window, which this host does not have,
because VS Code activates an extension before it restores the editors. So the config runs against a
hidden fallback window and the first real editor is initialised from it, and every editor after that
from the one that was active. Before that, every editor was initialised to the option defaults and
`set nu rnu` numbered exactly one window.

The gutter is written after every keystroke, and only when the editor is not already showing what
it should be - writing an editor option is a round trip, and doing it per keystroke would be fifty
for a typed word. The comparison is against what the editor is *showing*, not against what this
host last wrote, and that distinction is the whole of a bug: `TextEditorOptions` belong to VS Code
and it resets them without asking - re-showing an editor that was hidden does it, and so does a
change to the `editor.lineNumbers` setting. A memory of our own writes cannot see that happen, so
it went on saying "already relative" while the gutter sat empty and never wrote again for the life
of that editor. The editor's own answer cannot drift from the editor.

`'filetype'` and `'syntax'` work too, and they are one setting here: VS Code's language mode does
both jobs, so `set syntax=java` gets Java's language server as well as its colours - more than Vim
would have done, and what a VS Code user means by it. `:setfiletype` is the same thing.

The rest of a `~/.vimrc` is accepted rather than implemented, and that distinction is the whole of
`VsCodeOptions`. `set expandtab`, `set laststatus=2` and `syntax on` name things VS Code has already
decided - indentation it resolves per language and per file, a status bar and a highlighter it draws
itself - and an option or command that is not declared is not ignored, it is `E518` or `E492`. A
`~/.vimrc` sourced from an `.ideavimrc` is thirty of those, which is a config that stops being read.
So 134 options and 12 commands are declared, do nothing, and say nothing; the exceptions are the
forms that ask for something VS Code contradicts, like `:syntax off` and `:colorscheme desert`,
which say so. The count keeps falling - it was 21 until `:cd` and `:highlight` were written. The
cost is honest: `:set expandtab?` answers with what was set rather than with what the editor is
doing. IdeaVim's `IjOptions` has the same group for the same reason.

Vim's command modifiers are implemented, in `vim-engine`, so both hosts have them.
`silent! colorscheme solarized` is the standard way a portable config guards something optional, and
without it such a line does not merely fail, it is reported. Vim documents these as modifiers rather
than commands and that reads like a job for the grammar; it is not one. A modifier is spelled like
any other ex command and its argument is the command it modifies, so `:silent`, `:verbose`,
`:noautocmd` and `:lockmarks` are four ordinary `@ExCommand` classes that set one thing, run the
rest of the line, and put it back. What each one sets is real: `:silent` a suppression the messages
and the output panel consult, `:noautocmd` a flag `handleEvent` reads, `:lockmarks` and
`:keepmarks` a flag that stops a mark following the text, `:keepjumps` one that stops
`saveJumpLocation` recording, `:keeppatterns` one that stops a search remembering its pattern,
`:verbose` the `'verbose'` option. `:keepalt` is the exception and says so: the alternate file is
IntelliJ's last tab and VS Code's previous editor, decided outside anything the engine could
suppress, so it runs the command and the alternate file changes anyway.

`:vertical` and `:horizontal` name an axis, and an axis is something both hosts have: a split is
either beside the window or below it. `:vertical split` splits beside, `:horizontal vsplit` splits
below - the modifier wins over the name, which is the whole reason Vim has both spellings. This is
also where `:vertical` moved out of the IntelliJ plugin: IdeaVim's copy accepted `:vertical resize`
and reported `E492` for anything else, so it was not really the modifier; `:resize` reads the flag
now, and both hosts get the general case.

The five that name a *place* - `:topleft`, `:botright`, `:aboveleft`, `:belowright`, `:tab` - run
the command and let the host put the window where it puts windows. Neither IntelliJ nor VS Code
lets an extension choose a corner, and `botright split` in a mapping is asking for a split, mostly.
`:confirm`, `:sandbox` and `:noswapfile` are the same shape: promises about *how* a command runs
that this fork cannot keep, where the host is already keeping its own version of two of them.

`:filter /pat/ {command}` is real, and reads at the same place `:silent` does: the output panel
keeps the lines the pattern matches, or the ones it does not for `:filter!`. The unit is the line,
as it is in Vim, so `:filter /vim/ registers` prints the rows of that table that mention vim.
`:unsilent` is the way out of a `:silent` wrapped round a whole block.

The `keep*` four were not on the measured list of missing commands, because they never reported an
error - they were worse than missing. `:k{mark}` is spelled with no space, so the catch-all parser
rule turned any name starting with `k` into a mark command, and did it before asking the registry
back when `:k` was the only command that started with one. `:keepjumps {cmd}` therefore set a mark
named `e` and ran nothing, in silence. The registry is asked first now, which is the order Vim
resolves in, and `:ka` is still a mark.

`:unlet` is implemented too, in the engine beside `:let`: `:unlet {name} ...` removes each name in
any scope, `E108` for one that was never set, and `:unlet!` for the form a config that may be
sourced twice actually writes.

`:bfirst`, `:blast`, `:pwd` and `:startinsert` are in the engine too. The first two are Vim's
buffer-list names for `:first` and `:last`, which are one command here because neither host keeps an
argument list separate from the files it has open. `:pwd` prints the folder open in the window -
VS Code has no current directory, it has a workspace, and that folder is already what a relative
path is resolved against here, so `:pwd` prints the truth and `:cd` is the one that has to say it
cannot. `:startinsert` is `i` and `:startinsert!` is `A`.

The windows and buffers are there too. `:enew` is VS Code's untitled file, which is Vim's new
buffer under another name - unnamed, unsaved, and in the group that is focused. `:new` and `:vnew`
are that with a split in front, and with a name they split first and open afterwards, which is the
thing `:split file` still says it cannot do: opening into the group that is *now* focused needs
nothing VS Code does not have. `:tabnew` and `:tabedit` are the same again, since an editor opened
by name gets a tab of its own either way.

`:wincmd` is not a table of arguments but the keys themselves: Vim defines `:wincmd {arg}` as what
`CTRL-W {arg}` does, so the argument is appended to `<C-W>` and handed to the key handler. It
therefore answers for exactly the `<C-W>` commands this host has, and for any added later without
being touched. `:2wincmd l` is `2<C-W>l`, as it is in Vim.

`:bufdo`, `:windo`, `:tabdo` and `:argdo` are one walk. Vim has four because it has four lists -
buffers, the windows of a tab page, the tab pages, the argument list - and neither host here keeps
those apart, so all four run the command against every editor that is open and say so rather than
three of them quietly doing nothing. Vim moves you to each in turn and leaves you in the last;
nothing here changes focus, which is what you want from `:bufdo %s/old/new/ge` and the only thing a
host whose focus changes asynchronously can do without reordering the edits. Vim's rule that an
error stops the walk is kept.

`:doautocmd` fires an event by hand, which is the line a config needs after installing handlers for
a file that is already open. `:earlier` and `:later` are undo and redo: Vim's undo is a tree walked
by state, by file write or by elapsed time, and VS Code's is a line, so `:earlier {N}` is N undos -
right whenever nothing has branched - while `:earlier 5m` reports `E475` rather than answering a
question about time with a number of undos.

`:echomsg`, `:echoerr`, `:echon` and `:eval` are the rest of the `:echo` family. Only `:echo` has a
rule of its own in the grammar, so these arrive as a string where it arrives as parsed expressions -
and rather than parse that string a second way, they hand it back to the parser as an `:echo` and
take the expressions off it, so everything `:echo` understands they understand too. `:echoerr`
throws, which is what makes `silent! echoerr` a way of testing something and what lets `:try` catch
it. `:echohl` and `:undojoin` are registered and do nothing, with the reason written at each.

`:left`, `:right`, `:center` and `:retab` rewrite a line's whitespace. The first three build their
indent through `createIndentBySize`, so it is tabs or spaces according to `'expandtab'` - the same
call every other indenting command makes, rather than a string of spaces that would look right
until someone opened the file in Vim. `:retab` is the interesting one: a tab is a jump to the next
tabstop rather than a fixed width, so a run of whitespace has to be measured from where it starts on
the line, and it is read at the tabstop the file is laid out for now and written at the one it is
being laid out for. Vim's rule that a run without a tab in it is left alone is kept, so `:retab`
does not silently reflow a file someone aligned with spaces; `:retab!` is how you say you meant it.
`:number`, `:list` and `:z` are `:print` with the two things it can be asked to show and a window of
lines.

`:args`, `:argadd`, `:argdelete`, `:badd`, `:bmodified`, `:tabs`, `:tabfirst`, `:tablast`,
`:bunload`, `:bwipeout`, `:drop`, `:view`, `:visual` and the `s`-prefixed family (`:sbuffer`,
`:snext`, `:sprevious`, `:sfind`, `:sview`) are the other names for the one list. Vim keeps three
apart - the files named on the command line, the buffers it has loaded, the tab pages - and neither
host does: a file appears once, whichever of the three you ask about. So `:args` and `:ls` print the
same files and `:tabfirst` is `:bfirst`. Registering both spellings is the point; one of each pair
working and the other reporting `E492` reads as an oversight rather than as a difference between Vim
and an editor with tabs. The `s`-prefixed ones are written as what they mean - split, then run the
command - so `:sbuffer 3` reports `E86` from the same place `:buffer 3` does.

`:fold`, `:foldopen` and `:foldclose` are `zf`, `zo` and `zc` addressed by line rather than by
caret, and they go the same road the keys go - `editor.fold` and `editor.unfold` through the action
executor - so a host that gains folding gains it for both at once. What is lost against Vim is the
span: both hosts fold by "the region the caret is in" rather than by a pair of offsets, so a range
opens the fold it starts in. `:redraw` and `:redrawstatus` reach the redraw service the status line
already uses; `:sleep` and `:checktime` are registered and return at once, the first because nothing
here can stop the thread that draws the editor and the second because both hosts watch the
filesystem without being asked. `:startreplace` is `:startinsert` in the other mode.

Some commands Vim only has when it was built with the feature, and this fork answers those the way
a `vim` without `+python3` answers `:python` - `E319: Sorry, the command is not available in this
version` rather than `E492: Not an editor command`. The two mean different things to whoever reads
them: `E492` says "no such command, check your spelling" and `E319` says "that command, and this
build does not have it". Thirty are on that list - the language interfaces, the GUI-only commands,
`:stop` and `:suspend`, the swap file, the viminfo and undo files, `:mksession`, `:undolist` - and
every one of them is a command whose *subject* is missing rather than one that could be written and
has not been. That distinction is the whole of the rule, and it is asserted: a test checks that
`:mkvimrc` and `:tselect` still report `E492`, so the line cannot quietly move.

The quickfix list is here too, and the location list beside it. `:make` and `:grep` run `'makeprg'`
and `'grepprg'` through the same process group `:!` uses, `:cexpr`, `:cfile` and `:cbuffer` fill the
list from an expression, a file or the buffer, `:cnext`, `:cprevious`, `:cfirst`, `:clast` and `:cc`
walk it, and every one of those has an `l` twin over the window's own list - which is the entire
difference between the two families. Two places it is not Vim, both written down where they happen:
`'errorformat'` is declared and never read, because Vim's is a pattern language of its own and this
reads the four shapes that cover a compiler, a linter and `grep -n` instead; and `:copen` prints the
list into the output panel rather than opening a buffer holding it, because neither host lets an
extension make a buffer out of nothing. `:cnext` and `:cc` are how you move through it.

`:smagic` and `:snomagic` are `:s` with `'magic'` decided rather than inherited - two characters
inserted in front of the pattern, which is what `\m` and `\M` mean and where Vim puts them, so
everything else about them is `:s` because it is `:s`.

The list in `VsCodeOptionsTest` of what a config can say and this host reports `E492` for is now
empty. It is still typed at the prompt, and still asserted, so a command that stops resolving shows
up as a failure rather than as a bug report.

Sweeping the whole of Vim's command list rather than what a config reaches for left twenty-six, and
those are written now. **Tags** read a real `tags` file - a symbol index that answers over a promise
cannot report `E426`, and a list of matches is what `:tselect` and `:tnext` are questions about, so
`'tags'` means what its documentation says and a project without one gets Vim's `E433`. **Diff**
opens the host's own diff view over two files, which is what `:diffthis` and `:diffsplit` reach in
the end; Vim gets there by turning a window mode on and neither host has one, so the first
`:diffthis` remembers the file and the second opens the pair. **`:vimgrep`** searches the files
itself with this engine's regex, which is the whole difference from `:grep` and its shell. **Spell**
is the three operations a borrowed checker answers - VS Code has none at all and says so.
**`:match`** is a standing highlight, recomputed after every keystroke and after every ex command,
painted in the colours `:highlight` gave the group and in the editor's own for a group nobody gave
any.
**`:mkvimrc`** writes the session back out from the same two places `:map` and `:set` read it from,
and the test runs the file it wrote back through the parser. **`:changes`** is the change list,
which moved out of an IntelliJ application service into the engine and brought `g;` and `g,` to
this host with it.

What is on the far side of the line, and why, is worth reading as a group: `:diffget` and
`:diffput` need a diff view an extension can reach hunk by hunk; `:ptag` and its relatives need a
preview window; `:spelldump` and `:mkspell` need a word list a borrowed checker will not hand over. Each of those
reports `E319` - "this build does not have it" - and each is an absent *subject* rather than
unfinished work. `:debug` is the one command of the sweep still reporting `E492`: it needs an
interactive stepping prompt, and a `:debug` that ran the command without stopping would not be
`:debug`.

`:language` is deliberately not in that group, though it was for a day. It names something the
editor decides for itself, which is the same shape as `:syntax`, `:filetype` and `:colorscheme` -
and those three already accept quietly and speak up only when the argument asks for something the
editor will not do. A `~/.vimrc` is full of such lines and one red line per line of config is a
worse answer than silence, because the reader cannot act on any of it.

`:cd`, `:chdir`, `:lcd` and `:tcd` are real now, over a current directory the *engine* owns -
because a project or a workspace is not a working directory, which is why `:pwd` used to report the
project root and there was nothing for `:cd` to change. A relative path is still handed to the host
untouched until somebody runs one of them, so no existing config moves; after that the engine
resolves, and `:e`, `:find`, `:vimgrep`, `:mkvimrc`, `:diffsplit` and the tag files all follow.

`:highlight` defines a group, which is the thing `:match` was written without. Vim's whole grammar
of it: `guifg`/`guibg`/`guisp` and their `cterm` twins, the attribute lists, `:hi link`, `:hi
default link`, `:hi clear`, and the listing that reads back what was set. The colours are resolved
in the engine - a name becomes `#rrggbb` before a host sees it, and `ctermfg=Blue` becomes the
bright blue Vim means by it and not the dark one, which is Vim's own quirk and easy to get wrong.
A group nobody defined still reaches the host as a *name*, and the host still maps it to its theme;
that fallback is not a placeholder, because a literal that reads in Dark+ is invisible in Light+.
`:hi Search NONE` is kept apart from a `Search` nobody mentioned, since Vim is explicit that
disabling a group is not the same as putting it back.

What it does not do is Vim's other half. `:highlight` is also how a syntax file colours a language,
and neither host highlights syntax - IntelliJ has a lexer and VS Code has a grammar, and both decide
what a comment looks like without asking. Defining `Comment` here colours no comment. That is not a
gap to close; it is two editors doing their own job.

`:redir` catches what a command printed - into a register, a variable or a file, appending with
`@A`, `=>>` or `>>`, and refusing to clobber a file without a bang. A register and a variable are
one code path, because both are `LValueExpression` and both already knew how to be assigned to. The
one thing that had to be got right is the order: the capture sits *before* the `:silent` check in
`VimOutputPanelServiceBase.output`, because `:redir => x | silent map | redir END` is the entire
reason anybody types this command, and a tap on the other side of that check returns an empty
string every time. Unlike Vim it writes through on every message rather than buffering until
`:redir END`, so a script that threw halfway - which is when somebody is redirecting output to find
out why - keeps what it caught. It also does not reproduce Vim's leading empty line, which is an
artifact of writing a screen line-break; a config that strips one still works.

`:messages` needed something the engine did not have: a single place a message passes through.
`VimMessages` said outright that there wasn't one, which is why every host consulted `:silent` on
its own - a flag each host reads can answer "should I draw this?" and nothing can answer "what was
said?". `VimMessagesBase` is that place now: its four `show*` methods are final and record before
delegating to a `display*` each host implements, with the same bodies and the same silence checks.
What is kept is what Vim keeps - `:echomsg` and not `:echo`, errors, and the one-line reports that
go to the status line, since "E486: Pattern not found" and "3 substitutions on 2 lines" are the
same kind of thing. A message `:silent` hid is remembered; an error `:silent!` swallowed is not,
because it is never reported at all, in Vim's `emsg_core` and in this executor alike.

`:sign` keeps Vim's split between a *definition* - what a mark looks like - and a *placement* -
where one is, with groups, priorities and `:sign jump`. Placements belong to a path rather than to
a buffer number: this fork has no buffers, `:ls` numbers what the host has open, and those numbers
shift when a tab closes, so a sign that remembered one would end up on a different file. `buffer=`
is still accepted and resolved through that list.

Each host draws what its gutter allows, and they allow different things. IntelliJ gets a
`RangeHighlighter` over the line carrying the `linehl` colour, plus a `GutterIconRenderer` that
*paints* the sign's one or two characters, since there is no image to load for `>>`. VS Code has no
way at all to put text in that column - `gutterIconPath` takes an image and nothing else - so the
characters are drawn into an SVG and handed over as a `data:` URI. Neither host draws `numhl`,
which colours the line *number*: IntelliJ's numbers come from the gutter component and VS Code's
from a theme colour, and in both the number is out of an extension's reach. It is carried rather
than dropped, so `:sign list` still reports it.

Signs repaint on a different rule from `:match`, and the difference is the point. A standing
highlight is recomputed after every keystroke because its ranges come from the text. A sign sits on
a line, and both hosts' markers already follow that line as text is inserted above it - so the
engine hands a host a new list only when the list has changed, and handing over an unchanged one
would move every sign back to where it was placed.

`:append`, `:insert` and `:change` are the only ex commands whose argument is *the lines that
follow*, and the grammar has no way to say that - every rule in it ends a command at a newline. A
lexer rule could swallow the block the way the `lua <<EOF` rules do, but those begin with a word
nothing else begins with; these begin with `a`, `i` and `c`, and a rule anchored on one letter
would swallow any `echo a` that happened to have a lone `.` further down the file. So the block is
folded into the argument by a textual pass before parsing, next to the two the parser already ran.
Inner newlines become `U+0001`, which the grammar's catch-all rule lexes without complaint and no
configuration file contains; the fold is anchored on a line that is *only* a range and a command
word, so nothing with a space in it can be mistaken for one.

That anchoring is not theoretical. `:imap a b |c " Something else` leaves a bare `c` after the
bar, and `:c` is `:change` - one of IdeaVim's own `:map` tests found a `:change` that had taken
` " Something else` for its argument and deleted a line of the buffer. These three take no
argument at all, so anything after the command name is now `E488`, which is Vim's answer too.

Giving them somewhere to write also filled two holes in the headless test host: it had no `put`
and its editor had no `document`, and between them that made `:put`, `:copy`, `:move` and `:read`
report "Not implemented yet :(" in every engine test that tried them - silently, because the
executor turns that into a message rather than a failure.

Nine more commands are each one more turn of machinery that was already here. `:stag` is `:split`
and then `:tag`, which is the shape `:sbuffer` and `:snext` already had. `:ltag` is `:tag` writing
its matches into the window's location list instead of only jumping - the difference, for a name
defined in six files, between six presses of `:tnext` and one look at a list; it costs a file read
per match, because a tags file stores a search pattern rather than a line and every entry in a
location list has to be a place. `:doautoall` is `:doautocmd` for every open editor rather than
one. `:argedit`, `:argglobal` and `:arglocal` join the argument-list family, where the list is the
open files; the two scope commands are true by construction with no argument, and with names after
them they open those files rather than *replacing* the list, since replacing this one would close
the reader's editors. `:tabfind` is `:find`, because both hosts open a file in its own tab already.

`:folddoopen` and `:folddoclosed` are `:global` with a fold instead of a pattern, and they exist
because a fold hides lines from the reader and not from a command - `:%s/x/y/g` changes text inside
a collapsed fold as happily as anywhere else. They run over range markers rather than line numbers,
which is what `:global` does and for the same reason: the command being run can insert and delete
lines. A host that folds nothing is not a special case - every line is then not in a closed fold, so
one of the pair runs everywhere and the other runs nowhere, which is what Vim does in a buffer with
no folds.

Writing them filled two more holes in the headless test host. Its editor had no folds at all, so
only half of that pair could ever have been tested; and `createRangeMarker` was a `TODO` reading
"nothing here needs that yet", which was already untrue when it was written - `:global` takes one
marker per matching line before it runs anything, so every `:g/pattern/command` in the engine's own
suite was reaching it.

`:uniq` is Unix `uniq` over a range: *adjacent* duplicates, which is the whole difference from
`:sort u`. It removes a repeat only where it sits directly under the line it repeats, so a log or
an already-sorted list is deduplicated without being reordered. All of Vim's flags, including the
one worth reading twice - the bang keeps "lines that are immediately followed by a duplicate",
which in a run of three means the first two survive.

`:saveas` writes the buffer somewhere else and leaves you editing *there*, which is the difference
from `:write {file}` and the reason both exist. Vim does it by renaming the buffer; neither host
lets an extension rename what an editor is showing, so this writes the file and opens it - the
reader ends up where Vim would leave them, with one more tab than Vim would leave open.

`:oldfiles` lists the jump list's files, newest first. Vim reads its list from viminfo, and there
is none here: no host offers an extension the files a user edited last week, and inventing a file
to keep them in would be a larger decision than this command is worth. Vim's own index calls this
"files that have marks in the viminfo file" and a jump is a mark, so it is the same kind of list
starting empty at each launch instead of being read from disk - which the command says out loud,
because someone expecting last week's files should be able to tell.

Nine of the *editor decides for itself* family moved out of this host and into the engine:
`:syntax`, `:filetype`, `:colorscheme`, `:runtime`, `:packloadall`, `:helptags`, `:mkview`,
`:loadview` and `:version`. That was not tidying. They lived here, so the **IntelliJ plugin
reported `E492` for every one of them**, `:syntax on` included - which is the first line of a great
many `~/.vimrc` files. Their messages now say "the editor" rather than naming VS Code, because
there are two and the sentence is true of both. `:version` is not one of the quiet ones: it asks a
question that has an answer worth printing. `:setfiletype` stays here, because in this host it does
real work through the language mode.

### Vimscript functions

`has()` was the IntelliJ plugin's alone, which meant that in this host `has('unix')` was
`E117: Unknown function` - and a `~/.vimrc` whose first ten lines are `if has(...)` blocks did not
take the wrong branch, it stopped. It is the engine's now, and what it claims is only what is true:
`has('signs')` became 1 the week `:sign` was written, `has('quickfix')` because `:copen` is real,
`has('folding')` because folds are the editor's and the commands reach them. Nothing is claimed
because a config would prefer it - no `python`, no `terminal`, no `timers` while `timer_start()`
does not exist, no `patch-9.1.0`, because this is not Vim and a patch number is a claim about Vim's
source.

`gui_running` is the interesting no. Both hosts are unmistakably graphical, so 1 is the
honest-looking answer and the wrong one: a config guards `set guifont=` and `set guioptions-=T`
behind it, neither option exists here, and claiming the feature turns one skipped block into two
`E518`s. The operating system, and `spell` - which IntelliJ has and VS Code does not - come from a
new `hostFeatures` on the injector, because the engine has no way to ask what platform it is on and
no business guessing.

Twenty-seven string functions came with it, taking the engine's list from 93 to 121. `printf()` is
the one that could not be delegated: `String.format` is a JVM method, so `commonMain` cannot use it
and the specification had to be implemented - flags, width, precision, `*`, positional arguments
and eleven conversions, including `%e` and `%g` written out by hand. `substitute()` shares its
replacement grammar with `:s` rather than reimplementing it, because `\0`, `\1`, `&`, `~`, `\U`
and `\L` are a small language and a second copy of it would be a second copy to drift.
`execute()` is the function form of `:redir` and is built on it, which is why the awkward part -
catching output that `:silent` is hiding - was already solved.

The measurement functions are where UTF-16 shows through. Vim counts bytes, characters and screen
columns, and *none* of the three is a Kotlin string's `length`: `strlen('héllo')` is 6, `strchars`
is 5, and a CJK ideograph is one character, three bytes and two columns.

Twenty-eight more take the list to 149: the path family, the register pair, and the `match*()`
functions that add a highlight.

`expand()` leads it, because `expand('%:p:h')` is how every configuration in the world asks for the
directory of the file it is looking at and it was `E117` in both hosts. Vim's filename modifiers
are their own small language - applied left to right and repeatable, so `:h:h` is the grandparent -
and they live in one place, shared with `fnamemodify()`, which is the same feature pointed at a
string. `:.` and `:~` are relative to the directory `:cd` owns, which is what makes them agree with
`:pwd`.

`glob()` and `globpath()` share their globber with `:vimgrep`, which is the point rather than an
economy: a config that writes `glob()` over a tree and one that writes `:vimgrep` over the same
tree should be looking at the same files, and two implementations would eventually disagree about a
double star. The shared one gained character classes on the way. `glob2regpat()` touches no files
at all - it exists because Vim has no `fnmatch()`, so a config turns the glob into a pattern and
uses `=~`.

`getreg()` and `setreg()` are what let a mapping *borrow* a register: yank into it, use it, put it
back. The type travels with the text, because a line-wise register restored characterwise is a
mangled buffer rather than a wrong colour.

`matchadd()` and its family are `:match` reached from Vimscript, and they share its table. Vim
reserves ids 1, 2 and 3 for `:match`, `:2match` and `:3match` and starts `matchadd()` at 4; so does
this fork, which is why `getmatches()` lists a `:match` alongside everything a plugin added and
`clearmatches()` takes them all off together. Two tables would have been easier to write and would
have made both of those functions lie. `matchaddpos()` is the fast one and the speed is real here
too: a match made from positions is already ranges, so the repaint after every keystroke has
nothing to search for.

Twenty-four more take it to 173: the position family, the two line-writing functions, and the
buffer questions.

`getpos()` and `setpos()` speak in lists, which is what makes them the pair a plugin uses - save
the cursor, move around, put it back, without ever writing down what a position is made of. So the
list has to be Vim's exact four-element shape, and `setpos('.', getpos('.'))` has to be a no-op in
code that never looks inside it. `getcurpos()` adds a fifth element, and it is the whole reason
that function is not `getpos('.')`: it carries the column the caret is *trying* to be in, so a
restore does not leave the caret looking right and behaving wrong on the next keystroke.

`virtcol()` and `indent()` count screen columns rather than characters, which only tabs make
visible - two tabs and a letter is three characters and seventeen columns. That is what makes
`indent()` usable at all: a file indented with tabs and one indented with spaces compare equal.

`setline()` and `append()` write to the buffer without a register, a motion or a mode. They are
kept apart the way Vim keeps them: `setline()` stops at the end of the buffer and `append()` grows
it, so a wrong line number cannot silently add text.

The buffer functions are over the one list this fork has, and the divergence is worth stating: Vim
numbers a buffer once for the life of a session, while both hosts here have lists that *shrink*
when something closes. `bufnr('%')` saved before a tab closes may name a different file afterwards.
The two that appear in real configuration - `bufname('%')` and `bufnr('%')` - are about this
buffer and cannot go stale, and the numbers agree with the ones `:ls` prints. The window functions
answer about the window you are in, because neither host lets an extension enumerate its splits;
`winnr()` in a status line gets a number that means something, and a config that loops over windows
visits one.

Writing them turned up one more headless-host gap: `TestVimCaret.vimLine` was a `TODO`, and
`line()` is what half the position family is built on - so `getpos()`, `cursor()` and `setline()`
were all reaching it at once.

`:action {id}` runs any VS Code command, `:actionlist [pattern]` lists them, and `<Action>(id)`
maps a key to one - IdeaVim's three, over commands instead of IntelliJ actions. The names are
different, so an `.ideavimrc` written for IdeaVim will not carry over: `:action GotoClass` becomes
`:action workbench.action.quickOpen`, and `:actionlist quickopen` is how you find that out. A name
this VS Code does not have is reported at the `:` prompt rather than failing silently, from the
command list fetched once at activation.

`:actionlist` prints the chord bound to each command beside it, as IdeaVim prints IntelliJ's
shortcuts - and it has to work for it, because **VS Code has no API that says what is bound to
what**. There is no `getKeybindings`, for anything. So the three files VS Code builds its own keymap
out of are read instead, in the order it applies them: its defaults, then each extension's
`contributes.keybindings`, then the user's `keybindings.json` - which can rebind and, with a leading
`-`, unbind. Only the middle one is reachable through the documented API. The user's file is found
two directories above `globalStorageUri`, which is where VS Code always puts it and is the only way
that holds for Insiders, VSCodium, a portable install and a remote window alike. The defaults are
read from the document behind `workbench.action.openDefaultKeybindingsFile`, which is the one
undocumented step in this extension and is written to be allowed to fail: a VS Code that stops
serving it leaves the other two sources printing and says so in the output channel.

The `when` clause is deliberately not printed. Most bindings have one, an extension cannot evaluate
them - context keys are write-only to it - and a chord shown without the condition that gates it is
a promise the list cannot keep. The column says what is bound; whether it applies where the caret
happens to be is what the Keyboard Shortcuts editor is for. The pattern matches the whole line, so
`:actionlist shift+cmd+f` answers the other question a reader has.

An `.ideavimrc` written for IdeaVim names IntelliJ's actions - `:action GotoClass`,
`<Action>(Back)` - and those are translated. 90 of them, with 8 answering that the job is
IntelliJ's and nothing in VS Code does it: `MakeGradleModule` and `Maven.ReimportProject` are the
IDE's build model, `Annotate` is its VCS integration, `GotoSuperMethod` goes up an override chain
VS Code has no way to walk. Saying which of the two it is matters, because "Action not found" sends
the reader looking for a typo they did not make.

A name this window really has always wins over the table, which is what stops the two vocabularies
from colliding. The table is a page of ids typed from documentation - this module's least checkable
kind of fact - so activation compares them against the real window and names the ones that are
wrong, the same way it checks the commands the extension uses itself.

`:action git<Tab>` completes, and cycles on each Tab with `<S-Tab>` going back. The engine had all
of this - a completion session, a parser that says whether the caret is in a command name or its
argument, and a type per command saying what the argument completes against - and it knew about file
paths and nothing else. Both hosts already answer `getActionIdList`, which is a prefix query, so an
`ACTION` type in `vim-engine` was the whole of it; IdeaVim gets `:action` completion from the same
change. Command names complete too, this host's own included: `:actionl<Tab>` finishes `:actionlist`.

`:set syn<Tab>` finishes `:set syntax`, by the same route: an `OPTION` type, answered from the
option registry both hosts fill. Vim completes the full name rather than the abbreviation - the
abbreviation is a way of writing the option, not a second option - and offers `no` and `inv` in
front of a boolean one, because those are how you write it. `:set` is also the first command whose
argument is a *list*, so Tab replaces the word the caret is in rather than the whole argument:
`:set number rel<Tab>` leaves `number` alone. Everything else still replaces the argument entire,
because `:edit my file.txt` names one file and completing only `file.txt` would produce a path
nobody meant. A word that has already said what it wants - `:set nu?`, `:set nu!`, `:set sw=4` - is
not a name being typed, and none of the three completes.

The matches are drawn on a second status bar item, which is Vim's wildmenu with the same two limits
as the caret: text, so the current match is bracketed rather than highlighted, and one row, so the
list is windowed around the selection with the count in front of it. `:action e` against a real VS
Code matches a couple of hundred commands.

File completion for `:e` is not wired up - `listFilesForCompletion` is a `VimFile` method this host
does not override, so `:e <Tab>` finds nothing.

The prompt draws a caret, so a typed command can be edited rather than only retyped. `<Left>`,
`<Right>`, `<Home>`/`<C-B>`, `<End>`/`<C-E>` and `<S-Left>`/`<S-Right>` all worked already - they
are assignments to an offset the engine owns - and what was missing was any way to see where that
offset was. A status bar item is text and takes no styling, so the caret is a thin bar spliced into
the string rather than a block drawn over the character under it, and the display is handed the
offset apart from the text so that something painting this into the editor one day can do better.
It also makes a trailing space visible: `:e ` and `:e` are different commands and looked identical.

`Cmd+V` pastes into the `:` and `/` prompts, and `Ctrl+Shift+V` does off the Mac - not plain
`Ctrl+V`, which is Vim's own and inserts the next character literally. The work is Vim's `<C-R>+`,
which the engine already had; what the host adds is that a system chord is a keybinding rather than
a key an extension can be handed, and one claimed only while `vimperor.mode` is `COMMAND` so that
every other mode keeps the editor's own paste. The clipboard is re-read before anything is typed,
which is the one register read here that can afford a promise - a paste is a gesture of its own,
where every other read happens mid-keystroke.

`:registers` and `:marks` print to the *IdeaVim* output channel. Vim's output panel takes over the
screen and then takes keys - space pages, `q` closes - and VS Code has no equivalent that does not
fight the editor for focus, so this prints and gets out of the way. Typing carries on working while
the output is showing, which is a deliberate difference from Vim.

What is not built yet is written down rather than left to be discovered. `VsCodeUnimplementedTest`
presses every key the engine registers and asserts the list of the ones that land on a host service
this port has not written; implementing a service shrinks the list, and a key that starts or stops
reaching one shows up as a diff. That list is the honest map of the gap, and what is on it now is
not a matter of writing more host: `[m` and `]s` need a language server and a spellchecker, and `q:`
is Vim's command-line window. The ordinary Vim keys came off the list with `S`, and `U` came off it
afterwards - the list had said `U` "needs the same host history undo does", which was a wrong
reading of the command. `u` walks a history; `U` walks nothing. Vim keeps one pristine copy of one
line, taken the first time that line is touched, and `U` swaps it with what is there now, so a
second `U` puts the change back. What it needed was a copy taken *before* an edit, and every edit
here already funnels through one class. A description on that list is a claim like any other.

The reasons on that list were then read again as claims rather than as conclusions, and four of the
nine were wrong. Two services came off it. `pluginService` was recorded as blocked behind
`modalInput.activate` - the blocking key read a `getchar()` needs, which JavaScript cannot do on one
thread. That limit is real and it is about a different part of the extension system: nothing in
`pluginService` reads a key. Its three methods are running normal-mode keys, declaring a Vimscript
function and adding a command alias, all of them the engine's, and IdeaVim's implementation was
three one-line delegations to a facade in its IntelliJ module whose bodies had no IntelliJ in them.
Both hosts share `VimPluginServiceBase` now.

`fallbackWindow` was described as the editor for "no window at all", which reads like a corner
nobody reaches. Every scope in the thin API resolves its editor as
`projectId?.let { getSelectedEditor(it) } ?: injector.fallbackWindow`, and a plugin's `init` runs
with a null project id by construction - there is no editor yet while a plugin is declaring its
mappings. So it is the *first* thing the extension chain asks for rather than the last, and without
it that chain stops at line one. The option group wants it for a second reason: the global values of
window-local options have to be stored against some window when none is open.

The other two corrections did not free anything and matter more. `extensionLoader` and
`jsonExtensionProvider` are blocked by *class loading*, not by modal input: `LazyVimExtension` is in
`vim-engine/jvm/src/main/kotlin` and resolves a class by name through a `ClassLoader`, which JavaScript does
not have - the same problem this build already solves for commands, functions and ex commands by
generating a registry at build time, so the answer has a known shape. And they were described as how
IdeaVim's twenty-six bundled extensions are found and started. They start exactly one:
`ideavim_extensions.json` has a single entry. The other twenty-five use the older `VimExtension`
extension point, which is not a `VimInjector` service at all, so it has never appeared on that list.
The map of the gap was itself understating the gap, from the one direction it cannot see: a thing
that was never a service.

The thing worth knowing before building that registry is that the mechanism is not finished upstream
either. The one thin-API extension has a test class marked `@Disabled("The test is flaky because of
an unknown reason")`, and the two halves of its registration do not meet - the annotation processor
writes `ksp-generated/ideavim_extensions.json` and the scanner looks for `META-INF/extensions.json`,
which is the path a third-party plugin uses. The generated-registry shape is the right answer here,
as it was for commands and functions; the time to apply it is when there is one extension that
demonstrably runs.

That has now happened often enough to be the pattern rather than the exception. `'incsearch'`, the
incsearch caret, `:s///c`'s prompt, `:e newfile`, `U`, and range markers were each ruled out in a
comment in this repository, and every one of those comments was wrong - usually about which VS Code
API was being talked about. The range marker one is the sharpest: it said VS Code has nothing
equivalent and that this would have to be built on `onDidChangeTextDocument`, while forty lines away
`DocumentBuffer` already tracked markers over the engine's own mutations and said in its own comment
that doing it there is *more* exact than change events. Two names for one thing - `LiveRange` and
`VimRangeMarker` - were enough to hide that one of them was done. `:g/pattern/command` was what that
cost, and no sweep could find it: a bare `:g` is a Vim error before it reaches the marker.

That is the same blind spot three times, and it took three findings to name it. Every one of the
four sweeps exercises its surface *with no arguments* - a bare `:g`, an `echo split()` with nothing
to split, `gx` on a line with no URL - so a service reached only once there is a real argument is
invisible to all of them. `:g` needed a range marker, `split()` and `=~` needed a regex service, and
that service was `VimRegexServiceBase`: the engine's own class over the engine's own regex engine,
which the IntelliJ host does no more than name.

So there is a fifth sweep now, and it works the other way round. `UnimplementedServicesTest` reads
`VsCodeInjectorBase` for the services declared as `TODO` and `VsCodeInjector` for the ones actually
overridden, and asserts the difference in both directions. Nothing has to be *reachable* to appear
on it - which is the point, since reachability was what the other four were measuring by accident.
It found `autoCmd` on its first run, and that was the fourth
feature in a row missing for the same reason: `:autocmd` and `:augroup` are engine commands, their
registry and their Vim-glob pattern matching had nothing IntelliJ-shaped in them, and they lived in
IdeaVim's IntelliJ module anyway. They are in `vim-engine` now. What a host owes them is the
*events*, and this one fires `BufEnter`/`BufLeave` when VS Code changes the active editor and
`FocusGained`/`FocusLost` when the window gains or loses focus.

It also caught a wrong reason on its own list, which is the point of writing reasons down at all:
`highlightingService` was described there as `matchadd()`, and `matchadd` does not exist anywhere in
this repository. Its only caller is `Transaction.addHighlight` in the extension thin API, so it
belongs with the plugin services and is reachable from nowhere else.

Nine services are left, and each carries a line saying what it needs.

Then a sixth, and it found the largest gap in the port. Every test in this module presses keys by
calling the extension's own entry point - `VimHost.key("<C-V>")` - which is not VS Code's. In a real
window a key only reaches that entry point if `package.json` declares a keybinding for it: printable
characters arrive through the `type` command and need nothing, and *everything else* arrives only if
asked for. So a key can be implemented, swept, fixture-tested, and completely dead in a real editor,
and nothing here would say so.

Sixty-one keys were in that state. `<C-V>` for block Visual, `<C-W>` for windows, `<C-D>` and
`<C-U>` for scrolling, `<C-O>` and `<C-I>` for the jump list, `<C-A>` and `<C-X>` for increment,
every shifted arrow that Select mode is built on, and the whole of Home, End, PageUp and PageDown.
Three of them had tests written in the same week the manifest did not mention them.

`KeybindingManifestTest` compares what the engine registers against what the manifest asks for, in
both directions, and sixteen are now left to VS Code on purpose with a reason each. `<C-C>`, `<C-F>`,
`<C-S>` and `<C-Q>` are copy, find, save and quit: Vim has meanings for all four, and an emulator
that took them is the reason somebody uninstalls it. The rest are terminal spellings a keyboard
cannot produce and keypad keys VS Code does not distinguish.

The control chords that clash with editing habits are bound only outside Insert mode, which needs
the mode in a `when` clause - `ctrl+v` is block Visual to Vim and paste to everyone else. The
extension sets `ideavim.mode` alongside the status bar for exactly that, and only when the mode
changes: `setContext` re-evaluates every `when` clause in the window, so sending it per keystroke
would be fifty round trips for a typed word.

The rest of the manifest is checked too, because it is read before any of this code runs.
`engines.vscode` and the `@types/vscode` that `checkVsCodeApiDeclarations` reads are pinned to the
same version - otherwise the API check passes while checking a version users are not promised. And
`capabilities` now says where this works: not in a virtual workspace, because every file it reads
goes through Node's `fs` so that `:source` can return with the contents, and yes in an untrusted
one, because the configuration comes from your home directory and never from the workspace.

One thing here is a performance test, which is unusual and is the right shape for what it caught.
Markers - the offsets that follow edits, so that insert mode can say where it began - are moved one
by one on every change to the buffer. A marker nobody will read again is therefore not merely
memory: it is work added to every keystroke for the rest of the session, so the cost of typing grows
with how long the editor has been open, and no test that presses ten keys would ever see it. Three
things were leaking them. Replace mode kept one per overwritten character and dropped the stack
without dropping them. The engine's backspace *looked up* an entry by building a marker and throwing
it away - free in IntelliJ, tracked for ever here - and it now searches instead, which also retires
a trap: the lookup used to depend on two markers over the same span comparing equal, and a phase
went by where they did not. And every caret made one eagerly, while carets are rebuilt from VS
Code's selections each time the mouse moves.

A fourth thing was accumulating beside them, and it is the same shape. `VimHost.forget` existed to
drop an editor and nothing called it - so the host kept an editor, its whole text and its markers
for every file opened in the session. Memory is the least of it: `'hlsearch'` paints every editor
the engine knows about, and `getFocusedEditor` falls back to the last one registered, which could be
a file closed an hour ago. `workspace.onDidCloseTextDocument` is now wired to it.

The same subscription pass added `BufWritePost`, which is the other autocommand event VS Code
reports plainly. `BufWritePre` is deliberately not fired: `onWillSaveTextDocument` wants the edits
handed back as a promise of `TextEdit`s, and this host applies its own asynchronously, so
`autocmd BufWritePre * :%s/\s\+$//e` - the reason anyone wants the event - could land after the
file was written. Firing nothing beats firing it too late.

A seventh arrived from widening the corpus rather than from reasoning about it. The harness refused
any fixture with more than one caret, on the honest grounds that replaying a multi-caret test with
one caret would give a wrong answer rather than a missing one. That was true of a harness that
compared one caret; the answer was to compare all of them, not to look away - and multiple cursors
are the feature VS Code is best known for. Twenty-four fixtures came in with the refusal removed and
twelve of them fail, which is the yield of a refusal that had been standing since the harness was
written.

They are one bug with a very legible signature: `caret expected [18, 47, 86, 127], actual [47, 86,
127, 127]`. A vertical motion in blockwise Visual asks the host to lay the block out again, and the
engine's own comment on that call says "WARNING! This can invalidate the primary caret" - IntelliJ's
selection model really does throw its caret objects away there, so the motion's final move lands on
one that no longer exists. This host reuses the caret instead, because `vimSelectionStart` is stored
on the instance and the anchor has to survive, and it becomes the block's *first* line since that is
where it is standing. The motion then drags it to the destination on the *last* line. What a host
owes a caret the engine has just re-purposed is its own piece of work, so the twelve are recorded in
`known-fixture-failures.txt` with the trace that found them rather than fixed in passing.

All five of the earlier blind spots are about *reach*: which surface a test drives and how far into
it. The sixth is narrower and worse, and it is about the text. Every test in this module, and every one
of the 1,025 fixtures harvested from IdeaVim, writes `\n`. Files that end their lines with `\r\n`
were therefore covered by nothing at all - and that is not an exotic file, it is most of a Windows
checkout.

It is host-specific by construction, which is exactly why IdeaVim's own tests could never have
caught it. IntelliJ normalises a `Document` to `\n` and applies the file's separator on the way to
disk, so `vim-engine` has never seen a carriage return and is not written to expect one. VS Code's
`getText()` hands back what the file actually has. Left alone, the `\r` sits *inside* the line as
the engine measures it, and it is the line's last character - so it is under everything that goes to
the end of a line. `$` lands on it, `x` deletes it, `A` appends after it, and `J` leaves one in the
middle of the joined line. `DocumentBuffer` normalises on the way in and puts the separator back on
the way out, which is IntelliJ's arrangement and the only one the engine can be used with.

The caret had the same problem from the other side, and the fix is the more interesting half. It
went through `document.offsetAt` and `document.positionAt`, which speak in *document* offsets - so
on a CRLF file every caret drifted one character further off for each line above it. It goes through
the buffer's own line index now, because a line and a column mean the same thing on both sides
whatever the file does: VS Code's character index is within the line's text, which excludes the
separator. That also retires a second, older hazard the old code had - the document lags the buffer
while an edit is in flight, so a conversion made through it could answer about text the engine had
already moved past.

Three other things read or write a file without going through a document, and each needed its own
end of this. `:source` and the `.ideavimrc` are read through Node, and the file most likely to have
CRLF endings is a Windows user's `_ideavimrc` - where a carriage return would land in the right-hand
side of every `:map` in it. `:read` puts a file's contents into the normalised buffer. And `:w
other.txt` writes the buffer straight to disk, so without putting the separator back it converts a
CRLF file on its way to a new name, which is Vim's `'fileformat'` and Vim keeps it.

The same question was then asked of `vim-engine` itself, and it has a better answer than expected.
The engine ships to VS Code compiled to JavaScript, so a test in its `jvmTest` source set is a test
of behaviour that reaches users on a platform the test never touches. 430 of its tests run on both
targets and 221 run only on the JVM - and nearly every one of those is JVM-only *by construction*,
because the JDK is the reference implementation it checks against. A differential test of
`Character.isLetter` cannot run where there is no `Character`. Exactly one file was there for no
reason at all, put there by the habit of typing `org.junit`, and it has moved. `JvmOnlyTestsTest`
now pins the list in both directions with a reason for each, and marks the two that could move if
somebody rewrote their JUnit 5 features.

The caret's shape is the mode indicator, and it is drawn the way Vim draws it: a block in Normal,
Visual, Select and on the command line, a vertical bar in Insert, an underline in Replace. That is
Vim's own default `guicursor`, `n-v-c:block,i-ci:ver25,r-cr:hor20`, and it is the one editor option
a Vim emulator has any business writing - a user reads it a hundred times a minute without looking
at the status bar. It is applied against whichever editor is active and tracked against that editor
as well as against the mode, because VS Code gives each editor its own options and one opened while
in Normal mode would otherwise keep the user's default bar.

## The tutor

`vimtutor` is how most people learned Vim, and it is here: Vim's own thirty-three lessons, opened
into an untitled document so that the ones saying "delete this word" work on the real thing.

Four doors, because the reader might come from either side:

    :vimtutor
    :tutor
    :vimperortutor
    Command Palette -> Vimperor: Open Vim Tutor

The lessons live in `vim-engine` and are shared with IdeaVim - a tutor teaching `dw` is teaching Vim
rather than teaching an editor, and only fourteen of its nine hundred lines were about the host.
Those fourteen are supplied per host through a `TutorHost`, and where this one's wording differs
from IdeaVim's it is because the fact differs: there is no `'showcmd'` here to watch a pending `d`
appear in, no status bar icon to create a vimrc from, and no plugin support to recommend one with.
Vim's own lessons on `:q`, `:w FILENAME`, `:r FILENAME`, `:!` and `:help` stay in the appendix but
are marked as working here, which was checked by running them rather than assumed.

The three ex commands are this host's rather than the engine's, registered through the
`commandProviders` hook `VimscriptParserBase` leaves open. They are not `:command` aliases: the
engine implements Vim's rule that a user-defined command starts with an uppercase letter, so a
lowercase `:tutor` could never have been one.

The tutor text is under the **Vim license**, not MIT - see `ThirdPartyLicenses.md` here, which is
inside the packaged extension because that license requires its text to travel with the
distribution.

## What only a real window found

The extension ran in a real VS Code for the first time and the first keystroke failed:

    IdeaVim: typing 'i' failed - Throwable: Illegal argument: selections

That is VS Code's own error text. `TextEditor.selections` is not duck-typed - its setter does
`value.some(a => !(a instanceof Selection))` and throws - and `Selection` was declared here as an
`external interface`, so every caret this host pushed was a plain JavaScript object and every
keystroke was refused. Normal mode looked fine because the status bar shows the mode of a freshly
built state machine whether or not a key has ever been handled.

Nothing offline could see it, and it is worth being precise about why. The stub host takes whatever
it is handed, so it agreed. `checkVsCodeApiDeclarations` reads `@types/vscode`, but it compared
*names* - and `Selection.anchor` and `Selection.active` are both real members of the real thing. The
mistake was in the *kind*: an interface where VS Code has a class. That check now compares kind as
well, with an exception list for the two classes this module only ever receives - receiving an
instance through an interface is fine, and it is constructing one that is not. Reverting the fix
makes the build fail, which is the only way to know a check works.

The same run found a second thing, from what was *missing* from the output rather than from an
error. The `.ideavimrc` line was absent: the config was loaded inside `window.activeTextEditor?.let
{ ... }`, and `onStartupFinished` fires before VS Code has focused a restored editor - so the file
was never read, silently, because the message saying so was inside the same block. It runs against
the fallback window now, which is what that editor is for.

A fourth had the same shape and took a second round in the window to find, because the first fix
uncovered it. `flushCarets` pushed a whole blockwise Visual selection correctly and then ran one more
line: `nativeEditor.selection = selections[0]`. That looks like setting the primary caret and is
not. VS Code documents `TextEditor.selection` as "shorthand for `TextEditor.selections[0]`", and its
setter is `this._selections = [value]` - so it *discards* every other caret. Every flush pushed an
N-line block and immediately threw all but one line of it away. The fake and the stub both kept
`selection` as a plain field, which is more forgiving than the real thing, which is the definition of
a fake that hides a bug; both implement it as the shorthand it is now, in both directions. That
change immediately caught the fixture harness doing `fake.selection = fake.selections[0]` after
setting up a multi-caret fixture, which under the real semantics collapses it to one caret.

That is the pattern in all of them, and it is worth naming as its own kind of blind spot: every one
was a place where a stub was *more permissive* than VS Code. An interface where the API has a class,
a field where the API has a shorthand, an event nothing fires, an editor that is always focused. The
offline half of this port is strong at what the engine does with a keystroke and was systematically
weak at what VS Code does with what the host hands it. The two changes that matter most from this
round are not the fixes: they are that `checkVsCodeApiDeclarations` compares kind as well as name,
and that the fakes now behave like the API rather than like the code under test.

And a third, which is a lesson about instrumentation rather than about VS Code. A keystroke that
threw was swallowed: VS Code catches the exception from a command handler, shows a generic
notification, and writes the detail to the extension host log - a different window from the one this
extension prints to. The key handlers report to the output channel now, and that one line is what
turned "`i` does nothing" into a named error in a single round trip.

## Checking it against somebody else's expectations

Every test in this module was written by whoever wrote the code under it, and they all share that
weakness: they encode what this port's author believed Vim does. The sweeps have the same blind spot
from the other side - they find a service that is *missing* and are blind to one that exists and is
wrong. `:` and `/` passed every sweep while the second backspace in a row threw.

So IdeaVim's own tests are read as data. Most of its 7,499 are one call - `doTest(keys, before,
after)` - with nothing IntelliJ-shaped in it: text in, keys, text out, argued over against real Vim
for twenty years by people who were not thinking about VS Code. `VimFixtures` walks the IntelliJ
module's test sources, evaluates the `doTest` calls it can read without a compiler, and
`VimFixtureReplayTest` presses all of them against this host.

**1,018 of the 1,025 it harvests pass.** The seven that do not are listed in
`src/test/fixtures/known-fixture-failures.txt`, grouped by what is actually wrong - which is six
things, not seven.

It started at 294 of 314, and every bug it has found was one no sweep could have: the service
existed, was reached, threw nothing, and was wrong. Seven fixtures were the empty line a trailing
newline opens - an editor buffer is not a file, and IntelliJ and VS Code both show that line and let
a caret sit on it, but this host counted lines by counting newlines, so `cc` and `dd` on the last
line took the range of the line above and `G` stopped short of the end. A unit test in this module
asserted the wrong behaviour in as many words, which is the case for harvesting somebody else's
tests in one line. Three were replace mode: `R` did not overwrite at all, and backspace did not
restore what it had overwritten, because the engine's replace stack is keyed by a live marker and
this host's markers compared by identity. Four more were `doIndent`, left as a `TODO` here because
Vim reindents nothing and VS Code could not do it synchronously anyway - but the ex command executor
catches `NotImplementedError` and turns it into a message, so `:copy` inserted its lines and then
abandoned the command half way, leaving the caret past them. Four more were Select mode, which had
never been built: `exitSelectModeNative` was the one missing method, and most of what IdeaVim does
in it is IntelliJ bookkeeping this host has no equivalent of. Writing it turned up a second thing
nothing in the engine says out loud - typing over a selection replaces it, which IntelliJ's typed
action does on IdeaVim's behalf, so `gh` and then a letter left the letter *beside* the selection
instead of in place of it. And eight were Delete and Backspace in Select mode, which the engine
settles by asking the host what it has bound to those keys and running that - in IntelliJ the answer
is `EditorDelete`, which deletes a selection because that is what Delete does anywhere; here the
answer was nothing, so Delete in Select mode deleted nothing at all. The build fails if that list
changes in either direction: a new failure is a regression, and a fixture that starts passing has to
be taken out of the list, which is how the number goes down.

The parser is deliberately narrow and refuses far more than it accepts, because a fixture read
slightly wrong is worse than one skipped - it fails against a correct host and gets recorded as a
bug in it. It also has tests of its own, for the same reason the sweeps do.

The corpus went 314 -> 642 -> 966 -> 1,025 as the parser learned what these tests are written with.
First `exCommand("...")`, which is only string building; `//` comments between the arguments, which
have to go before the arguments are split, because a comment with a comma in it otherwise tears the
call into the wrong pieces; and the `${s}`/`${se}` selection markers. Those appear in 462 `after`s
and in not one `before`, so each of those fixtures is an ordinary one that additionally says where
the selection ended up - and the replay now checks it. Not one failed on the selection, which is the
first evidence from outside this repository that Visual mode here lands where Vim lands.

Then `trimMargin`, which was the single largest thing it could not read: 318 fixtures are written
with it rather than `trimIndent`, and it is a different rule - a line that does not carry the margin
prefix is kept exactly as it was, which is why IdeaVim reaches for it when the text under test has
indentation of its own. With `.repeat(n)`, `"a" + "b"` and `\uXXXX` alongside it, "an argument this
cannot evaluate" fell from 397 to 26. A fixture with no caret marker at all is harvested too - it
means offset zero, which is where `configureByText` puts one.

Four entries left the baseline without anything being fixed, and that is the thing to remember about
a harness like this. It was typing its setup commands through `parseKeys`, so `:nmap <Tab>
ihello<Esc>` pressed Escape on the command line instead of writing five characters into it. IdeaVim
types those literally and says why in a comment two lines long. Four fixtures had been recorded here
as bugs in this host that were bugs in the reading of them - which is exactly what the parser is
narrow to avoid, arriving through the one part of the harness that is not the parser.

Two things it does on purpose. Trailing whitespace is not compared, because IdeaVim does not compare
it either - thirteen fixtures assert that `]}`, a motion, removed two spaces from a line. And the
mode afterwards is not compared yet, only the text, the caret, and the selection where a fixture
marks one.

There are four sweeps now, and three of them find nothing. Keys and ex commands each hid something;
options and Vimscript functions were clean from the start, and the ex list is empty as of
`:command`. That is only worth believing because the sweep is tested too - and testing it turned
out to be harder than writing it. The first two attempts named a command that happened to be
unbuilt, and both rotted within a commit or two of being written, because the list they pointed
into is the list this port is emptying. What the test does now is run the same command against this
host and against this host with one service taken back out. A hole the test digs itself cannot be
filled in by accident.

`:w` and `:q` work, which they did not until recently, and the way that was found is worth writing
down. The inventory presses keys, and everything behind a colon was invisible to it - so `:w`, `:q`,
`:wq`, `:x`, `:bnext` and twenty more had been reporting "Not implemented yet :(" since the first
commit and nothing had noticed. Worse, they were not even crashing: the Vimscript executor catches
`NotImplementedError` on purpose and turns it into that message, so an ex command standing on an
unbuilt service apologises rather than failing. There is a second sweep now that types every
registered ex command and watches the messages rather than the exceptions.

`:command` and `:loadkeymap` were the last two, and both were the same discovery this port keeps
making: the code was already written and living in the IntelliJ module with nothing IntelliJ-shaped
in it. An alias is a name and the line it stands for; `:loadkeymap` is a table of `:lmap`s. Both
moved into the engine, where IntelliJ now uses the same implementation this does.

`:e file` and `:w file` work, and they arrive from opposite directions. `:w file` writes a file that
is not open in any editor, so there is nothing for VS Code to save - it goes to disk through Node,
which it has to, because `:w` reports `E212` when a write fails and a promise cannot answer a command
that has already returned. `:e file` is the reverse: nothing to read or write, only VS Code to ask,
and `vscode.open` is a command like the folds once the runner can carry an argument. Keeping it in
that lane rather than calling `showTextDocument` means it inherits the queue, the rejection branch
and the command-id check.

Paths are Vim's: `~` and `$VAR` are expanded, and a relative path resolves against the workspace
folder. Vim would resolve against the current directory, and a VS Code window does not have one.

`:e newfile` gives an empty buffer with that name, the way Vim does. It used to answer E447, with a
comment saying that VS Code's nearest equivalent - an untitled document - "has no path until it is
saved and then asks where to put it". That is true of `newUntitledFile` and false of the `untitled:`
scheme, which takes one: `untitled:/dir/new.txt` is an unsaved buffer whose `:w` writes to exactly
that file, with no dialog, and whose language VS Code derives from the name. Which is Vim's new
buffer under another spelling, and the reasoning that ruled it out had been about the wrong API.

`:bdelete N` asks for a buffer number, which VS Code does not have - an editor has a position among
the tabs and no identity beyond its file - so it says so instead of closing whatever happens to be
third.

Folds, `<C-W>` windows, `gt` tabs and `gd` are off it too, and all for the same reason: each of them
is a VS Code command, and the queue that undo needed already knew how to wait for one. What is new
is deciding *when* to wait, and the line does not fall where it first looks like it should. It is
not about what the command does but about whether this host knows what the command is. Splitting a
window, changing editor and closing one are dispatched by name from here, so it is known that they
touch no text and they do not hold the keyboard. Folds and `gd` arrive the other way round: the
engine asks for an action *by name* and the host runs it, and that same path is what an `<Action>`
mapping in someone's `.ideavimrc` goes down. It can name a reformat. So it waits - the conservative
half of a decision that cannot be made per command, since the host is not the one choosing them.

A command VS Code does not have rejects its promise rather than resolving it, and a runner that only
listened for success would leave the queue waiting for something that is never coming back - one
typo in an `<Action>` mapping and the keyboard would be gone until the window was reloaded. So both
halves are handled, and an unknown command says so.

`<C-W>` deserves a caveat. A Vim window is a view onto a buffer in a tree of splits; a VS Code
editor group is a column of tabs with one of them visible. They are not the same thing. What they
share is the part `<C-W>` is about - there is more than one place to be, they are arranged in two
dimensions, and you can split, close and move between them - so the direction keys, `<C-W>s`,
`<C-W>v`, `<C-W>c` and `<C-W>o` all land on the nearest true thing. `:split file` is where the model
runs out, and it says so rather than opening the file in the wrong place.

Blockwise Visual is off it, and it is the only Vim mode that needs the editor to have more than one
caret. The engine's model came from IntelliJ: a block is N carets with N one-line selections, torn
down and rebuilt on every motion, one of them primary and carrying the block's anchor. VS Code has
the same idea - `selections` is an array whose first entry is the primary - so the model ports
directly. What does not port is the assumption that the primary caret is the first one in the
document: after `<C-V>k` the moving corner is the *top* of the block, so the caret list stays in
document order, the primary is found by its flag, and the flush puts it first because that is where
VS Code looks for it.

Scrolling is off it. Vim moves the *view* - `<C-E>` by a line, `<C-D>` by half a window, `zt` to put
the current line at the top - and lets the caret follow. `visibleRanges` says where the view is and
the `editorScroll` command moves it by a number of lines, so `<C-E>`, `<C-Y>`, `<C-F>`, `<C-B>`,
`<C-D>`, `<C-U>`, `zt`, `zz`, `zb` and `H`/`M`/`L` are all Vim's arithmetic in line numbers and one
call.

Not `revealRange`, which is the API an extension is pointed at and which this used for months. It
says where a *range* should end up rather than where the view should be, and a real window put every
`AtTop` request five lines above the line it named - `zt` on line 18 left the view at 13, and
`<C-E>`, which asks for one line further down than it believes it is, walked the view four lines
*backwards* per press. Sticky scroll, a surrounding-lines setting, an editor padding: whichever it
was, a reveal is a request to be interpreted and that window interpreted it. `editorScroll` has no
range to reason about. It cost three wrong diagnoses to get there, all three of them plausible and
none of them it, and what finally settled it was sixty reveals in a trace with the requested line
and the reported viewport side by side.

Sideways scrolling is still missing: `visibleRanges` carries no columns and `editorScroll` moves
only up and down, so `zh`, `zl`, `zs`, `ze`, `zH` and `zL` report failure, which is what Vim does
when a scroll has nowhere to go. For the same reason `g0` and `g$` answer as if the line starts at
column 0 and ends where the buffer line ends - right whenever the line fits on screen.

`%`, `di(` and the rest of the bracket text objects need to know whether a bracket is code or is
written inside a string or a comment, or they land on the wrong pair. IdeaVim asks IntelliJ's syntax
tree. VS Code has the same knowledge and will not part with it synchronously - semantic tokens
arrive over a promise, and the question is asked once per character in the middle of a keystroke -
so this host answers from the text. It counts quotes along the line, which is what the engine
already does for `i"`, and gets `f("(", x)` right. It says nothing at all about comments, because a
comment is `//` in one language and `#` in another and guessing from the file extension would be a
table of lies; a bracket inside a comment is counted, which is what Vim does with syntax off.

IdeaVim's bundled extensions were not wired up when this was written, and twenty-five of the
twenty-seven are now. Each is a `@VimPlugin` function in the engine that `VsCodeExtensions.BUNDLED`
names, rather than an IntelliJ extension point; `surround` and `sneak` read their keys through the
engine's `readKeys`, which is modal input rather than a blocking loop. The two still absent are
`matchit` and `VimEverywhere`, and neither is waiting on a seam.

Indentation comes from `editor.options` rather than from a Vim option, and the reason is worth
stating because it looks like a gap: the engine has no `'expandtab'` or `'shiftwidth'` at all.
IdeaVim asks IntelliJ's code style, so `createIndentBySize` is a host question by construction, and
VS Code answers it - resolved for the file, its language and the user's settings, and detected from
the file's own contents when `detectIndentation` is on. So `>>`, `S` and Tab in Insert mode all
indent the way pressing Tab in the same file without Vim would. It is read on every keystroke
rather than cached, because the indentation of an open file is a thing the status bar can change.

Rebuilding an indent measures it in characters, so a line indented with two tabs comes back with two
spaces. That is the engine's arithmetic and IdeaVim does the same; it is written down as a test
rather than fixed, because fixing it means giving the engine a notion of display columns.

Undo is the one place where a host answer is a guess. VS Code owns the history and `undo` is a
command: it resolves a promise and reports nothing about what it did, while the engine needs a
boolean now. So `u` says it worked - which is wrong only when there was nothing left to undo, where
Vim would say "Already at oldest change". Keys pressed while the command is in flight wait for it
rather than racing it, so the guess affects the message and not what happens next.

## Checking it

```bash
./gradlew :vscode-extension:runInStubHost
```

This activates the built bundle in Node with `vscode` stubbed, takes the `type` handler the
extension registered, and types. It is the only check that covers the wiring in `activate`, which
no unit test can see - and the only one that distinguishes an engine that is live inside the
extension from one that is merely bundled beside it. `./gradlew test` runs it, along with the
Kotlin tests.

## What is not checked, and what now is

Nothing here is compiled against `vscode`: the extension host injects it at runtime, so the
`external` declarations are the one part of this module the Kotlin compiler cannot verify. A wrong
shape fails when a user presses a key. Worse, the stub the tests run against was written from the
same reading of the documentation as the declarations, so it agrees with them whether or not they
are right - a matched pair of identical mistakes passes every test.

`checkVsCodeApiDeclarations` compares every declaration against `@types/vscode`, which is the real
API surface published by the VS Code team. It runs as part of `./gradlew test`.

It checks that members exist, not that signatures match - a full TypeScript parse is a different
project - and it says nothing about *behaviour*. The extension has still never run in a real VS Code
window, which remains the largest untested assumption in this module.

Command ids are worse off than declarations, because there is nothing to check them against. `@types/vscode`
describes the API and publishes no list of command ids, so nothing offline can say whether
`workbench.action.focusBelowGroup` is a command VS Code has - and a test asserting that string is
asserting that the test and the code were written by the same hand, which they were. Every id in
this module was typed from the documentation.

The only list that exists is `getCommands`, so the extension asks for it at activation and writes
what it did not find to the output channel. That is worth what `VsCodeCommands.all` is complete, and
a hand-maintained list loses completeness quietly - so no id lives anywhere else, and
`checkVsCodeCommandIds` fails the build on a `workbench.` or `editor.` literal outside that file, or
on a constant in it that never made it into the list. The stub host checks the reporting end to end
by answering with a list that is deliberately three commands long.

None of that says the ids are right. It says the first run in a real window will name every one that
is wrong, in one line, instead of one Vim command at a time.

## Why Kotlin and not TypeScript

Kotlin/JS strips everything not reachable from an exported root. A TypeScript extension would have
to reach the engine through a hand-written `@JsExport` facade, and that facade fails silently - the
engine's own JS library once compiled to a 561-byte shell exporting nothing while every test passed.
Compiled together with the engine, the extension *is* the reachable root.

The engine's `public` API still bounds what this module can call: `internal` declarations are
module-scoped, so anything the extension needs has to be public - a real constraint, but a
type-checked one that the compiler enforces rather than a hand-maintained list that rots.
