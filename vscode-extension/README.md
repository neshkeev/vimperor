# IdeaVim for VS Code

The IdeaVim engine, compiled to JavaScript and loaded as a VS Code extension.

## Running it

```bash
./gradlew :vscode-extension:jsProductionExecutableCompileSync
code --extensionDevelopmentPath="$PWD/vscode-extension"
```

`package.json` points `main` directly at the build output, so there is no packaging step: build,
then reload the extension host window. The extension writes to an *IdeaVim* output channel on
activation, and contributes **IdeaVim: Check Vim Pattern** to the command palette.

## Checking it

```bash
./gradlew :vscode-extension:runInStubHost
```

This loads the built bundle in Node with `vscode` stubbed and asks the engine to compile Vim
patterns, which is the only check that distinguishes an engine that is live inside the extension
from one that is merely bundled beside it. `./gradlew test` runs it too.

## Why Kotlin and not TypeScript

Kotlin/JS strips everything not reachable from an exported root. A TypeScript extension would have
to reach the engine through a hand-written `@JsExport` facade, and that facade fails silently - the
engine's own JS library once compiled to a 561-byte shell exporting nothing while every test passed.
Compiled together with the engine, the extension *is* the reachable root.

The engine's `public` API still bounds what this module can call: `internal` declarations are
module-scoped, so anything the extension needs has to be public - a real constraint, but a
type-checked one that the compiler enforces rather than a hand-maintained list that rots.
