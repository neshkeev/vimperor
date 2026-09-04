This repository is a hard fork of IdeaVim, an open source project created by 130+ contributors, and
it builds two things from one engine: the IntelliJ plugin it inherited, and Vimperor, a VS Code
extension. Would you like to make it better? That's wonderful!

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
We've prepared some useful configurations for you:

- `Start IJ with IdeaVim`
- `IdeaVim tests`
- `IdeaVim full verification`
- `Platforms`
    - `Start CLion with IdeaVim`
    - `Start PyCharm with IdeaVim`
    - `Start Rider with IdeaVim`
    - `Start WebStorm with IdeaVim`

![Prepared configurations light](assets/contributing/configs-light.png#gh-light-mode-only)![Prepared configurations dark](assets/contributing/configs-dark.png#gh-dark-mode-only)

And here are useful gradle commands:

* `./gradlew runIde` — start the dev version of IntelliJ IDEA with IdeaVim installed. This is called by the `Start IJ with IdeaVim` configuration.
* `./gradlew runClion` — start the dev version of CLion with IdeaVim installed. This is called by the `Start CLion with IdeaVim` configuration.
* `./gradlew runPycharm` — start the dev version of PyCharm with IdeaVim installed. This is called by the `Start PyCharm with IdeaVim` configuration.
* `./gradlew runRider` — start the dev version of Rider with IdeaVim installed. This is called by the `Start Rider with IdeaVim` configuration.
* `./gradlew runWebstorm` — start the dev version of WebStorm with IdeaVim installed. This is called by the `Start WebStorm with IdeaVim` configuration.
* `./gradlew test -x :tests:property-tests:test -x :tests:long-running-tests:test` — run tests. This is called by the `IdeaVim tests` configuration.
* `./gradlew buildPlugin` — build the plugin. The result will be located in `build/distributions`. This file can be
installed by using `Settings | Plugin | >Gear Icon< | Install Plugin from Disk...`. You can stay with your personal build
for a few days or send it to a friend for testing.

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
      - [In the engine, and so in both hosts](vim-engine/src/jvmMain/resources/ksp-generated/engine_commands.json)
      - [In the IntelliJ plugin only](src/main/resources/ksp-generated/frontend_commands.json)
    - How commands are executed in common: `EditorActionHandlerBase`.
    - Key mapping: `KeyHandler.handleKey()`.

- Ex commands (`:set`, `:s`, `:nohlsearch`):
    - Any particular command:
        - [In the engine, and so in both hosts](vim-engine/src/jvmMain/resources/ksp-generated/engine_ex_commands.json)
        - [In the IntelliJ plugin only](src/main/resources/ksp-generated/frontend_ex_commands.json)
    - Vim script grammar: `Vimscript.g4`.
    - Vim script parsing: package `com.maddyhome.idea.vim.vimscript.parser`.
    - Vim script executor: `Executor`.

- Extensions:
    - Extensions handler: `VimExtensionHandler`.
    - Available extensions: package `com/maddyhome/idea/vim/extension`.

- Common features:
    - State machine. How every particular keystroke is parsed in IdeaVim: `KeyHandler.handleKey()`.
    - Options (`incsearch`, `iskeyword`, `relativenumber`): `VimOptionGroup`.
    - Plugin startup: `PluginStartup`.
    - Notifications: `NotificationService`.
    - Status bar icon: `StatusBar.kt`.
    - On/off switch: `VimPlugin.setEnabled()`.


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
   
##### Neovim
IdeaVim has an integration with neovim in tests. Tests that are performed with `doTest` also executed in
neovim instance, and the state of IdeaVim is asserted to be the same as the state of neovim.
- Only tests that use `doTest` are checked with neovim.
- Tests with `@VimBehaviorDiffers` or `@TestWithoutNeovim` annotations don't use neovim.

#### Property-based tests
Property-based tests are located under `propertybased` package. These tests a flaky by nature
although in most cases they are stable. If the test fails on your TeamCity run, try to check the test output and understand 
if the fail is caused by your changes. If it's not, just ignore the test.


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


## Two hosts, one engine

The engine is deliberately separate from the editor it runs in. `vim-engine` knows nothing about
IntelliJ or about VS Code; each host supplies its own implementations of the seams the engine
declares. Upstream split it this way so one engine could serve more than one editor, and that is
exactly what makes this fork possible.

| Path                | What it is                        | Compiles to       |
|---------------------|-----------------------------------|-------------------|
| `vim-engine/`       | The Vim engine, host-independent  | JVM **and** JS    |
| `src/main/java/`    | The IntelliJ plugin               | JVM               |
| `vscode-extension/` | The Vimperor VS Code extension    | JS (Kotlin/JS IR) |

`vim-engine` is Kotlin Multiplatform, so the engine lives in `src/commonMain/kotlin` rather than
`src/main/kotlin`. **A change there changes both hosts**, and the IntelliJ plugin's test suite is
the regression net that catches it - which is why the plugin is kept rather than deleted.


-----

## Getting help, and reporting things

Everything below is about **this fork**. It has its own repository, and the upstream project neither
maintains it nor supports it, so please do not send questions about it there.

### Something on this page is unclear, wrong, or missing.

That is a bug in the documentation and worth reporting like any other. Open an issue, or a pull
request if you already know what it should say.

### I have found a bug.

[Open an issue](https://github.com/neshkeev/vimperor/issues), and say which host it happened in -
the IntelliJ plugin or the VS Code extension - because the fix is in a different place depending on
the answer. If it is the extension, turn on `vimperor.trace` in VS Code's settings and include what
the Vimperor output channel printed: most of what is left to get wrong lives in the gap between the
engine and the editor, and a trace shows that gap directly.

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
* [`known-fixture-failures.txt`](vscode-extension/src/jsTest/fixtures/known-fixture-failures.txt) -
  every replayed fixture the VS Code host does not pass, with the reason for each
* [Changelog](CHANGES.md) and [contributors listing](AUTHORS.md), both inherited and both still
  the record of the work this fork is built on
