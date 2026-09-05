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
repository. It is kept for its tests. 11,727 of them, plus the 1,049 fixtures the
VS Code host mines out of `src/test` and replays (1,036 pass). That corpus is the
largest outside check on the port and it has to survive the deletion, so
`src/test` is not an ordinary casualty of removing `src/main`.

What is still missing: 14 of the 26 bundled extensions, 24 IntelliJ-only options,
13 of the replayed fixtures, and three `TODO` seams in `VsCodeInjector`.

Twelve are ported - `ReplaceWithRegister`, `vim-paragraph-motion`,
`textobj-entire`, `mini-ai`, `CamelCaseMotion`, `indentwise`, `textobj-user`,
`targets`, `abolish`, `textobj-indent`, `argtextobj` and `commentary` - and they
are the pattern for the rest. A thirteenth, `yankring`, is in the engine and
deliberately *not* bundled: see below. Each lives in
`vim-engine/src/commonMain/.../extension/<name>/` as a `@VimPlugin` function, the
VS Code host lists it in `VsCodeExtensions.BUNDLED`, and the plugin keeps a
two-line `VimExtension` adapter that calls the same function so IntelliJ is
unaffected. The adapter goes when the plugin does.

`textobj-user` is the one exception to "two-line", and it says something about the
engine rather than about that extension: it registers Vimscript *function
handlers*, and the loader's teardown removes mappings and listeners by owner but
knows nothing about functions. So its adapter keeps a real `dispose`, and the VS
Code host has `VsCodeExtensions.TEARDOWN` for the same reason. Anything else that
registers by name will need an entry there.

**`yankring` is in `vim-engine` and is not in `VsCodeExtensions.BUNDLED`, on
purpose.** `<C-P>` after a paste works by undoing the paste and re-pasting an older
entry, and that needs `u` to have finished by the next statement. IntelliJ's undo is
synchronous; VS Code's is a command the host dispatches and cannot wait for -
`VsCodeInjector.undo` returns `true` immediately and holds the user's *keys* until it
lands, which is no help inside one mapping. The re-paste would land on text the undo
had not removed yet. Enabling it there needs a seam the engine does not have: a way
for an extension to continue once the host's document has caught up. Everything else
in the extension asks nothing of the host.

**`commentary` is the pattern for the ones that are left**, and a different one:
its body was *not* engine-only, and the answer was not to make it so. Toggling a
comment needs to know that a comment is `//` here and `#` there, which is a fact
about the file that only a host has - so the extension asks
`injector.commentService` to toggle a range and the host decides what that means.
Both hosts already had the capability and neither exposed it. Most of what is left
- `highlightedyank`, `sneak`, `exchange` and their editor markup - is the same
shape: a capability to name, not code to move.

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
at parity - 401 in the engine, two IntelliJ-only.

**The extensions are not blocked on `getchar()`.** That was the standing
explanation and it is wrong: of the 26, exactly one - `surround` - asks for a key
at all. What blocks them is where they live and how they register. They are in
`src/main/java/`, which compiles for the JVM only, and `VimExtensionRegistrar`
hangs off an IntelliJ extension point. Measured against `vim-engine` rather than
against `com.intellij` - which is the measure that matters, because IdeaVim's own
IntelliJ bridge lives in `newapi`, `helper` and `listener` and does not say
`com.intellij` - most of what is left is a single package away, and that package
is always `newapi`: a cast to `IjVimEditor` or `IjVimCaret` for something the
engine can now do itself. `functextobj` and `classtextobj` are what is left in
that group, and both also want PSI, so neither is a move.

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
