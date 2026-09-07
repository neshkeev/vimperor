<img src="vscode-extension/icon.png" width="80" height="80" alt="icon" align="left"/>

Vimperor
===

**Vim for VS Code, powered by IdeaVim's engine.**

A hard fork of [IdeaVim](https://github.com/JetBrains/ideavim/blob/master/README.md). This
repository built the IntelliJ plugin too, until the port no longer needed it; what is left is
the engine and the VS Code extension.

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

What does not: 2 of IdeaVim's 24 bundled extensions — matchit and VimEverywhere.
The other twenty-two are ported:
`ReplaceWithRegister`, `vim-paragraph-motion`,
`textobj-entire`, `mini-ai`, `CamelCaseMotion`, `indentwise`, `textobj-user`,
`targets`, `abolish`, `textobj-indent`, `argtextobj`, `commentary`,
`highlightedyank`, `exchange`, `sneak`, `surround`, `multiple-cursors`, `yankring`,
`functextobj`, `classtextobj`, `NERDTree` and `youcompleteme`.

The two that are missing are not waiting on a seam. `matchit` needs to know
what a *token* is, and the most VS Code will say about a file's structure is where its
functions and classes are. `VimEverywhere` labels every clickable thing in the IDE
window and clicks the one you type, and an extension cannot draw over VS Code's
workbench.

`NERDTree` is bundled but is half an extension. Its ex commands work; the keys it maps
*inside* the tree are declared in `package.json` rather than by the engine, because a
key pressed in VS Code's sidebar never reaches an extension. Seventeen of the thirty
have equivalents.

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
| `src/test/`         | IdeaVim's tests, as replay data   | nothing           |

`vim-engine` is Kotlin Multiplatform laid out the Maven way: every Kotlin file is
under `src/main/kotlin` or `src/test/kotlin`, and what only one platform needs sits
in a `jvm/` or `js/` source root nested inside them. It still compiles for both
targets and its tests run on both, which is how a JVM-ism in shared code gets
caught.

The IntelliJ plugin was deleted once the port no longer needed it. `src/test`
stayed and is not compiled: it holds IdeaVim's 11,727 tests, and the VS Code host
mines 2,423 replayed fixtures out of them as *text*. That corpus is the largest
outside check on the port, and it never needed to be code.

Testing
-------

```bash
# Everything, both modules and both of the engine's targets - about 25 seconds
./gradlew test --console=plain

# The extension: its tests, the fixture replay, the stub-host smoke test and both guards
./gradlew :vscode-extension:check --console=plain

# One class, on the JVM
./gradlew :vim-engine:jvmTest --tests "VimPathExpansionTest" --console=plain
```

Two things are checked that a test suite cannot check on its own. The `external`
declarations for the VS Code API are compiled against nothing — the extension host
injects the real API at runtime — so `checkVsCodeApiDeclarations` compares all 127
of them, name and kind, against `@types/vscode`. Every command id the extension
sends is compared against a real window by `checkVsCodeCommandIds`.

Beyond its own tests, the extension replays IdeaVim's test fixtures against the
VS Code host: **2,417 of 2,423 pass**. The six that do not each name an IntelliJ
action with no VS Code command behind it, and are listed in
[`known-fixture-failures.txt`](vscode-extension/src/test/fixtures/known-fixture-failures.txt).

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
