# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this repository is

A **hard fork of IdeaVim** being turned into **Vimperor**, a VS Code extension.
Three parts:

| Path                | What it is                        | Compiles to       |
|---------------------|-----------------------------------|-------------------|
| `vim-engine/`       | The Vim engine, host-independent  | JVM **and** JS    |
| `src/main/java/`    | The IntelliJ plugin (IdeaVim)     | JVM               |
| `vscode-extension/` | The Vimperor VS Code extension    | JS (Kotlin/JS IR) |

`vim-engine` is Kotlin Multiplatform: the engine is in `src/commonMain/kotlin`,
**not** `src/main/kotlin`. **A change to `commonMain` changes both hosts.**

There is no upstream to contribute to. `vim-engine` may be changed freely, and
should be when the bug is the engine's. The `upstream` remote is read-only.

## Where this is going

**Port IdeaVim to VS Code completely, then delete the IntelliJ plugin.** The
plugin is kept until then and only until then, so weigh every change against the
day it goes: anything written into `src/main/java/` is work that will be thrown
away, and anything that only the plugin can do is a gap in the port.

The plugin is not kept for its features - nobody runs IdeaVim out of this
repository. It is kept for its tests. 11,727 of them, plus the 1,047 fixtures the
VS Code host mines out of `src/test` and replays (1,044 pass). That corpus is the
largest outside check on the port and it has to survive the deletion, so
`src/test` is not an ordinary casualty of removing `src/main`.

What is still missing: 2 of the 24 bundled extensions, the in-tree keys NERDTree maps
that VS Code has no command for, 14 IntelliJ-only options,
3 of the replayed fixtures, and one `TODO` seam in `VsCodeInjector` -
`pluginActivator`, which nothing in the engine calls.

**Three, and two of them name an IntelliJ action.** Until recently it was twelve, and
nine of those were one bug about carets seen from eight angles plus one that was never
this host's bug at all - which is the second time a fixture turned out to be measuring
the harness. The remaining three are `ideajoin`, an `<Action>` mapping that runs
`EditorToggleCase`, and incsearch previewing an *ex command* - `:%s/dolor` shows its
match while it is still being typed, which needs the command line parsed for a range
and a pattern before it runs. Only the last is work this host can do.

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

**Fourteen, not twenty-four.** This file said 24 and the number counted the file
rather than the concept, exactly as "26 extensions" did: `IjOptions` declares 24 and
ten of them - `wrap`, `list`, `cursorline`, `relativenumber`, `textwidth`,
`breakindent`, `colorcolumn`, `bomb`, `fileencoding`, `fileformat` - are ordinary Vim
options this host already has. The fourteen that are left are the `idea*` family plus
`lookupkeys`, `trackactionids`, `visualdelay`, `closenotebooks`, `oldundo` and
`unifyjumps`, and most describe IDE behaviour VS Code has no analogue for.

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

**Twenty are ported and bundled** - `ReplaceWithRegister`, `vim-paragraph-motion`,
`textobj-entire`, `mini-ai`, `CamelCaseMotion`, `indentwise`, `textobj-user`,
`targets`, `abolish`, `textobj-indent`, `argtextobj`, `commentary`,
`highlightedyank`, `exchange`, `sneak`, `surround`, `multiple-cursors`, `yankring`,
`functextobj`, `classtextobj`, `NERDTree` and `youcompleteme` - which is every one
whose substance this host can carry. Each lives in
`vim-engine/src/commonMain/.../extension/<name>/` as a `@VimPlugin` function, the
VS Code host lists it in `VsCodeExtensions.BUNDLED`, and the plugin keeps a
two-line `VimExtension` adapter that calls the same function so IntelliJ is
unaffected. The adapter goes when the plugin does.

The two that are left - `matchit` and `VimEverywhere` - are not waiting on a seam.
`matchit` wants to know what a *token* is, so that `%` can jump between `if` and
`endif` and between HTML tags, and the one thing VS Code tells an extension about a
file's structure is where its symbols are - functions and classes, nothing smaller.

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

The engine already answers most of those: `isVimWhitespace`, `isIdentifierPart` and
now `isIdentifierStart` in `helper/Characters.kt` are the JDK's own rules spelled
out for both targets, and `StrictMode.assert` is IdeaVim's idiom for an internal
invariant. Look there before writing an approximation.

### How an extension is meant to reach VS Code

There are two extension systems in this repository, and only one of them is worth
porting to.

The old one is the `VimExtension` interface registered through the IntelliJ
extension point `IdeaVIM.vimExtension`. Eighteen of the twenty-six still use it.

The new one is the **thin API**: a function annotated `@VimPlugin`, written
against `com.intellij.vim.api.VimInitApi`, found by the KSP `ExtensionsProcessor`
and emitted as JSON for a host to read. Eight extensions have already been
migrated to it - `commentary`, `replacewithregister`, `yankring`,
`camelcasemotion`, `paragraphmotion`, `textobjentire`, `textobjuser`, `miniai` -
and **the `api` module is already Kotlin Multiplatform with a JS target**. So the
thin API is the route: nothing about it is IntelliJ-shaped.

What is missing is the host half. IntelliJ has `IjPluginExtensionsScanner` (68
lines, reads the generated JSON) and `IjJsonExtensionProvider` (228 lines). The VS
Code host has neither, and `VsCodeInjectorBase` says so:
`TODO("the VS Code host does not provide extensionRegistrator yet")`. It already
imports `ExtensionLoader` and `JsonExtensionProvider`, so the shape is anticipated.

**That provider is the next piece of work, and it is the gate for all eight.**
Until it exists no extension can register in VS Code however portable it is;
once it does, the question for each extension becomes only whether its own
imports are engine-only.

Most of the machinery to fix this is already in `vim-engine`:
`extension/ExtensionHandler.kt`, `ExtensionLoader.kt`, `ExtensionBean.kt` and
`JsonExtensionProvider.kt` - registration driven by JSON rather than by an
IntelliJ extension point - and `VimExtensionRegistrator`, which `:Plug` already
calls. `VimExtensionHandler` in the plugin is a thin adapter over the engine's
`ExtensionHandler`: it converts a `VimEditor` to an IntelliJ `Editor` and does
nothing else. So porting one is moving it to `commonMain`, writing it against
`ExtensionHandler` instead of the adapter, and registering it through the JSON
provider - not rewriting it.

## Packages

**New code goes in `com.github.neshkeev.vimperor`.** So does anything this fork
authored, which is what the `Copyright 2026 Nikita Eshkeev` header marks;
inherited files keep `com.maddyhome.idea.vim` along with their own header.

The VS Code extension and the fourteen engine packages that are wholly this
fork's - `highlight`, `sign`, `redirect`, `path`, `diff`, `directory`, `match`,
`message`, `profile`, `script`, `tags`, `tutor`, and the buffer and path function
handlers - have moved. Sub-paths were preserved: only the prefix changed.

**Sixty-four files have not moved, on purpose.** They sit in packages that are
mostly inherited - 20 ex commands among 116, one file in `api` among 110, the
`host` test package, four function-handler packages - and moving them would split
those packages in two for as long as the plugin lives. The engine is easier to
rename in one pass once it belongs to this fork outright, which is after the
plugin goes. Until then, leave them.

## Quick Reference

**Java 21 is required; the build refuses anything else:**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

```bash
# One class or package - the usual thing to want
./gradlew :test --tests "SearchGroupTest" --console=plain

# The standard suite: both hosts, since `test` matches by task name across projects
./gradlew test -x :tests:property-tests:test -x :tests:long-running-tests:test --console=plain

# The extension alone: its tests, the stub-host smoke test, and both API guards
./gradlew :vscode-extension:test --console=plain

# The plugin, in a dev IDE
./gradlew runIde

# The extension, as a .vsix. See vscode-extension/PUBLISHING.md
./gradlew :vscode-extension:packageExtension
```

Avoid running all tests - it takes too long. Prefer a specific test.

Use `--console=plain` for gradle.

**Two traps in those commands.** `--tests` needs the leading colon: bare
`./gradlew test --tests "X"` fails with `Unknown command-line option '--tests'`,
because `test` matches by name across projects and `:vim-engine:test` and
`:vscode-extension:test` are plain aggregator tasks. `:test` is the root project's
real test task. And `jsNodeTest` accepts no `--tests` at all - it runs every JS
test - while a Kotlin/JS test's `println` never reaches the console, so read
`vscode-extension/build/test-results/jsNodeTest/*.xml` instead.

See CONTRIBUTING.md for the plugin's architecture and
`vscode-extension/DEVELOPMENT.md` for the port's.

## Notes

- Property tests can be flaky - check whether a failure relates to your change
- Use `<Action>` in mappings, not `:action`
- Config file: Vimperor's own is `~/.vimperorrc`; it falls back to `~/.ideavimrc`, then Vim's
  `~/.vimrc`. Three families in that order, XDG and `_name` included, a whole family before the
  next one starts. Host-local, in `NodeFileSystem.findVimRc`; the IntelliJ plugin reads
  `~/.ideavimrc` only
- A config runs with `indicateErrors = false`, so a line it cannot execute fails *silently*.
  That is IdeaVim's behaviour and why startup names the file it loaded
- Goal: match Vim's functionality and architecture
- `commonMain` cannot use JVM APIs (`String.format`, `Character`, `java.*`,
  reflection). They compile for the JVM target and break the JS one.
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
