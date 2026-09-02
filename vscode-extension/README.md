# IdeaVim for VS Code

The IdeaVim engine, compiled to JavaScript and loaded as a VS Code extension.

## Running it

```bash
./gradlew :vscode-extension:jsProductionExecutableCompileSync
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
event loop until a key arrives, which is what `getchar()` and IdeaVim's bundled extensions are built
on, and JavaScript has one thread and no way to stop it.

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

`gx` is the third shape of blind spot, and worth naming beside the other two. The key sweep presses
every key on a buffer of ordinary text, so `gx` returns before it ever asks for the service that
opens a URL. A hole that needs the right *text* under the caret as well as the right key is
invisible to a sweep that only varies the key.

Your `~/.ideavimrc` is read at startup, so mappings and options come from the file you already have.
The search order is IdeaVim's: `IDEA_VIM_CUSTOM_VIMRC`, then `~/.ideavimrc` and `~/_ideavimrc`, then
`$XDG_CONFIG_HOME/ideavim/ideavimrc`. A line that fails does not stop the rest, the way Vim carries
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

On macOS and Windows `"*` and `"+` are the same clipboard, as they are in Vim. Under X11 `"*` is the
primary selection and is kept separate.

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

The same question was then asked of `vim-engine` itself, and it has a better answer than expected.
The engine ships to VS Code compiled to JavaScript, so a test in its `jvmTest` source set is a test
of behaviour that reaches users on a platform the test never touches. 430 of its tests run on both
targets and 221 run only on the JVM - and nearly every one of those is JVM-only *by construction*,
because the JDK is the reference implementation it checks against. A differential test of
`Character.isLetter` cannot run where there is no `Character`. Exactly one file was there for no
reason at all, put there by the habit of typing `org.junit`, and it has moved. `JvmOnlyTestsTest`
now pins the list in both directions with a reason for each, and marks the two that could move if
somebody rewrote their JUnit 5 features.

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
`src/jsTest/fixtures/known-fixture-failures.txt`, grouped by what is actually wrong - which is six
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
the current line at the top - and lets the caret follow; VS Code's extension API has no verb for
that, only `revealRange`. What makes them reconcilable is `visibleRanges`, which says where the view
currently is: read it, do Vim's arithmetic in line numbers, and reveal the line that should end up
at the top. `<C-E>`, `<C-Y>`, `<C-F>`, `<C-B>`, `<C-D>`, `<C-U>`, `zt`, `zz`, `zb` and `H`/`M`/`L`
are all that one call. Sideways scrolling is not: `visibleRanges` carries no columns and nothing can
scroll by one, so `zh`, `zl`, `zs`, `ze`, `zH` and `zL` report failure, which is what Vim does when
a scroll has nowhere to go. For the same reason `g0` and `g$` answer as if the line starts at column
0 and ends where the buffer line ends - right whenever the line fits on screen.

`%`, `di(` and the rest of the bracket text objects need to know whether a bracket is code or is
written inside a string or a comment, or they land on the wrong pair. IdeaVim asks IntelliJ's syntax
tree. VS Code has the same knowledge and will not part with it synchronously - semantic tokens
arrive over a promise, and the question is asked once per character in the middle of a keystroke -
so this host answers from the text. It counts quotes along the line, which is what the engine
already does for `i"`, and gets `f("(", x)` right. It says nothing at all about comments, because a
comment is `//` in one language and `#` in another and guessing from the file extension would be a
table of lies; a bracket inside a comment is counted, which is what Vim does with syntax off.

IdeaVim's bundled extensions (`surround`, `commentary`, `easymotion`) are not wired up. They are a
separate port rather than a missing service: they live in the IntelliJ module, they are loaded
through an extension point that has no Kotlin/JS equivalent, and `surround` needs `getchar()` -
IntelliJ answers that by blocking on a modal input loop, which a JavaScript host cannot do at all.

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
