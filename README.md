<img src="vscode-extension/icon.png" width="80" height="80" alt="icon" align="left"/>

Vimperor
===

**Vim for VS Code, powered by IdeaVim's engine.**

A hard fork of [IdeaVim](https://github.com/JetBrains/ideavim/blob/master/README.md), whose README describes the IntelliJ plugin this repository still builds.

What this is
------------

JetBrains' `vim-engine` — the Vim implementation behind IdeaVim, with its Vimscript
parser, its regex engine and its test suite — compiled to JavaScript and loaded as
a VS Code extension.

That is the whole of what makes it different from the extensions already in the
marketplace. Those are either a Vim written from scratch in TypeScript, or a real
Neovim running as a subprocess. This is neither: it is a mature Vim implementation
running in the extension host with nothing between it and the editor.

It reads your `~/.ideavimrc`. That is the config this engine has always read, and
the one its users already have.

Status
------

**Not released.** Version 0.0.1, not on the marketplace, and there is no packaging
step yet. It does run in a real VS Code window, and has since the port's first
keystroke landed.

What works today: motions, operators and text objects; counts and registers; the
`:` and `/` prompts with history, so `:s`, ranges and search are all reachable;
Visual mode in all three kinds including blockwise; marks, macros and digraphs;
`:g`/`:v`; `:!cmd` and `:%!sort`; `:ls` and `:buffer`; `:autocmd`; `'incsearch'`
and `'hlsearch'`. Insert mode steps aside for the suggest widget and for Copilot's
ghost text, so Tab accepts a suggestion when one is showing and is Vim's otherwise.

Vim's own tutor is built in — `:vimtutor`, `:tutor`, `:vimperortutor`, or
"Vimperor: Open Vim Tutor" in the Command Palette.

What does not: anything built on `getchar()`, which blocks on a modal event loop
until a key arrives. IdeaVim's bundled extensions — surround, commentary, easymotion
— are built on it, and JavaScript has one thread and no way to stop it.

[`vscode-extension/README.md`](vscode-extension/README.md) is the real account: what
works, how it is tested, and what only a real window found.

Running it
----------

Java 21 is required; the build refuses anything else.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :vscode-extension:jsProductionExecutableCompileSync
code --extensionDevelopmentPath="$PWD/vscode-extension"
```

`package.json` points `main` straight at the build output, so there is no packaging
step: build, then reload the extension host window. Type in any editor and Vim's
mode appears in the status bar.

The extension takes over VS Code's `type` command, which is the only way for an
extension to see ordinary typing. That also means **no other Vim extension can be
enabled at the same time** — `type` has one owner.

Layout
------

| Path                | What it is                        | Compiles to       |
|---------------------|-----------------------------------|-------------------|
| `vim-engine/`       | The Vim engine, host-independent  | JVM **and** JS    |
| `vscode-extension/` | The Vimperor VS Code extension    | JS (Kotlin/JS IR) |
| `src/main/java/`    | IdeaVim, the IntelliJ plugin      | JVM               |

`vim-engine` is Kotlin Multiplatform: the engine lives in `src/commonMain/kotlin`,
with `jvmMain` and `jsMain` for what each platform needs. A change to `commonMain`
changes both hosts.

The IntelliJ plugin is kept rather than deleted. It is the second host that keeps
the engine honest, and its test suite is the regression net for every engine change.

Testing
-------

```bash
# The extension: its tests, the stub-host smoke test, and both API guards
./gradlew :vscode-extension:test --console=plain

# The engine and the IntelliJ plugin
./gradlew test -x :tests:property-tests:test -x :tests:long-running-tests:test --console=plain

# One class — note the leading colon; bare `test --tests` is rejected
./gradlew :test --tests "SearchGroupTest" --console=plain
```

Two things are checked that a test suite cannot check on its own. The `external`
declarations for the VS Code API are compiled against nothing — the extension host
injects the real API at runtime — so `checkVsCodeApiDeclarations` compares all 106
of them, name and kind, against `@types/vscode`. Every command id the extension
sends is compared against a real window by `checkVsCodeCommandIds`.

Beyond its own 402 tests, the extension replays IdeaVim's test fixtures against the
VS Code host: **1,034 of 1,049 pass**. The fifteen that do not are listed, with an
explanation of each, in
[`known-fixture-failures.txt`](vscode-extension/src/jsTest/fixtures/known-fixture-failures.txt).

Why Kotlin and not TypeScript
-----------------------------

Rewriting the engine would mean rewriting the Vimscript parser, the regex engine and
two decades of accumulated corner cases, then discovering the corner cases again one
bug report at a time. Compiling it keeps all of that, and keeps the fixtures that
prove it — which is how a fresh host gets a test suite worth the name on its first
day.

Authors
-------

See [AUTHORS.md](AUTHORS.md).

Most of the engine is the work of IdeaVim's contributors, and the fork does not
change that.

License
-------

MIT. Two copyright notices under one grant — the IdeaVim authors for the work up to
the fork, and this fork's author for what has been written since. Both are in
[LICENSE.txt](LICENSE.txt).

Third-party components and licenses are listed in
[ThirdPartyLicenses.md](ThirdPartyLicenses.md).

All IdeaVim releases before 2.0.0 were licensed under GPL-2.0 or later. The last
commit before the switch to MIT is 05852b07c6090ad40fde7d3cafe0b074604f7ac5;
[the discussion](https://github.com/JetBrains/ideavim/discussions/543) has the
background.
