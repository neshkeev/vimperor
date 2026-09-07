This repository is a hard fork of IdeaVim, an open source project created by 130+ contributors. It
builds Vimperor, a VS Code extension, out of the engine it inherited; the IntelliJ plugin that came
with that engine has been deleted. Would you like to make it better? That's wonderful!

This page is here to help you start contributing.

## Before you begin

- The project is primarily written in Kotlin with a few Java files. When contributing to the project, use Kotlin unless
you’re working in areas where Java is explicitly used.

- If you come across some IntelliJ Platform code, these links may prove helpful:

    * [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html)
    * [IntelliJ architectural overview](https://plugins.jetbrains.com/docs/intellij/fundamentals.html)
    * [IntelliJ Platform community space](https://platform.jetbrains.com/)

- Having any difficulties? Ask in [this fork's discussions](https://github.com/neshkeev/vimperor/discussions).
Please do not take questions about this fork to the upstream project - it is not theirs to answer.

OK, ready to do some coding?

## Yes, I'm ready for some coding

* Fork the repository and clone it to the local machine.
* Open the project with IntelliJ IDEA.

Yoo hoo! You’re all set to begin contributing.

The run configurations that came with IdeaVim - `Start IJ with IdeaVim`, `Start CLion`, `Start
Rider` and the rest - started a dev IDE with the plugin installed. There is no plugin, so they are
gone. Vimperor is run the way any VS Code extension is:

```bash
./gradlew :vscode-extension:assembleExtension
code --extensionDevelopmentPath=$PWD/vscode-extension --disable-extensions
```

`--disable-extensions` matters more than it looks: if Vimperor is also installed from the
marketplace, the installed copy shadows the one being developed and you debug the wrong build.

And here are the useful gradle commands:

* `./gradlew test` — everything, both modules and both of the engine's targets. About 25 seconds.
* `./gradlew :vscode-extension:check` — the extension alone: its tests, the fixture replay, the
  stub-host smoke test, and the two guards that check command ids and API declarations against the
  real VS Code.
* `./gradlew :vim-engine:jvmTest --tests "SomeTest"` — one class. `jsNodeTest` takes no `--tests`.
* `./gradlew :vscode-extension:assembleExtension` — build into `dist/`, which is what
  `--extensionDevelopmentPath` loads.
* `./gradlew :vscode-extension:packageExtension` — a `.vsix`. See `vscode-extension/PUBLISHING.md`.

`./gradlew build` does not work and did not before the plugin was deleted either; use `test` and
`check`.

## Warmup

 - Pick a few relatively simple tasks that are tagged with 
[#patch_welcome](https://youtrack.jetbrains.com/issues/VIM?q=%23patch_welcome%20%23Unresolved%20sort%20by:%20votes%20)
 in the issue tracker.
 - Read the javadoc for the `@VimBehaviorDiffers` annotation in the source code and fix the corresponding functionality.
 - Implement one of the requested [#vim plugin](https://youtrack.jetbrains.com/issues/VIM?q=%23Unresolved%20tag:%20%7Bvim%20plugin%7D%20sort%20by:%20votes%20)s.

> :small_orange_diamond: You may leave a comment in the YouTrack ticket or open a draft PR if you’d like early feedback
> or want to let maintainers know you’ve started working on an issue. Otherwise, simply open a PR.

## Where to start in the codebase

If you are looking for:

- Vim commands (`w`, `<C-O>`, `p`, etc.):
    - Any particular command:
      - [In the engine](vim-engine/src/main/resources/ksp-generated/engine_commands.json)
      - Host commands, which the extension registers itself: `VsCodeCommandProvider`
    - How commands are executed in common: `EditorActionHandlerBase`.
    - Key mapping: `KeyHandler.handleKey()`.

- Ex commands (`:set`, `:s`, `:nohlsearch`):
    - Any particular command:
        - [In the engine](vim-engine/src/main/resources/ksp-generated/engine_ex_commands.json)
        - Host ex commands, such as `:actionlist`: `HostCommands.kt`
    - Vim script grammar: `Vimscript.g4`.
    - Vim script parsing: package `com.maddyhome.idea.vim.vimscript.parser`.
    - Vim script executor: `Executor`.

- Extensions:
    - Extensions handler: `VimExtensionHandler`.
    - Available extensions: package `com/maddyhome/idea/vim/extension`.

- Common features:
    - State machine. How every particular keystroke is parsed: `KeyHandler.handleKey()`.
    - Options (`incsearch`, `iskeyword`, `relativenumber`): `VimOptionGroup`, and
      `VsCodeOptions` for the ones this host adds or answers itself.
    - Where a keystroke enters the host: `VimHost.handle`.
    - Startup, and everything VS Code is told about: `Extension.kt`.


## Testing

Here are some guides for testing:

1. Read the javadoc for the `@VimBehaviorDiffers` annotation in the source code.

2. Please avoid senseless text like "dhjkwaldjwa", "asdasdasd", "123 123 123 123", etc. Use a few lines of code or
the following template:
```text
Lorem Ipsum

Lorem ipsum dolor sit amet,
consectetur adipiscing elit
Sed in orci mauris.
Cras id tellus in ex imperdiet egestas.
```

3. Don't forget to test your functionality with various corner cases:
   - **Position-based**: line start, line end, file start, file end, empty line, single character line
   - **Content-based**: whitespace-only lines, lines with trailing spaces, mixed tabs and spaces, Unicode characters, multi-byte characters (e.g., emoji, CJK)
   - **Selection-based**: multiple carets, visual mode (character/line/block), empty selection
   - **Motion-based**: dollar motion, count with motion (e.g., `3w`, `5j`), zero-width motions
   - **Buffer state**: empty file, single line file, very long lines, read-only files
   - **Boundaries**: word boundaries with punctuation, sentence/paragraph boundaries, matching brackets at extremes
   
##### The replayed fixtures
`src/test` holds IdeaVim's tests and is not compiled. The extension reads them as text and replays
the keys against the VS Code host: 2,417 of 2,423 pass, and the rest are listed in
`vscode-extension/src/test/fixtures/known-fixture-failures.txt`. Every run writes the current
list, and what it refused to harvest and why, to `vscode-extension/build/fixture-failures.txt`.

The corpus has grown four times over, every time by teaching the harness to read more of what was
already written rather than by writing new tests. If you want more coverage cheaply, look there
first.

IdeaVim's Neovim integration and its property-based tests went with the plugin: both were built on
the IntelliJ test fixtures.


## A common direction

We’re trying to make IdeaVim close to the original Vim both in terms of functionality and architecture.

- Vim motions can be [either inclusive, exclusive, or linewise](http://vimdoc.sourceforge.net/htmldoc/motion.html#inclusive).
In IdeaVim, you can use `MotionType` for that.
- Have you read the [interesting things](https://github.com/JetBrains/ideavim#some-facts-about-vim) about IdeaVim?
Do you remember how `dd`, `yy`, and other similar commands work? `DuplicableOperatorAction` will help you with that.
And we also translate it to `d_` and `y_`: `KeyHandler.mapOpCommand()`.
- All IdeaVim extensions use the same command names as the originals (e.g. `<Plug>(CommentMotion)`, `<Plug>ReplaceWithRegisterLine`),
so you can reuse your `.vimrc` settings. 
We also support proper command mappings (functions are mapped to `<Plug>...`), the operator function (`OperatorFunction`), and so on.
- Magic is supported as well. See `Magic`.


## One host, one engine

The engine is deliberately separate from the editor it runs in. `vim-engine` knows nothing about
VS Code; the host supplies its own implementations of the seams the engine declares. Upstream
split it this way so one engine could serve more than one editor, and that is exactly what made
this fork possible.

| Path                | What it is                        | Compiles to       |
|---------------------|-----------------------------------|-------------------|
| `vim-engine/`       | The Vim engine, host-independent  | JVM **and** JS    |
| `vscode-extension/` | The Vimperor VS Code extension    | JS (Kotlin/JS IR) |
| `src/test/`         | IdeaVim's tests, as replay data   | nothing           |

`vim-engine` is Kotlin Multiplatform, laid out the Maven way rather than KMP's: every Kotlin file
is under `src/main/kotlin` or `src/test/kotlin`, with `jvm/` and `js/` source roots nested inside
them for what only one platform needs. It has one host and still two **targets**: a change to
`src/main/kotlin` has to compile for JS as well as the JVM, and its tests run on both. That is what catches a `java.lang`
call in shared code, and such calls usually need no import, so nothing else would.

The IntelliJ plugin was deleted once the port no longer needed it. Its tests were the regression
net while the port was being built; the net now is the engine's own tests plus the fixtures the
VS Code host replays out of `src/test`, which is kept as data and never compiled.


-----

## Getting help, and reporting things

Everything below is about **this fork**. It has its own repository, and the upstream project neither
maintains it nor supports it, so please do not send questions about it there.

### Something on this page is unclear, wrong, or missing.

That is a bug in the documentation and worth reporting like any other. Open an issue, or a pull
request if you already know what it should say.

### I have found a bug.

[Open an issue](https://github.com/neshkeev/vimperor/issues). Turn on `vimperor.trace` in VS Code's
settings and include what the Vimperor output channel printed: most of what is left to get wrong
lives in the gap between the engine and the editor, and a trace shows that gap directly.

If the same bug reproduces in IdeaVim as it ships, it is an upstream bug rather than this fork's,
and reporting it there as well will get it fixed for more people.

### I want to know why some code is the way it is.

Three places, in order of how often they have the answer: the KDoc at the code, which in this
repository carries the reasoning rather than restating the signature; the commit that introduced it,
whose body says what was wrong and what was measured; and `git log upstream/master` for anything
older than the fork.

### Resources

* [`vscode-extension/DEVELOPMENT.md`](vscode-extension/DEVELOPMENT.md) - how the VS Code port is
  built and tested, and what only a real editor window ever found
* [`vscode-extension/PUBLISHING.md`](vscode-extension/PUBLISHING.md) - packaging and releasing the
  extension
* [`CLAUDE.md`](CLAUDE.md) - the short version of everything on this page
* [`known-fixture-failures.txt`](vscode-extension/src/test/fixtures/known-fixture-failures.txt) -
  every replayed fixture the VS Code host does not pass, with the reason for each
* [Changelog](CHANGES.md) and [contributors listing](AUTHORS.md), both inherited and both still
  the record of the work this fork is built on
