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
theme you use, and `'ignorecase'` and `'smartcase'` both apply. `'incsearch'` does not: the preview
needs the pattern as typed so far, which arrives on the command line rather than through the search
group, and a preview that lags the typing by a keystroke is worse than none.

The output panel (`:registers`, `:marks`, `:!` output), the system clipboard and IdeaVim's bundled
extensions are not wired up; each names itself if reached.

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

## Why Kotlin and not TypeScript

Kotlin/JS strips everything not reachable from an exported root. A TypeScript extension would have
to reach the engine through a hand-written `@JsExport` facade, and that facade fails silently - the
engine's own JS library once compiled to a 561-byte shell exporting nothing while every test passed.
Compiled together with the engine, the extension *is* the reachable root.

The engine's `public` API still bounds what this module can call: `internal` declarations are
module-scoped, so anything the extension needs has to be public - a real constraint, but a
type-checked one that the compiler enforces rather than a hand-maintained list that rots.
