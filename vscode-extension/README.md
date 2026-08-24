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
`o`/`O`, counts, registers, `u` and `<C-R>`, and `:s` when driven directly. The command-line prompt,
visual mode, search highlighting and the system clipboard are not wired up; each names itself if
reached.

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
