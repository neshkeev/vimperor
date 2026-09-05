<img src="vscode-extension/icon.png" width="80" height="80" alt="icon" align="left"/>

Vimperor
===

**Vim for VS Code, powered by IdeaVim's engine.**

A hard fork of [IdeaVim](https://github.com/JetBrains/ideavim/blob/master/README.md), whose README describes the IntelliJ plugin this repository still builds.

What this is
------------

IdeaVim's `vim-engine` — the Vim implementation behind that plugin, with its
Vimscript parser, its regex engine and its test suite — compiled to JavaScript and
loaded as a VS Code extension.

That is the whole of what makes it different from the extensions already in the
marketplace. Those are either a Vim written from scratch in TypeScript, or a real
Neovim running as a subprocess. This is neither: it is a mature Vim implementation
running in the extension host with nothing between it and the editor.

Configuring it
--------------

Vimperor has **its own config file, `~/.vimperorrc`** — and reads the two you may
already have if it is not there:

| file | for |
|---|---|
| **`~/.vimperorrc`** | **Vimperor's own. Read first, wherever it is** |
| `~/.ideavimrc` | shared with IdeaVim in IntelliJ, unchanged |
| `~/.vimrc` | Vim's own — nothing to copy, nothing to rename |

Each is also looked for as `_name` and in its XDG directory, and Vim's family adds
`~/.vim/vimrc`; a whole family is searched before the next one starts, so a
`vimperorrc` anywhere beats an `ideavimrc` anywhere. **The name is the intent.**

Only the first file found is read — nothing is merged. That is what makes
`~/.vimperorrc` worth having: it is where anything VS Code-specific goes without
leaking into IntelliJ, and it can pull in the rest explicitly.

```vim
" ~/.vimperorrc
source ~/.ideavimrc
nnoremap <leader>f <Action>(workbench.action.quickOpen)
```

A borrowed `~/.vimrc` is read as-is, but expect part of it not to apply: there are
no Vim plugins here, so a plugin manager does nothing, and neither do autocommands,
`syntax` or `colorscheme`. Those lines are stepped over and the rest takes effect.
Startup names the file it loaded in the Vimperor output channel, and says so when
that file is Vim's own.

Status
------

**Not released.** Version 0.0.1 and not on the marketplace yet, though it packages
and publishes: `./gradlew :vscode-extension:packageExtension` builds the `.vsix`, and
[`vscode-extension/PUBLISHING.md`](vscode-extension/PUBLISHING.md) has the rest. It
runs in a real VS Code window, and has since the port's first keystroke landed.

What works today: motions, operators and text objects; counts and registers; the
`:` and `/` prompts with history, so `:s`, ranges and search are all reachable;
Visual mode in all three kinds including blockwise; marks, macros and digraphs;
`:g`/`:v`; `:!cmd` and `:%!sort`; `:ls` and `:buffer`; `:autocmd`; `'incsearch'`
and `'hlsearch'`. Insert mode steps aside for the suggest widget and for Copilot's
ghost text, so Tab accepts a suggestion when one is showing and is Vim's otherwise.

Vim's own tutor is built in — `:vimtutor`, `:tutor`, `:vimperortutor`, or
"Vimperor: Open Vim Tutor" in the Command Palette.

What does not: 25 of the 26 bundled extensions — surround, commentary, targets and
the rest. `ReplaceWithRegister` is ported and is the pattern for the others. Not
for the reason this file used to give. They are not built on `getchar()`;
exactly one of them asks for a key. They are unported because they live in the
IntelliJ plugin's module and register through an IntelliJ extension point. One is
portable as it stands and four more are a single cast away from it.

[`vscode-extension/DEVELOPMENT.md`](vscode-extension/DEVELOPMENT.md) is the real account: what
works, how it is tested, and what only a real window found.

Running it
----------

Java 21 is required; the build refuses anything else.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :vscode-extension:assembleExtension
code --extensionDevelopmentPath="$PWD/vscode-extension"
```

`main` points at `vscode-extension/dist/`, which `assembleExtension` fills from the
build output and which is exactly what the `.vsix` ships — so a development window
loads the same files a user would install. Build, then reload the extension host
window. Type in any editor and Vim's mode appears in the status bar.

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

The IntelliJ plugin is kept until the port is finished, and then it goes. It is not
kept for its features - nobody runs IdeaVim out of this repository - but for its
tests: 11,727 of them, plus the 1,049 fixtures the VS Code host mines out of
`src/test` and replays. That corpus is the largest outside check on the port, and
it has to outlive the plugin.

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
injects the real API at runtime — so `checkVsCodeApiDeclarations` compares all 112
of them, name and kind, against `@types/vscode`. Every command id the extension
sends is compared against a real window by `checkVsCodeCommandIds`.

Beyond its own 631 tests, the extension replays IdeaVim's test fixtures against the
VS Code host: **1,036 of 1,049 pass**. The thirteen that do not are listed, with an
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
