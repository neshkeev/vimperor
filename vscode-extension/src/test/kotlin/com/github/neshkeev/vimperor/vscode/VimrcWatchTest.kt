/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Following `~/.vimperorrc` and its two alternatives, and what happens when one changes.
 *
 * The watching itself is VS Code's - `createFileSystemWatcher` with a base directory, which reaches
 * outside the workspace - so what is worth testing is everything *around* it: which paths are
 * handed to it, what counts as a change worth mentioning, and what each of `'vimrcreload'`'s three
 * values does. All of that is [VimrcWatch] and none of it is in `Extension.kt`, which is the file
 * no unit test loads.
 */
class VimrcWatchTest {

  /** A home directory with nothing XDG about it, which is the common case. */
  private val environment: (String) -> String? = { name ->
    when (name) {
      "HOME" -> "/home/someone"
      else -> null
    }
  }

  private class Session {
    val fake = FakeEditor("one\n")
    val dispatched: MutableList<String> = mutableListOf()
    val host = VimHost(
      runCommand = { command, _, onDone ->
        dispatched += command
        onDone(true)
      },
    ).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun script(line: String) =
      injector.vimscriptExecutor.execute(line, host.editorFor(fake), VsCodeExecutionContext, skipHistory = true)

    /** Runs [body] as though it were lines of the config, which is what decides a mapping's owner. */
    fun asConfig(body: () -> Unit) {
      injector.vimscriptExecutor.executingIdeaVimRcConfiguration = true
      try {
        body()
      } finally {
        injector.vimscriptExecutor.executingIdeaVimRcConfiguration = false
      }
    }
  }

  /** What was asked, and the answer to give back. */
  private class Questions(val answer: String? = null) {
    val asked = mutableListOf<String>()
    val ask: (String, List<String>, (String?) -> Unit) -> Unit = { message, _, onAnswer ->
      asked += message
      onAnswer(answer)
    }
  }

  private fun watch(
    session: Session,
    questions: Questions = Questions(),
    mode: VimrcReload = VimrcReload.PROMPT,
    located: String? = "/home/someone/.vimperorrc",
    texts: Map<String, String> = mapOf("/home/someone/.vimperorrc" to "set number"),
  ) = VimrcWatch(
    host = session.host,
    environment = environment,
    readText = { texts[it] },
    locate = { located },
    ask = questions.ask,
    announce = {},
    mode = { mode },
  )

  // ---- which paths are watched -----------------------------------------------------------------

  /**
   * Every path a config could arrive at, not the one that loaded.
   *
   * Creating `~/.vimperorrc` while only `~/.vimrc` is in force changes which file wins, so watching
   * the loaded file alone would miss the most interesting change there is.
   */
  @Test
  fun `test every candidate path is watched, not only the one in force`() {
    val paths = watch(Session()).pathsToWatch()

    assertTrue("/home/someone/.vimperorrc" in paths, paths.toString())
    assertTrue("/home/someone/.ideavimrc" in paths, paths.toString())
    assertTrue("/home/someone/.vimrc" in paths, paths.toString())
    assertTrue("/home/someone/.vim/vimrc" in paths, "the family Vim users keep under ~/.vim: $paths")
    assertTrue("/home/someone/.config/vimperor/vimperorrc" in paths, "the XDG location: $paths")
    assertEquals(paths.distinct(), paths, "a path watched twice is a question asked twice")
  }

  @Test
  fun `test none watches nothing at all`() {
    assertEquals(emptyList(), watch(Session(), mode = VimrcReload.NONE).pathsToWatch())
  }

  @Test
  fun `test a custom vimrc is watched first`() {
    val watch = VimrcWatch(
      host = Session().host,
      environment = { name -> if (name == "IDEA_VIM_CUSTOM_VIMRC") "/tmp/custom" else environment(name) },
      readText = { null },
      locate = { null },
      mode = { VimrcReload.PROMPT },
    )

    assertEquals("/tmp/custom", watch.pathsToWatch().first())
  }

  // ---- what counts as a change -----------------------------------------------------------------

  @Test
  fun `test a changed config is offered`() {
    val session = Session()
    val questions = Questions()
    val texts = mutableMapOf("/home/someone/.vimperorrc" to "set number")
    val watch = VimrcWatch(
      session.host, environment, { texts[it] }, { "/home/someone/.vimperorrc" },
      questions.ask, {}, { VimrcReload.PROMPT },
    )
    watch.remember("/home/someone/.vimperorrc")

    texts["/home/someone/.vimperorrc"] = "set relativenumber"
    watch.settle()

    assertEquals(1, questions.asked.size, "expected one question, got ${questions.asked}")
    assertTrue(questions.asked.single().contains(".vimperorrc"), questions.asked.single())
  }

  /**
   * A rewrite that produced identical bytes is not a change the user made.
   *
   * Format-on-save does this, and so does any editor that writes by replacing the file. Being asked
   * to load a config that did not change is worse than not being asked.
   */
  @Test
  fun `test an identical rewrite asks nothing`() {
    val session = Session()
    val questions = Questions()
    val watch = watch(session, questions)
    watch.remember("/home/someone/.vimperorrc")

    watch.settle()

    assertEquals(emptyList(), questions.asked)
  }

  /** A different file winning is a change, and the message says which file it is. */
  @Test
  fun `test a higher-priority config appearing is offered by name`() {
    val session = Session()
    val questions = Questions()
    val texts = mutableMapOf("/home/someone/.vimrc" to "set number")
    var found = "/home/someone/.vimrc"
    val watch = VimrcWatch(
      session.host, environment, { texts[it] }, { found }, questions.ask, {}, { VimrcReload.PROMPT },
    )
    watch.remember("/home/someone/.vimrc")

    texts["/home/someone/.vimperorrc"] = "set relativenumber"
    found = "/home/someone/.vimperorrc"
    watch.settle()

    assertTrue(
      questions.asked.single().contains("/home/someone/.vimperorrc"),
      "the question should name the file that now wins: ${questions.asked.single()}",
    )
  }

  @Test
  fun `test the config going away is offered too`() {
    val session = Session()
    val questions = Questions()
    val watch = VimrcWatch(
      session.host, environment, { null }, { null }, questions.ask, {}, { VimrcReload.PROMPT },
    )
    watch.remember("/home/someone/.vimperorrc")

    watch.settle()

    assertEquals(1, questions.asked.size, questions.asked.toString())
  }

  // ---- the four modes --------------------------------------------------------------------------

  @Test
  fun `test autoreload loads without asking`() {
    val session = Session()
    val questions = Questions()
    val texts = mutableMapOf("/home/someone/.vimperorrc" to "set number")
    val watch = VimrcWatch(
      session.host, environment, { texts[it] }, { "/home/someone/.vimperorrc" },
      questions.ask, {}, { VimrcReload.AUTORELOAD },
    )
    watch.remember("/home/someone/.vimperorrc")

    texts["/home/someone/.vimperorrc"] = "set relativenumber"
    watch.settle()

    assertEquals(emptyList(), questions.asked, "autoreload must not ask")
    assertEquals(emptyList(), session.dispatched, "and must not restart")
  }

  /**
   * `autorestart` restarts instead, which is the other thing `auto` could have meant.
   *
   * Which is why there are two values and not one: re-sourcing and restarting differ in what they
   * can undo, so a setting that said only "automatically" left the reader to guess.
   */
  @Test
  fun `test autorestart restarts without asking`() {
    val session = Session()
    val questions = Questions()
    val texts = mutableMapOf("/home/someone/.vimperorrc" to "set number")
    val watch = VimrcWatch(
      session.host, environment, { texts[it] }, { "/home/someone/.vimperorrc" },
      questions.ask, {}, { VimrcReload.AUTORESTART },
    )
    watch.remember("/home/someone/.vimperorrc")

    texts["/home/someone/.vimperorrc"] = "set relativenumber"
    watch.settle()

    assertEquals(emptyList(), questions.asked, "autorestart must not ask")
    assertEquals(listOf(VsCodeCommands.RESTART_EXTENSION_HOST), session.dispatched)
  }

  @Test
  fun `test none does nothing even when a change arrives`() {
    val session = Session()
    val questions = Questions()
    val texts = mutableMapOf("/home/someone/.vimperorrc" to "set number")
    val watch = VimrcWatch(
      session.host, environment, { texts[it] }, { "/home/someone/.vimperorrc" },
      questions.ask, {}, { VimrcReload.NONE },
    )
    watch.remember("/home/someone/.vimperorrc")

    texts["/home/someone/.vimperorrc"] = "set relativenumber"
    watch.settle()

    assertEquals(emptyList(), questions.asked)
  }

  /**
   * Dismissing takes the new text as the baseline, so the same edit is not offered twice.
   *
   * Being asked once about an edit is help. Being asked again about the same edit, on the next
   * unrelated save, is nagging - and a watcher fires on every save.
   */
  @Test
  fun `test a dismissed question is not asked again for the same edit`() {
    val session = Session()
    val questions = Questions(answer = VimrcAnswer.DISMISS.label)
    val texts = mutableMapOf("/home/someone/.vimperorrc" to "set number")
    val watch = VimrcWatch(
      session.host, environment, { texts[it] }, { "/home/someone/.vimperorrc" },
      questions.ask, {}, { VimrcReload.PROMPT },
    )
    watch.remember("/home/someone/.vimperorrc")

    texts["/home/someone/.vimperorrc"] = "set relativenumber"
    watch.settle()
    watch.settle()

    assertEquals(1, questions.asked.size, "asked twice about one edit: ${questions.asked}")
  }

  /**
   * **Always Reload** stops the asking for the rest of the session, and only for that.
   *
   * A notification cannot carry a checkbox, so remembering is a button. What it must not be is
   * sticky beyond the session - a preference nobody wrote in their config should not quietly become
   * permanent, and the next extension host asks again.
   */
  @Test
  fun `test always reload stops the asking for the rest of the session`() {
    val session = Session()
    val questions = Questions(answer = VimrcAnswer.ALWAYS_RELOAD.label)
    val texts = mutableMapOf("/home/someone/.vimperorrc" to "set number")
    val watch = VimrcWatch(
      session.host, environment, { texts[it] }, { "/home/someone/.vimperorrc" },
      questions.ask, {}, { VimrcReload.PROMPT },
    )
    watch.remember("/home/someone/.vimperorrc")

    texts["/home/someone/.vimperorrc"] = "set relativenumber"
    watch.settle()
    assertEquals(1, questions.asked.size, "the first change is still a question")

    texts["/home/someone/.vimperorrc"] = "set nonumber"
    watch.settle()
    texts["/home/someone/.vimperorrc"] = "set number"
    watch.settle()

    assertEquals(1, questions.asked.size, "asked again after Always Reload: ${questions.asked}")
  }

  /**
   * And an explicit `:set` after it wins, which costs no listener.
   *
   * The remembered answer only lifts `prompt` to `autoreload`, so any other value of the option is
   * itself. Someone who sets the option after pressing the button has spoken more recently.
   */
  @Test
  fun `test setting the option after Always Reload takes it back`() {
    val session = Session()
    val questions = Questions(answer = VimrcAnswer.ALWAYS_RELOAD.label)
    val texts = mutableMapOf("/home/someone/.vimperorrc" to "set number")
    var option = VimrcReload.PROMPT
    val watch = VimrcWatch(
      session.host, environment, { texts[it] }, { "/home/someone/.vimperorrc" },
      questions.ask, {}, { option },
    )
    watch.remember("/home/someone/.vimperorrc")

    texts["/home/someone/.vimperorrc"] = "set relativenumber"
    watch.settle()
    assertEquals(1, questions.asked.size)

    // `:set vimrcreload=none` - and the remembered answer must not override it.
    option = VimrcReload.NONE
    texts["/home/someone/.vimperorrc"] = "set nonumber"
    watch.settle()

    assertEquals(1, questions.asked.size, "none must still mean none")
    assertEquals("set nonumber", texts["/home/someone/.vimperorrc"], "and nothing reloaded")
  }

  @Test
  fun `test the restart answer asks VS Code to restart the extensions`() {
    val session = Session()
    val questions = Questions(answer = VimrcAnswer.RESTART.label)
    val texts = mutableMapOf("/home/someone/.vimperorrc" to "set number")
    val watch = VimrcWatch(
      session.host, environment, { texts[it] }, { "/home/someone/.vimperorrc" },
      questions.ask, {}, { VimrcReload.PROMPT },
    )
    watch.remember("/home/someone/.vimperorrc")

    texts["/home/someone/.vimperorrc"] = "set relativenumber"
    watch.settle()

    assertTrue(
      session.dispatched.contains(VsCodeCommands.RESTART_EXTENSION_HOST),
      "expected a restart, dispatched ${session.dispatched}",
    )
  }

  // ---- the option ------------------------------------------------------------------------------

  @Test
  fun `test vimrcreload defaults to prompt and takes the four values`() {
    val session = Session()

    assertEquals(VimrcReload.PROMPT, vimrcReloadMode())

    session.script("set vimrcreload=autoreload")
    assertEquals(VimrcReload.AUTORELOAD, vimrcReloadMode())

    session.script("set vimrcreload=autorestart")
    assertEquals(VimrcReload.AUTORESTART, vimrcReloadMode())

    session.script("set vrr=none")
    assertEquals(VimrcReload.NONE, vimrcReloadMode(), "the short name has to work too")

    session.script("set vimrcreload=prompt")
    assertEquals(VimrcReload.PROMPT, vimrcReloadMode())
  }

  // ---- what a reload does ----------------------------------------------------------------------

  /**
   * A reload replaces the config's own mappings rather than adding to them.
   *
   * The first mapping registered for a key wins, so without dropping the old ones a changed
   * right-hand side would never take effect. What decides whether a mapping is the config's is its
   * owner: `MapCommand` writes `MappingOwner.IdeaVim.InitScript` while the config is running and
   * `IdeaVim.Other` at every other time. IdeaVim's `clearIdeaVimRcState` is the same line.
   */
  @Test
  fun `test a reload drops the mappings the config made`() {
    val session = Session()
    session.asConfig { session.script("nmap gx ihello<Esc>") }

    session.host.reloadVimRc { name -> if (name == "IDEA_VIM_CUSTOM_VIMRC") null else environment(name) }
    session.host.type(session.fake, "g")
    session.host.type(session.fake, "x")

    assertEquals("one\n", session.fake.document.content, "the old config's mapping should be gone")
  }

  /**
   * And keeps the ones the user typed, which is the half that makes the other one mean something.
   *
   * A `:nmap` entered at the command line is not the config's and has nothing to do with it being
   * reloaded. Dropping every mapping would be the easy implementation and the wrong one.
   */
  @Test
  fun `test a reload keeps a mapping typed by hand`() {
    val session = Session()
    session.script("nmap gx ihello<Esc>")

    session.host.reloadVimRc { name -> if (name == "IDEA_VIM_CUSTOM_VIMRC") null else environment(name) }
    session.host.type(session.fake, "g")
    session.host.type(session.fake, "x")

    assertEquals("helloone\n", session.fake.document.content, "a hand-typed mapping should survive")
  }
}
