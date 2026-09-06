# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this repository is

**Vimperor**, a Vim emulator for VS Code, grown out of a hard fork of IdeaVim.
Two parts:

| Path                | What it is                        | Compiles to       |
|---------------------|-----------------------------------|-------------------|
| `vim-engine/`       | The Vim engine, host-independent  | JVM **and** JS    |
| `vscode-extension/` | The Vimperor VS Code extension    | JS (Kotlin/JS IR) |

`vim-engine` is Kotlin Multiplatform: the engine is in `src/commonMain/kotlin`,
**not** `src/main/kotlin`. It still has two *targets* - a change to `commonMain`
has to compile for JS as well as the JVM, and its tests run on both - but it now
has only one host.

Five Gradle modules: those two, plus `api`, `vim-annotations` and
`annotation-processors`. There is no upstream to contribute to. The `upstream`
remote is read-only and is kept to read from, because IdeaVim's history is often
the fastest answer to "why is this code like this".

## `src/test` is data, not a source set

The IntelliJ plugin is gone - 408 files, deleted once the port no longer needed
it. `src/test` stayed. It holds IdeaVim's 11,727 tests, which cannot run without
the plugin, and **the VS Code host mines 2,423 replayed fixtures out of them as
text**: `VimFixtures.load` reads the `.kt` files and `VimFixtureReplayTest` plays
the keys against this host. 2,417 pass.

Nothing compiles that directory. It is the largest outside check the port has,
and it survives because it never needed to be code. `vscode-extension` declares
it a task input. Do not tidy it, reformat it, or delete from it: every file in
there is a potential fixture, and the corpus has grown four times over by the
harness learning to read more of what is already written.

The six that fail all name an IntelliJ action with no VS Code command behind it -
`ideajoin`, `EditorCloneCaretBelow`, `EditorSelectWord`, `EditorRight`,
`EditorDown`. They are not portable and are not waiting on anything.

## What is left

Nothing structural. Every key the engine registers reaches something this host
has built or a refusal that is itself an answer, and `VsCodeUnimplementedTest`
asserts the empty list; ex commands are at parity in both directions. 22 of
IdeaVim's 24 extensions are bundled. One injector seam is still `TODO` -
`pluginActivator` - and nothing in the engine calls it.

What remains is a backlog of small things and one piece of tidying: the engine
still carries `com.maddyhome.idea.vim` packages, which can now be renamed in one
pass - see **Packages**.

## The record below

Most of what follows was written while the plugin still existed, and is kept
because it is the design record of the port: what was tried, what was measured,
what turned out to be wrong. Where it compares this host with IdeaVim, that
comparison is still the right one - IdeaVim is the reference implementation and
its behaviour is what the fixtures assert. Where it says "both hosts", read "the
engine and its one host".

**Eleven of 2,081, at the time.** The corpus's largest single finding was `u`: it undid a *keystroke*
where Vim undoes a command, so `ciwfoo<Esc>u` put back `fo`. 128 fixtures said so at once.
This host writes to VS Code once per keystroke and VS Code's default is an undo stop before
and after every edit an extension makes; the engine says where an undoable unit begins,
through `VimKeyBasedUndoService`, and this host answered all three of those calls with
nothing - under a comment claiming the boundaries "are already where they belong" because it
"gives VS Code a whole command as a single edit". It does not. `UndoStops` is where they go
now, and `edit` is passed `{ undoStopBefore, undoStopAfter }`.

Two things went with it. **The caret after `u` is the host's**, restored with the text -
IdeaVim gets it from IntelliJ's `UndoManager` and this host gets it from VS Code - and a
selection VS Code puts back has to collapse to its *start*, because Vim's `u` is not Visual
mode and leaves the caret on the first line of the change. And the replay was flushing once
at the end rather than after every keystroke, which is a different program: the document was
written once, so `u` asked VS Code to undo a document it had not touched.

Of the eleven left, five name an IntelliJ action: `ideajoin` is IntelliJ's language-aware join, and the rest
name an action id with no VS Code command behind it - `EditorToggleCase`,
`EditorCloneCaretBelow`, `EditorSelectWord`, `EditorDown`. The other six are one each,
and one of them is instructive: three of `yankring`'s four `<C-P>` fixtures were fixed by the
undo work without being touched, which says where the fourth probably lives.

Eleven of the thirteen the extension tests found were one thing, and it is the shape this
port keeps meeting. **`.` repeats a *handler*, not only a command** - a mapping an extension
installed is repeated by running the handler again with the keys it read played back out of
`Extension`, and this host's `RepeatChangeAction` was a reimplementation that had only the
command branch. `ysiw)l.` surrounded once and the `.` did nothing. The plugin's copy was
right and was 60 lines of engine-shaped code in `src/main/java`; the body is
`repeatLastChange` in `vim-engine` now and both hosts call it. What stayed behind is one
lambda: IdeaVim's split-mode undo marker, which is an RPC to a backend this host has no
equivalent of.

**The corpus grew from 1,047 to 2,423 by fixing the harness, and that is still where the next
fixtures are.** The three most recent steps were all the same kind of thing, and one of them
came only after the report was made to say what it meant: "the test sets something up this
cannot repeat" was 919 refusals emitted by five different checks, so the largest number in the
report was the least actionable thing in it. Split apart, the answers fell out - `commandToKeys`
is `enterCommand` written the long way (`VimTestCase` *defines* the second as the first);
assertions that only look can be read past, since dropping one cannot change what the rest of a
method means; `Lists.newArrayList` is `listOf`; and a `val` bound to a list was unreadable, which
refused every `doTest` standing after it. The report prints example locations now as well as
counts, because a number says how much is not seen and a file name says what to read.

**The sentinel that read a dollar wrong is the cautionary half.** `"k\$d"` is the keys `k$d`, and
by the time the interpolation check saw it the escape was gone and it looked like `${...}`. The
fix carries "this one was literal" through as a sentinel character - and the first choice of
character was NUL, "which no fixture can contain". That is an assumption about Vim, not about
text: `:s/\./\n/g` inserts NUL and `SubstituteCommandTest` has a fixture named `test dot to nul`
whose expected text is three of them. It is a private-use codepoint now.

**The corpus grew from 1,047 to 1,236 by fixing the harness, and that is where the next
fixtures are too.** Twice it turned out to be looking at less of `src/test` than it thought.
It only found backtick-quoted test names, so IdeaVim's 578 plainly-named tests were
*invisible* rather than refused - they never reached the skip counts either, so the reported
yield was measuring what could be parsed out of what could be seen. And it only read a method
whose body *started* with `doTest`, so a method that bound its text first, or called `doTest`
more than once, gave up everything but the first call.

Getting that second one honest took three things, each of which is a rule about IdeaVim
rather than about parsing. A statement standing *before* a `doTest` may be its setup -
`setRegister('a', "World")` and then a paste of register `a` - so anything the harness cannot
read refuses the calls after it, and only those; refusing all of them costs 95 fixtures.
Options an earlier `doTest` set are still set for a later one, because each call re-seeds the
text and none of them re-seeds the options, so the setup accumulates. And a trailing lambda
ends at its own brace, which with one call per method was indistinguishable from the rest of
the method.

Nine of the 189 failed. Seven were real gaps here and all seven are fixed; the other two were
**order dependence**, which was the worst thing this found. `VimSearchGroupBase` keeps
`lastPatternTrailing` and three neighbours in a `protected companion object` - one per
process, however many search groups exist - so `n` after `:s` inherited a `3` from a `/and/3`
two fixtures earlier. Every fixture was being replayed into whatever its neighbour had left
behind, so a pass meant less than it looked like it did. The replay resets the engine between
fixtures now, the way IdeaVim's own `VimTestCase.setUp` does.

The third step was the extension tests - 1,039 `doTest` calls skipped wholesale on the
grounds that "the extension tests need plugins this host has not ported", which stopped
being true when twenty-two of the twenty-four were. What kept them out after that was where
their setup lives: `enableExtensions`, and the `let` that has to precede it for
`camelcasemotion`, are in `setUp` - a *different method* - and `enableExtensions` sets the
extension's toggle option, which is what `:set surround` does. So `set <name>` being `E518`
here until the extension options were registered was the other half of it: these fixtures
could not have been replayed before that was fixed either. 461 of the 1,039 are `matchit`.

2,778 `doTest` calls exist under `src/test` and 2,423 fixtures are harvested - more than that
number, because the `configureByText`/`typeText`/`assertState` shape is not a `doTest` call at
all. What is refused is
counted and printed with every run, and the largest buckets now are tests that set something
up in Kotlin, tests IdeaVim marks `@VimBehaviorDiffers`, and arguments that need a compiler.

**What the eight had in common was blockwise Visual, and the fix was three rules a host
owes the engine.** A block's caret goes in the *active corner's* column, not the block's
right edge - the two are the same only while the block is drawn rightwards, which is why
`<C-V>jl` read correctly for so long and `<C-V>bjj` put every caret but one six columns
out. A line too short to hold the block's slice contributes **no caret at all**, and that
one rule is what collapses `<C-V>j` onto an empty line to a single caret. And carets that
have stopped being different carets merge - on a genuine overlap, not on touching, which
was measured: two linewise selections meeting at a newline stay two.

**The other half was per-caret state that has to outlive the caret**, and it is where
"the engine has **no per-editor storage**" needs qualifying. That is true of the engine
and *not* of IdeaVim, which mirrors the primary caret's copy of seven properties onto the
editor - `userDataCaretToEditor` in `UserDataManager.kt`, plus `_vimLastColumn` through its
`Or` variant - and reads it back when a new primary has none. A block invents its carets on every motion, so the caret holding the
primary flag is usually one that has never run anything: without the mirror `1v` worked
once and not twice. `vimSelectionStart` is the same story with a different default -
IdeaVim's is the far end of whatever that caret has selected, this host's was zero, and a
caret drawing a linewise selection from its own anchor drew one from the top of the file.

**The tenth was `'incsearch'` previewing an ex command, and it was in the plugin all
along.** This file had it recorded as needing "the command line parsed for a range and a
pattern before it is run, which is a different piece of machinery from previewing `/`".
True, and the machinery already existed: `parseCommandLineForPreview` in `ExEntryPanel`,
sitting in `src/main/java` beside a Swing document listener with no `com.intellij` in the
body of it. Same shape as `IjSearchWindowGroup`, and the same answer - it is
`incsearchPreviewRequest` in `vim-engine` now, 64 lines lighter in the plugin, and both
hosts read it. Vim has previewed `:s`, `:g` and `:v` since 8.0; this host previewed `/`
and `?` only, and a test here asserted that as correct.

Two things a command's preview does that a search's does not, and both are in the
fixture: it searches from the *range* rather than from the caret, so `:%s/dolor` shows
the first match in the file; and it **drops the Visual selection**, because `v` then
`:<C-U>%s/foo` is a command over the whole file that merely started in Visual mode. `v`
then `/foo` is the opposite - there the caret move *is* the selection move.

**And the harness was harvesting tests IdeaVim has `@Disabled`.** Two of the twelve were
that: `search in one time from select mode` sat here as "expected [81], actual [82]" when
IdeaVim's own caret is at 82, and `repeat command with execution of ij action` had a
reason written against it - a missing IntelliJ action - that was not why it failed. A
disabled test does not pass over there either, so holding this host to it measures
nothing. Before believing an entry in `known-fixture-failures.txt`, check that the fixture
passes in the plugin: the way to do that is a throwaway test in `src/test` that runs the
same keys and prints `caretModel.allCarets`, under a read action and after
`PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()`, which is what `doTest`
does before it asserts.

**Eleven, not twenty-four.** This file said 24 and the number counted the file rather
than the concept, exactly as "26 extensions" did: `IjOptions` declares 24 and ten of
them - `wrap`, `list`, `cursorline`, `relativenumber`, `textwidth`, `breakindent`,
`colorcolumn`, `bomb`, `fileencoding`, `fileformat` - are ordinary Vim options this host
already has.

**Declaring an option is not implementing it, and `'wrap'` is the warning.** `VsCodeOptions`
has two groups on purpose: the ones that reach VS Code, and the ones declared only so that a
`~/.vimrc` loads instead of reporting `E518` on every line. `:set nowrap` sat in the second
group and did nothing at all, which is worse than an error - the user is told nothing.

Getting it out of that group took three attempts and the first two were wrong in the same
way, which is the part worth keeping. VS Code has no per-editor setting for the wrap:
`TextEditorOptions` carries the gutter, the tab size and the caret shape and not this. What it
has is `editor.action.toggleWordWrap`, and **a toggle cannot be pointed at a state, only
flipped** - so it needs to know which way the editor currently is, and VS Code will not say.
Tracking a belief was the obvious answer and is unfixable rather than merely fragile: one
wrong belief and every command means its opposite. In a real window it did exactly that -
`:set nowrap` wrapped the file and `:set wrap` unwrapped it - while every test passed.

`WorkspaceConfiguration.update` writes an absolute value, so it cannot be inverted, and the
same setting reads back, so `:set wrap?` answers from the editor rather than from a memory of
what this host last asked for. The cost is that a window-local Vim option is written as a
setting that is not: it lands in the workspace when there is one and in the user's settings
otherwise, and it persists. That is the better of the two trades.

The default is the other half. Vim wraps and VS Code does not, so an option that started at
Vim's answer would turn wrapping *on* in every editor the moment Vimperor loaded. It is
seeded from the editor's own setting, scoped to the document - unscoped, VS Code answers for
the *window* and ignores a `[markdown]` block turning wrap on, which is how people usually do
it.

**And a setting has to be written where it is read**, which cost one more round. Whether the
`[markdown]` block or the plain value decided a file's wrap was being worked out per call, by
comparing a document-scoped read against a URI-scoped one - so `:set wrap` wrote the language
value and took effect while `:set nowrap` wrote the plain one and was shadowed by what the
previous command had just written. The two directions were not the same setting, so the second
could not undo the first. Every write goes into the language block now: it is the layer that
wins, which is both why people use it and why it is the only one certain to be reversible. The
cost is that `:set nowrap` on a Kotlin file writes `"[kotlin]"` into the user's settings.

Anything else moved out of the accepted group will need all four questions asked of it: can it
be set rather than toggled, what is its default here, is the read scoped, and does the write
land in the layer the read comes from.

**`'expandtab'`, `'tabstop'` and `'shiftwidth'` were the next three out, and they were easier
than `'wrap'` - for a reason worth knowing before reaching for the fourth.** VS Code models
indentation as *editor state* and the wrap as *configuration*: `TextEditorOptions.tabSize` and
`insertSpaces` are per editor, writable with an absolute value, and read back from where they
were written, so all four questions answer themselves. Which group an option falls into is a
fact about VS Code's API, not about the option.

The seed is what keeps both answers. A user who has set nothing must go on getting VS Code's -
resolved per language, per file and by detection - because that is what makes `>>` agree with
pressing Tab without Vim, and because Vim's own `ts=8 sw=8 noexpandtab` applied on open would
re-indent every file anybody opened. So each editor's options start at the editor's answer and
are written back only when they differ, which also makes `:set tabstop?` honest.

Three numbers, not one: `'tabstop'` is what a tab *draws as*, `'shiftwidth'` is what `>>`
*moves by*, and VS Code has had `indentSize` beside `tabSize` since 1.85. `'shiftwidth'`
defaults to 0 here rather than Vim's 8, because 0 is Vim's own spelling of "however wide
`'tabstop'` is" and so defers to the editor along with the rest.

Two traps, neither of them predicted. **Seeding an option fires the engine's change listener**,
which ran the apply re-entrantly: seeding `'tabstop'` wrote the still un-seeded `'expandtab'`
onto the editor, and the next line read that back as the editor's own answer. Read every value
before writing any of them. And the replayed fixtures caught the `'shiftwidth'` default -
four `ShiftRightTest` fixtures went from four spaces to eight - which is the corpus doing the
job it is kept for.

**And it still cannot reach everything.** VS Code lets an editor carry a word wrap of its own
*on top of* the setting - `Alt+Z` sets one, and so does `editor.action.toggleWordWrap`. It
wins, and there is no API to read it or clear it. An editor in that state ignores `:set
nowrap` however correctly the setting is written, and the only way out is `Alt+Z` again. That
is the same missing API that made the toggle unworkable, seen from the other side, and it is
worth knowing before reaching for a VS Code command to implement an option: **a command that
sets hidden state is not an implementation, it is a bug with a delay on it.**

The reason this took three attempts, and the lesson that generalises: every one of them passed
its tests. The stub answered `null` for an unscoped configuration read, so the tests agreed
with each other and with none of them agreed with a window. A host seam whose *state* lives in
VS Code cannot be checked by a stub that only records what it was told - the stub has to
answer the way the editor answers, or the test is a mirror.

Three more of the `idea*` family are answered here now, and the interesting thing about
them is how little they needed. `'ide'` is `env.appName` - the option exists because one
`~/.ideavimrc` is read by every JetBrains IDE and branches on `if &ide =~? 'clion'`, and
a config shared with a VS Code install wants the same question answered rather than
`E518`. `'ideastatusicon'` is IdeaVim's clickable Vim icon; this host has the mode
indicator instead, and `enabled`/`gray`/`disabled` carry over to it without straining.
`'ideawrite'` picks between `workbench.action.files.save` and its `saveAll`, both of
which were already written.

**`'ideawrite'` defaults to `file` here where IdeaVim's is `all`, deliberately.** IdeaVim
can afford `all` because IntelliJ writes your buffers on its own account anyway; VS Code
does not autosave unless asked, so the same default would save files the user never
mentioned - through format-on-save and whatever else a save is wired to. Vim's `:w`
writes one buffer. `set ideawrite=all` is there for anyone who wants IdeaVim's.

The eleven left are `ideacopypreprocess`, `ideajoin`, `ideamarks`, `idearefactormode`,
`ideavimsupport`, `lookupkeys`, `trackactionids`, `visualdelay`, `closenotebooks`,
`oldundo` and `unifyjumps`. They are not a backlog: `ideamarks` wants a bookmarks API,
`unifyjumps` a navigation history an extension can read, `trackactionids` the ability to
observe another command running, `lookupkeys` a completion popup an extension can see -
and `visualdelay`, `oldundo` and `closenotebooks` are IntelliJ dev flags and a workaround
for a JetBrains bug, so there is nothing to port at all.

**Every key the engine registers now reaches something this host has built, or a
refusal that is itself an answer.** `VsCodeUnimplementedTest` presses all of them and
asserts the list; the list is empty. Getting there took three kinds of answer and they
are worth telling apart, because the second is easy to miss: a service gets written;
or a service turns out to have been written already under another name, which is how
`[m` and `]m` left - their note said "needs a language server" and stopped being true
when `DocumentSymbols` cached the Outline tree; or the honest answer is *no*, and
saying no is an implementation - `[s` and `]s` answer -1, the engine's "no such
motion", and beep.

`q:`, `q/` and `q?` were the last, and they went the second way with a twist worth
keeping. The feature is history in a buffer you can edit, and `IjSearchWindowGroup` -
in `src/main/java`, named `Ij` - had **no `com.intellij` import in it at all**. It is
`SearchWindowGroupBase` in the engine now. What a host actually owes is one level
down: `VirtualBufferGroup`, an editor over no file. VS Code has exactly one editable
kind of those, an untitled document, and the save prompt it would raise on the way out
is answered by reverting before closing.

That port also found a hole nothing else could have: **a keystroke can edit an editor
other than the one it was typed into**, which is what `<CR>` in the command-line window
does, and `VimHost.handle` flushed only the editor the key went to. `:s` from `q:`
edited the buffer and never reached the screen. Every editor is flushed now, which
costs a comparison each - `DocumentBuffer.flush` returns at once when the text is
already what it last wrote.

An empty list is not the end of the port. It says every key lands somewhere, not that
every key is right; the fixtures and the suites say that.

**Twenty-four, not twenty-six.** This file said 26 for a long time and the number was
never checked; `IdeaVIM.ideavim-frontend.xml` declares 24 `vimExtension` points, and
`windownavigation` - which looks like a 25th in `src/main/java` - is not one of them.
It is `ToolWindowNavEverywhere`, support code that `hints` constructs.

**Twenty-two are ported and bundled** - `ReplaceWithRegister`, `vim-paragraph-motion`,
`textobj-entire`, `mini-ai`, `CamelCaseMotion`, `indentwise`, `textobj-user`,
`targets`, `abolish`, `textobj-indent`, `argtextobj`, `commentary`,
`highlightedyank`, `exchange`, `sneak`, `surround`, `multiple-cursors`, `yankring`,
`functextobj`, `classtextobj`, `NERDTree` and `youcompleteme` - which is every one
whose substance this host can carry. Each lives in
`vim-engine/src/commonMain/.../extension/<name>/` as a `@VimPlugin` function, the
VS Code host lists it in `VsCodeExtensions.BUNDLED`, and the plugin keeps a
two-line `VimExtension` adapter that calls the same function so IntelliJ is
unaffected. The adapter goes when the plugin does.

The two that are left - `matchit` and `VimEverywhere` - **are not being ported, and
that is a decision rather than a backlog entry.** Neither is waiting on a seam and
neither should be proposed again. Treat 22 of 24 as complete.

`matchit` wants to know what a *token* is, so that `%` can jump between `if` and
`endif` and between HTML tags, and the one thing VS Code tells an extension about a
file's structure is where its symbols are - functions and classes, nothing smaller.

**Its size is a trap.** `matchit` is ~465 of the `doTest` calls the fixture harness
refuses - the largest single refusal bucket by a wide margin - so anyone listing what
is left will find it at the top and mistake it for the biggest available win. It is
not available. The number measures how thoroughly IdeaVim tested an extension this
host cannot have, not how much is left to do.

**`youcompleteme` was on that list twice over and should not have been**, and the
mistake is instructive. The reason given was that it edits `'lookupkeys'`, an
IntelliJ-only option - true of how it is written and not the obstacle. The obstacle is
that VS Code will not tell an extension whether the completion popup is open:
`suggestWidgetVisible` is a context key and context keys are write-only, which is why
`VsCodeInjector.lookupManager` answers null. But a `when` clause *can* read it, and the
whole extension is one sentence - `<Tab>` cycles the list instead of accepting from it
- so two manifest bindings carry all of it. Reading the file was what settled it; the
import list said `IjOptions` and the body said `injector.lookupManager`.

**`VimEverywhere` was built for this host and then taken out again, which is worth
recording so it is not built a second time.** Compare `youcompleteme`, which has the
same shape and was kept: the test is not whether the keys live in `package.json` but
whether what the manifest can carry is the whole extension or a fraction of it. It is four features. `h`/`j`/`k`/`l` in
any Swing `Tree` becomes nine keys under `listFocus` and works. `<C-W>hjkl` from
inside a tool window becomes twelve chords under `!editorTextFocus` and half works -
outside the editor it crosses into other panes, inside it the engine's own `<C-W>` uses
`focus*Group`, which stops at the edge of the editor area, so the obvious case of
`<C-W>h` from the editor into the file tree does nothing. `h`/`l` across a `JTable`'s
columns has no target at all: VS Code has no table an extension can bind into.

And the fourth is the one it is named for. `ToggleHintsAction` walks the Swing
accessibility tree from the root pane and labels *every clickable component in the
window* - tool window buttons, tabs, gutter icons, dialog controls - then clicks the
one you type. **Be precise about why that cannot come across**, because the loose
version of this claim is wrong: an extension can certainly draw labels over editor
*text*, with the same `TextEditorDecorationType` that `highlightedyank` uses, and that
is how every easymotion-style VS Code extension works. What it cannot do is touch the
workbench - there is no overlay API and no accessibility tree to enumerate, so the
activity bar, the tabs, the sidebar rows and the status bar are unreachable. An
editor-only version would be a different extension wearing this one's name.

Two of four, one of them half, and none of them the feature the name promises. That
was judged not worth shipping. What the attempt did leave behind is the `set <name>`
fix below, which was worth the whole detour.

**`NERDTree` and `VimEverywhere` are bundled but they are half an extension each, and
the half is the interesting part.** It is two things sharing a name. Six ex commands - `:NERDTree`,
`:NERDTreeToggle`, `:NERDTreeFind` - say nothing but "show me the file tree", and
those moved to the engine: IdeaVim wrote them as IntelliJ action ids
(`ActivateProjectToolWindow`), which was the only reason they could not travel, and
`injector.fileTree` is what they say now. Thirty *other* keys - `j`, `k`, `o`, `s`,
`d` - apply while the cursor is inside the tree, and those are not portable and are
not waiting on anything: **a key pressed in the sidebar never reaches an extension**.
`type` is the editor's command and the Explorer is not an editor.

So the VS Code half of those keys is `package.json`, which is a mechanism nothing
else in this port uses: declarative, fixed at install time, unable to be turned on by
`set NERDTree` or remapped by `g:NERDTreeMapOpenSplit`. The one thing still decided at
runtime is a `when` clause, and `vimperor.nerdtree` is it - set from
`VimHost.isExtensionEnabled`. That gate is not a nicety. `d` in the Explorer deletes a
file, and an ungated binding would arm it for everyone who installs the extension.
`NerdTreeManifestTest` exists to say so, and the stub host now checks that any binding
running a *VS Code* command is gated at all.

Seventeen of the thirty keys have honest equivalents. The other thirteen are listed
with their reasons in `NerdTreeManifestTest` - mostly depth-aware navigation (`p`,
`P`, `J`, `K`, `<C-J>`, `<C-K>`), which VS Code's list commands do not have, and
changing the tree's root (`C`, `u`, `cd`), which its Explorer does not allow.

**The gap the `VimEverywhere` attempt walked into was bigger than itself: `set <name>`
did not work on this host at all.** IdeaVim's documentation enables every extension that way
and `Plug` is the other route, for a config borrowed from Vim - but only `Plug` was
wired here, so `set surround` was `E518` and a config runs with errors suppressed, so
it failed silently for all twenty-two. `registerExtensionOptions` is what
`VimExtensionRegistrar` does for IntelliJ: a global toggle option per extension whose
listener enables and disables it. Two traps in it, both found by a red test. The
option is declared on `Options`, a Kotlin object that outlives an injector, while the
*listener* lives on the option group, which does not - so registering only when the
option is absent leaves every later host with an option nothing listens to. And the
loader writes the option back in both directions, because the engine's own `:Plug` and
`:packadd` reach `enableExtension` without passing through an alias at all.

`textobj-user` is the one exception to "two-line", and it says something about the
engine rather than about that extension: it registers Vimscript *function
handlers*, and the loader's teardown removes mappings and listeners by owner but
knows nothing about functions. So its adapter keeps a real `dispose`, and the VS
Code host has `VsCodeExtensions.TEARDOWN` for the same reason. Anything else that
registers by name will need an entry there.

**The last three to be bundled were all held up by the same thing, and it took two
different answers.** Each needed something only VS Code knows, and VS Code says all of
it over a promise while the engine is inside a keystroke and cannot wait. The two
answers are worth telling apart, because which one a case needs is decided by whether
the caller has to *return a value*.

**`yankring`: continue later.** Its `<C-P>` undoes the paste and re-pastes an older
entry, which needs `u` to have finished by the next statement. IntelliJ's undo is
synchronous; VS Code's is a command the host dispatches and cannot wait for -
`VsCodeInjector.undo` returns `true` immediately and holds the user's *keys* until it
lands, which is no help inside one mapping, so the re-paste landed on text the undo
had not removed yet.

`VimApplication.runAfterHostCatchesUp` is the seam: the work that has to see the new
document goes in a callback, and the host runs it once the commands in flight have
landed and every editor has been re-read - immediately, on a host whose edits never go
out of step, which is what IntelliJ's one-line implementation says. Two things a
caller learns the hard way, both of them the same lesson `readKeys` taught: whatever
the callback closes over is restored *after* it runs, so `undoAndRepaste` had to move
its register save inside; and the buffer is a different buffer by then, so nothing may
carry an offset across.

**`functextobj` (`am`, `aM`, `im`) and `classtextobj` (`ac`): know already.** These
ask `injector.psiService` where a function or a class begins and ends, and a text
object has to *return a range now* - continuing later is no use to it. So the shape
that fits is the other one: `DocumentSymbols` asks
`vscode.executeDocumentSymbolProvider` ahead of time - on activation, and after every
change to any open document - and `VsCodePsiService` answers from the cache.

The cache is honest about its one gap rather than papering over it: what is held
belongs to a document *version*, an edit invalidates every offset in it, and for the
moment between an edit and the language server answering these text objects decline.
A stale range would be worse. The refresh is debounced so that typing a word asks
once, and `Settle` exists only so the whole cache can be tested without an event loop.

What a `DocumentSymbol` does not carry is a body range, and the two rules
`VsCodePsiService.bodyOf` uses are the only things inferred rather than known: match
the braces backwards from the last `}`, or, when there is no brace, take the run of
lines indented more deeply than the definition. The second is not an approximation of
Python's rule, it *is* Python's rule, and it gets Ruby right from the other end -
`end` sits at the definition's own indentation, so it falls outside the run.

**`commentary` and `highlightedyank` are the pattern for the ones that are left**,
and a different one: their bodies were *not* engine-only, and the answer was not to
make them so. Toggling a comment needs to know that a comment is `//` here and `#`
there; a flash that fades needs a timer. Both are facts about the host, so the
engine names the capability - `injector.commentService`, `VimApplication.schedule`,
`VimHighlightingService.addSearchHighlighter` - and each host supplies it. In every
case both hosts already had it and neither exposed it, and `exchange` then needed
nothing new at all - the two seams those two added were the whole of its debt.

The engine has **no per-editor storage**, and `IjVimEditor` throws from `equals` so
it cannot be a map key either. An extension that kept state in IntelliJ's editor user
data keys it by `editor.getPath()` instead - see `exchange` and `multiple-cursors`.
The *hosts* have it, and a host may owe it: IdeaVim's `userDataCaretToEditor` mirrors
the primary caret's copy of `vimSelectionStart`, `vimLastColumn` and its position,
`vimLastVisualOperatorRange`, `registerStorage`, `markStorage` and `lastSelectionInfo`
onto the editor, which is how they survive a block Visual motion inventing a new primary
caret. This host mirrors one of the seven - `vimLastVisualOperatorRange`, on
`VsCodeCaret` - and reaches the same end two other ways: the block layout hands the new
primary the anchor and the remembered column outright, and `vimSelectionStart` falls back
to the caret's own lead offset. The three left - `registerStorage`, `markStorage` and
`lastSelectionInfo` - have not been needed by a test yet, and are named here so the next
one is found rather than rediscovered. See the note above on
the replayed fixtures.

A caret is a value the VS Code host holds, not a document marker, so **the engine's
edits have to move the other carets** - `VsCodeEditor.shiftCaretsAfterEdit`. IntelliJ
gets that from its document. Nothing needed it until `multiple-cursors` arrived,
which is also how one of IdeaVim's own replayed fixtures started passing.

A candidate is an extension whose *body* uses only the thin API and the engine,
whatever its registration does. Screen by compiling, not by grepping imports - the
two packages named `com.maddyhome.idea.vim.extension`, one in each module, make a
name filter useless.

`VimExtensionFacade` is in the engine now, which was worth more than any single
port: it is what an extension calls to register a mapping, and while it lived in
the plugin a portable extension still could not follow. Moving it unblocked
`camelcasemotion` and `indentwise` immediately, and `abolish` could not have
followed without it either - `executeNormalWithoutMapping` is what `cr{motion}`
uses to press `g@`. What stayed behind is in `VimExtensionFacadeIj.kt`
- the functions taking an IntelliJ `Editor` or `DataContext`, as top-level
functions rather than members, since Kotlin cannot add a member to an object from
another module. `inputKeyStroke` is the only one with a real reason to stay: its
unit-test branch reads IntelliJ's `TestInputModel`. Ex commands are
at parity outright: every one IdeaVim declares is either in the engine or declared by
this host - `:resize` was the last to move and `:actionlist` is the VS Code host's
own. `ExCommandsOnlyInIntelliJTest` asserts the empty list, in both directions.

**The extensions were never blocked on `getchar()`, and the two that read a key are
not blocked any more either.** The standing explanation was wrong in its details -
`getchar()` is a Vimscript function nothing here calls - but two extensions,
`surround` and `sneak`, did read a key through `injector.keyGroup.getChar`, which
*blocks* until one arrives. IntelliJ answers it by pumping a nested event loop; a
runtime with one thread cannot, so the VS Code host returned `null` and such an
extension silently did nothing.

`readKeys` in `vim-engine/.../extension/ExtensionInput.kt` is the answer, and it was
sitting in the engine already: modal input, which `ModalInputConsumer` routes every
keystroke through while a prompt is open, is a *non-blocking* read that both hosts
implement - it is how `:s///c` asks its question. Both extensions use it and both
are ported. Three things it has to get right, each of them learnt from a test going
red:

- **One session, however many keys.** Reading a character and then opening a second
  prompt for a tag name loses a keystroke in the handover - `ysiw<em>` arrives as
  `<m>`. So the caller supplies a predicate, and the prompt stays open until it says
  there are enough keys.
- **The work goes back inside a command.** IntelliJ refuses a document change made
  outside one, and the change no longer happens inside the keystroke that set one up.
- **`.` replays through `Extension.consumeKeystroke`.** A repeat re-runs the handler
  rather than the keys, so the keys the handler read have to be recorded and handed
  back - and the "this change was an extension's" marking, which
  `ToHandlerMappingInfo` sets after the handler returns, has to be restored after the
  callback too.

The cost is real: the extension returns before it knows what the user typed, and
continues a keystroke or two later.

What blocked the rest is where they live and how they register. They are in
`src/main/java/`, which compiles for the JVM only, and `VimExtensionRegistrar`
hangs off an IntelliJ extension point. Measured against `vim-engine` rather than
against `com.intellij` - which is the measure that matters, because IdeaVim's own
IntelliJ bridge lives in `newapi`, `helper` and `listener` and does not say
`com.intellij` - most of what is left is a single package away, and that package
is always `newapi`: a cast to `IjVimEditor` or `IjVimCaret` for something the
engine can now do itself. Nothing is left in that group.

**"Imports `newapi`" measures the import, not the debt.** `textobj-user` (489
lines), `targets` (808), `abolish` (786) and `textobj-indent` (279) were all on
that list, and none of them cost more than a handful of lines. The middle two cost
exactly one:
`(caret as IjVimCaret).caret.moveToInlayAwareOffset(...)`, where
`moveToInlayAwareOffset` is a member of the engine's own `VimCaret` and the cast
reached IntelliJ's `Caret` to call what the engine already offered. `abolish` cost
three - `editor.ij` twice and `VimPlugin.getVariableService()`, which is
`injector.variableService` - and `textobj-indent` cost two reads and a loop, where
it walked the carets through IntelliJ's `CaretModel.runForEachCaret` and wrapped
each one back into an `IjVimCaret` to hand to engine code. Open the file before
believing the estimate.

The inverse also holds, and it is the half that actually costs time. `yankring`
imports no IntelliJ at all and still did not compile for JS, because
`Math.floorMod` is `java.lang.Math` and needs no import. `argtextobj` was the same
story four times over: `Character.isWhitespace`, `Character.isJavaIdentifierPart`,
`Character.isJavaIdentifierStart` and six `assert` calls - `kotlin.assert` is
JVM-only - none of which appear in an import list. An import list overstates what
is IntelliJ-shaped and understates what is JVM-shaped.

**That held a third time, on the way out.** Moving the engine's own tests out of
`src/test` before deleting the plugin, `ParserTest` looked portable and used
`Integer.toBinaryString` and `Integer.parseInt` - `java.lang`, with no import to
give it away, so it compiled for the JVM and not for JS. In the other direction
`CommandParserTest` had four `com.intellij` references in thirty tests and is not
portable at all: it uses `doTest` and `c` off `VimTestCase`, which carry no
`com.intellij` import either. Both were found by compiling, not by reading.

The engine already answers most of those: `isVimWhitespace`, `isIdentifierPart` and
now `isIdentifierStart` in `helper/Characters.kt` are the JDK's own rules spelled
out for both targets, and `StrictMode.assert` is IdeaVim's idiom for an internal
invariant. Look there before writing an approximation.

### How an extension reaches VS Code

This section used to say the host half was missing and that writing it was "the
next piece of work, and the gate for all eight". It was written, and everything
below it is now description rather than plan.

An extension is a function annotated `@VimPlugin`, written against
`com.intellij.vim.api.VimInitApi`, found by the KSP `ExtensionsProcessor` and
emitted as JSON. `VsCodeJsonExtensionProvider` reads it, `VsCodeExtensionLoader`
loads it, `VsCodeExtensionRegistrator` is what `:Plug` calls, and
`VsCodeExtensions.BUNDLED` lists the twenty-two that ship. The engine's own
machinery - `ExtensionHandler.kt`, `ExtensionLoader.kt`, `ExtensionBean.kt`,
`JsonExtensionProvider.kt` - is what both ends were built on.

Three ways in, and a config may use any of them: `:Plug 'tpope/vim-surround'`
resolves through `VsCodeExtensionRegistrator.ALIASES`, `:packadd` reaches
`enableExtension` directly, and `set surround` sets a toggle option registered
per extension by `registerExtensionOptions`. All three have been broken at some
point and each break was silent, because a config runs with `indicateErrors =
false`. If an extension is not turning on, check that it can be reached all three
ways before looking at the extension itself.

`textobj-user` is the one that needs a real teardown: it registers Vimscript
*function handlers*, and the loader removes mappings and listeners by owner but
knows nothing about functions. `VsCodeExtensions.TEARDOWN` is where anything else
that registers by name will need an entry.

**The `.` mark was set past the change rather than at it**, and the shape of that bug is worth
keeping. `replaceText` ended with
`setMark(caret, LAST_CHANGE_MARK, newEnd)` where `newEnd` is `start + str.length` - correct for
the `]` end of a range, which is exclusive, and wrong for a position. `rA` on the first character
left `.` at offset 1.

It survived because the two hosts reach the change list by different routes. IntelliJ feeds its
own from the platform's `RecentPlacesListener`, so IdeaVim's `g;` never consulted this mark and
none of IdeaVim's tests covered it; a host with no such listener gets Vim's own definition -
the change list is where `.` has been - and inherits the error once per change. **A bug can sit
in shared code for as long as only one host's path reaches it**, which is an argument for the
replayed corpus rather than against the engine.

## Packages

**New code goes in `com.github.neshkeev.vimperor`.** So does anything this fork
authored, which is what the `Copyright 2026 Nikita Eshkeev` header marks;
inherited files keep `com.maddyhome.idea.vim` along with their own header. That
rule is now applied everywhere it can be seen: 213 fork-authored files, and every
one of them has its path equal to its package.

The eighty-two that moved in the last pass came in two shapes, and the second was
free. Sixty-six were in `com/maddyhome/idea/vim/` and moved. The other sixteen -
`diff`, `directory`, `highlight`, `match`, `message`, `path`, `profile`,
`redirect`, `script`, `sign`, `tags`, `tutor` - were **already** in
`com/github/neshkeev/vimperor/` on disk while declaring `com.maddyhome.idea.vim`,
which Kotlin permits and nothing had noticed.

**Twelve packages moved whole; ten split**, and a split package is the part that
costs. `com.maddyhome.idea.vim.vimscript.model.commands` holds 136 files of which
20 are this fork's, so a same-package reference that needed no import before needs
one now - in both directions, since a moved file also loses its free access to
what stayed. That is 270 imports, and it is why this is a mechanical pass and not
a `sed`.

**`kspKotlinJvm --rerun-tasks` is the check that matters.** The `@ExCommand` and
`@VimscriptFunction` registries name every class by its full package, they are
committed under `vim-engine/src/jvmMain/resources/ksp-generated/`, and KSP writes
them into the source tree rather than into `build/`. Regenerate and compare
byte-for-byte rather than trusting the rewrite - and confirm the mixed state is
what you meant: `engine_ex_commands.json` now names 60 classes under the new root
and 341 under the old.

`src/test` is left alone. It is IdeaVim's tests as IdeaVim wrote them, compiled by
nothing and read as text, and it names engine classes in strings no compiler
checks.

### The header is not the same thing as the authorship

**146 files carry `Copyright 2003-2026 The IdeaVim authors` and IdeaVim never had
them.** They were written here and inherited the header by copy-paste. The
filename appears nowhere in `upstream/master` under any name, which is the only
reliable test - comparing *paths* against upstream says nothing, because this fork
moved the engine from `src/main` to `src/commonMain` and so every path differs.

The clearest case is `com.maddyhome.idea.vim.host`: 43 files, the whole headless
host and its tests, none of which exist upstream - `upstream/master` has no
`vim-engine/src/commonTest` at all. Thirty-one of them carry this fork's header
and have moved; the twelve that hold `HeadlessInjector`, `TestVimEditor` and
`TestVimCaret` do not, and stayed. So a package IdeaVim never wrote is now split
down the middle on the strength of which header got pasted into which file, and
the other large buckets are `helper` (32) and 29 more ex commands.

Nothing is wrong with the code. But **the header is the criterion and on those
files the header is wrong**, so before moving any of them, fix the notice - that
is a decision about a copyright statement, not a refactor.

## Quick Reference

**Java 21 is required; the build refuses anything else:**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

```bash
# Everything, both modules and both of the engine's targets. Takes about 25 seconds.
./gradlew test --console=plain

# The extension alone: its tests, the fixture replay, the stub-host smoke test,
# and both API guards
./gradlew :vscode-extension:check --console=plain

# The engine alone, one target at a time
./gradlew :vim-engine:jvmTest --console=plain
./gradlew :vim-engine:jsNodeTest --console=plain

# One class, on the JVM
./gradlew :vim-engine:jvmTest --tests "VimPathExpansionTest" --console=plain

# The extension, built into dist/ - what `--extensionDevelopmentPath` loads
./gradlew :vscode-extension:assembleExtension

# ...and as a .vsix. See vscode-extension/PUBLISHING.md
./gradlew :vscode-extension:packageExtension
```

Use `--console=plain` for gradle.

**Two traps.** `jsNodeTest` accepts no `--tests` - it runs every JS test - and a
Kotlin/JS test's `println` never reaches the console, so read
`vscode-extension/build/test-results/jsNodeTest/*.xml` instead. The fixture
replay writes `vscode-extension/build/fixture-failures.txt` on every run, with
the current list and a count of what was refused and why; regenerating the
baseline is a copy from there rather than a transcription out of a failure.

**`./gradlew build` does not work**, and did not before the plugin was deleted
either: `:api:compileCommonMainKotlinMetadata` fails for want of a stdlib, which
`kotlin.stdlib.default.dependency=false` withholds - it is off because the engine
supplies its own collection shims. Use `test` and `check`, which is what
everything above does.

See `vscode-extension/DEVELOPMENT.md` for the port's architecture.

## Notes

- Use `<Action>` in mappings, not `:action`
- Config file: Vimperor's own is `~/.vimperorrc`; it falls back to `~/.ideavimrc`, then Vim's
  `~/.vimrc`. Three families in that order, XDG and `_name` included, a whole family before the
  next one starts. Host-local, in `NodeFileSystem.findVimRc`
- A config runs with `indicateErrors = false`, so a line it cannot execute fails *silently*.
  That is IdeaVim's behaviour and why startup names the file it loaded
- Goal: match Vim's functionality and architecture
- `commonMain` cannot use JVM APIs (`String.format`, `Character`, `Integer`,
  `java.*`, reflection). They compile for the JVM target and break the JS one, and
  most of them need no import, so only compiling for JS will say so.
- Moving or adding an `@ExCommand` or `@VimscriptFunction` class needs
  `:vim-engine:kspKotlinJvm` re-run and the generated JSON committed, or it is
  silently unregistered - the registries name every class by its full package.

## Issue tracking

There isn't one. `VIM-XXXX` tickets belong to IdeaVim's own issue tracker and are
not this fork's to close; no GitHub issues have been filed on `neshkeev/vimperor`. Bugs are
recorded in commit bodies, in comments at the code, and in
`vscode-extension/src/jsTest/fixtures/known-fixture-failures.txt`.

## Commit messages

A subject that says what changed, in plain words. **No ticket prefix.** The body
carries the reasoning - what was wrong, what Vim does, what was measured. See the
`git-workflow` skill.

## Automation

Every workflow inherited from IdeaVim was **deleted**, not disabled - twenty-eight
of them, in commit `146db64f5`. They tested IntelliJ versions this fork does not
track, closed tickets in a tracker that is not ours, and published documentation
to a site that is not ours. `git show 146db64f5` if one is ever wanted back.

There is one live workflow, written for this fork:
`.github/workflows/publish-vimperor.yml`, which packages and publishes the VS
Code extension on a `vimperor-v*` tag. See `vscode-extension/PUBLISHING.md`.
