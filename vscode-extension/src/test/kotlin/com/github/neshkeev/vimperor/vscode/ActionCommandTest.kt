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
 * `:action`, `:actionlist` and `<Action>()` - Vim's door onto everything the editor can do.
 *
 * IdeaVim's three, in the terms VS Code uses. IntelliJ has actions and VS Code has commands, and
 * the difference that matters is not the word: an IntelliJ action can be looked up, run and
 * answered for synchronously, and a VS Code command can only be dispatched at a promise. So `:action`
 * reports "not found" from a list fetched once at activation, and reports success from the fact
 * that it asked.
 *
 * [`the list is what decides whether a name is an action`] is the one worth reading twice. Refusing
 * a name Vim has not heard of is the whole value of `:action` over a raw dispatch - a typo says so
 * at the `:` prompt - and refusing one only because a promise had not landed would be a lie, so the
 * unloaded case accepts everything.
 */
class ActionCommandTest {

  private class RecordingChannel : OutputChannel {
    val lines: MutableList<String> = mutableListOf()

    override fun appendLine(value: String) {
      lines += value
    }

    @Suppress("OVERRIDING_EXTERNAL_FUN_WITH_OPTIONAL_PARAMS")
    override fun show(preserveFocus: Boolean) {}
    override fun dispose() {}
  }

  private class Session(text: String = "hello world", known: List<String>? = SOME_COMMANDS) {
    val fake = FakeEditor(text)
    val channel = RecordingChannel()
    val dispatched: MutableList<String> = mutableListOf()
    val errors: MutableList<String> = mutableListOf()

    val host = VimHost(
      sink = object : MessageSink {
        override fun message(text: String?) {}
        override fun error(text: String?) { errors += text.orEmpty() }
        override fun status(text: String?) {}
      },
      // Answers the way VS Code does: a command it does not have is a rejected promise, which is
      // what [VimHost.run] turns into the message the user sees.
      runCommand = { command, _, onDone ->
        dispatched += command
        onDone(known == null || command in known || command in IdeaActionAliases.targets)
      },
      outputPanel = OutputChannelPanelService(channel),
    ).also { it.start() }

    init {
      // What activation does when `commands.getCommands` resolves. Null models the window where it
      // has not resolved yet, which is a real state and the one the fallback is for.
      known?.let { host.rememberActions(it) }
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)

    fun run(command: String) {
      type(":")
      type(command)
      key("<CR>")
    }

    /**
     * The same, with the workbench layout arriving on the `<CR>` - which is where it arrives.
     *
     * The manifest binds Enter eight times, one per combination of the three context keys, so the
     * reading is taken at the instant the key is pressed and reaches the host a moment before the
     * command it is about runs. Modelled rather than described, because the order is the point.
     */
    fun run(command: String, visible: Set<WorkbenchArea>) {
      type(":")
      type(command)
      host.layoutReported(visible)
      key("<CR>")
    }

    val printed: String get() = channel.lines.joinToString("\n")
  }

  private companion object {
    val ALL_THREE = WorkbenchArea.entries.toSet()

    /** A plausible slice of what a real window answers, in the order it does not answer it. */
    val SOME_COMMANDS = listOf(
      "workbench.action.showCommands",
      "editor.action.formatDocument",
      "git.commitStagedAll",
      "git.pull",
      "_internalOne",
      "cursorDown",
    )
  }

  // `:action`

  @Test
  fun `test action runs the VS Code command it names`() {
    val session = Session()
    session.run("action editor.action.formatDocument")

    assertEquals(listOf("editor.action.formatDocument"), session.dispatched)
    assertEquals(emptyList(), session.errors)
  }

  @Test
  fun `test action trims what it was given`() {
    // The `:` prompt hands the argument over with the space after the command name still on it.
    val session = Session()
    session.run("action   git.pull  ")

    assertEquals(listOf("git.pull"), session.dispatched)
  }

  /**
   * The list is what decides whether a name is an action.
   *
   * A name that is neither a command this window has nor one of IntelliJ's says so, rather than
   * being dispatched into the dark and failing somewhere the user is not looking.
   */
  @Test
  fun `test action reports a name nothing has heard of`() {
    val session = Session()
    session.run("action NoSuchThingAnywhere")

    assertEquals(emptyList(), session.dispatched, "nothing should have been sent to VS Code")
    assertEquals(listOf("Action not found: NoSuchThingAnywhere"), session.errors)
  }

  // IntelliJ's names, which is what an `.ideavimrc` is written in.

  /**
   * `:action GotoClass` is not a typo, it is a different editor's word for the same thing.
   *
   * The whole point of [IdeaActionAliases]: a config carried over from IdeaVim is full of these and
   * every one of them failed, the ones in mappings silently. The engine passes a name through
   * without caring whose vocabulary it is in, so the translation belongs here.
   */
  @Test
  fun `test an IntelliJ action name runs the VS Code command that does the same job`() {
    val session = Session()
    session.run("action GotoClass")

    assertEquals(listOf("workbench.action.showAllSymbols"), session.dispatched)
    assertEquals(emptyList(), session.errors)
  }

  @Test
  fun `test an Action mapping written for IdeaVim works too`() {
    val session = Session()
    session.run("nmap <C-o> <Action>(Back)")
    session.key("<C-O>")

    assertEquals(listOf("workbench.action.navigateBack"), session.dispatched)
  }

  /**
   * An action that is IntelliJ's and nothing else says which of the two it is.
   *
   * "Action not found" would send the reader looking for a typo. The name is right; the feature is
   * the IDE's build model, and no command id is going to fix that.
   */
  @Test
  fun `test an IntelliJ-only action says so rather than reporting a typo`() {
    val session = Session()
    session.run("action MakeGradleModule")

    assertEquals(emptyList(), session.dispatched)
    assertEquals(
      listOf("MakeGradleModule is one of IntelliJ's actions and this VS Code has nothing that does it."),
      session.errors,
    )
  }

  /**
   * A command this window really has wins over the table.
   *
   * The two vocabularies do not collide in practice - VS Code's ids are dotted and lowercase and
   * IntelliJ's are not - so this is the order guaranteeing it rather than the observation.
   */
  @Test
  fun `test a real command name is never translated`() {
    val session = Session(known = SOME_COMMANDS + "GotoClass")
    session.run("action GotoClass")

    assertEquals(listOf("GotoClass"), session.dispatched, "the user named a command this window has")
  }

  /**
   * A mapping naming nothing is still sent, and the window is what says so.
   *
   * Not refused here, which is the tempting version: the id list arrives over a promise and a stub
   * host runs on a deliberately short one, so a host that refused what it could not place would let
   * an incomplete list silently disable a mapping that works. `zo` in the stub host is exactly that
   * case, and it is how this was found.
   */
  @Test
  fun `test an Action mapping that names nothing is reported by the window`() {
    val session = Session()
    session.run("nmap <Leader>z <Action>(NoSuchThingAnywhere)")
    session.type("\\z")

    assertEquals(listOf("NoSuchThingAnywhere"), session.dispatched)
    assertEquals(listOf("Vimperor: VS Code has no command 'NoSuchThingAnywhere'."), session.errors)
  }

  /** ...and the extension's own ids are never in doubt, whatever the window has answered. */
  @Test
  fun `test a command the extension itself uses is always an action`() {
    val session = Session(known = emptyList())
    session.run("action editor.unfold")

    assertEquals(listOf("editor.unfold"), session.dispatched)
  }

  /**
   * Every action the reported `.ideavimrc` names is answered.
   *
   * This is the acceptance test for "so `~/.ideavimrc` can be picked up without modifications" -
   * the list is that file's, verbatim. Answered means one of two things, and both count: a VS Code
   * command that does the job, or a sentence saying the job is IntelliJ's. What must not happen is
   * `Action not found`, which is the report that sends someone looking for a mistake they did not
   * make.
   */
  @Test
  fun `test every action in the reported config is answered`() {
    val fromTheReport = listOf(
      "GotoClass", "GotoFile", "RecentFiles", "GotoImplementation", "GotoRelated", "QuickJavaDoc",
      "TypeHierarchy", "Back", "Forward", "MakeGradleModule", "Maven.ReimportProject",
      "Maven.Reimport", "CloseAllEditorsButActive", "HideAllWindows", "editRunConfigurations",
      "GotoSuperMethod", "Annotate", "StructuralSearchPlugin.StructuralSearchAction",
      "XDebugger.MuteBreakpoints", "ActivateInspectionResultsToolWindow", "JumpToLastChange",
    )

    val unanswered = fromTheReport.filterNot { IdeaActionAliases.contains(it) }

    assertEquals(emptyList(), unanswered, "every name in that file has to resolve to something")
  }

  /**
   * `HideAllWindows` hides every area a tool window can be in, not just the sidebar.
   *
   * Reported from a real window: with the Explorer and the Output panel both open, `:action
   * HideAllWindows` hid the Explorer and left the Output panel exactly where it was. The alias
   * pointed at `workbench.action.toggleSidebarVisibility` and the sidebar is one of three places
   * VS Code puts these.
   */
  @Test
  fun `test HideAllWindows closes every area that is open`() {
    val session = Session(known = null)
    session.run("action HideAllWindows", ALL_THREE)

    assertEquals(
      listOf(
        VsCodeCommands.CLOSE_SIDEBAR,
        VsCodeCommands.CLOSE_PANEL,
        VsCodeCommands.CLOSE_AUXILIARY_BAR,
      ),
      session.dispatched,
    )
  }

  /**
   * ...and only what is open, which is the whole point of reading the layout.
   *
   * IntelliJ's `HideAllToolWindowsAction` hides the visible tool windows and remembers their ids.
   * VS Code answers no API about its three areas, so this reads them from the `when` clause on the
   * key that ran the command - see [WorkbenchToggle].
   */
  @Test
  fun `test HideAllWindows leaves alone what is already closed`() {
    val session = Session(known = null)
    session.run("action HideAllWindows", setOf(WorkbenchArea.SIDEBAR))

    assertEquals(listOf(VsCodeCommands.CLOSE_SIDEBAR), session.dispatched)
  }

  /**
   * The second press puts back exactly what the first one hid, and nothing else.
   *
   * This is what was asked for and what IdeaVim does. Before the layout could be read, the restore
   * opened all three - so a panel deliberately left closed came back with the sidebar.
   */
  @Test
  fun `test HideAllWindows puts back only what it hid`() {
    val session = Session(known = null)
    session.run("action HideAllWindows", setOf(WorkbenchArea.SIDEBAR, WorkbenchArea.PANEL))
    session.dispatched.clear()

    // Nothing is showing now, which is what the next key's `when` clause reports.
    session.run("action HideAllWindows", emptySet())

    assertEquals(
      listOf(
        VsCodeCommands.FOCUS_SIDEBAR,
        VsCodeCommands.FOCUS_PANEL,
        VsCodeCommands.FOCUS_EDITOR,
      ),
      session.dispatched,
      "the auxiliary bar was closed before any of this and stays closed",
    )
  }

  /**
   * The restore opens absolutely and then hands focus back.
   *
   * It used to toggle, on the reasoning that a toggle does not steal focus. That traded the wrong
   * risk away: a toggle is only right if the area is in the state you left it in, so opening the
   * panel by hand while everything was hidden meant the next press *closed* it. `focus*` means one
   * thing whatever the layout, and the focus it takes is given straight back.
   */
  @Test
  fun `test the restore ends by putting the cursor back in the editor`() {
    val session = Session(known = null)
    session.run("action HideAllWindows", ALL_THREE)
    session.dispatched.clear()

    session.run("action HideAllWindows", emptySet())

    assertEquals(VsCodeCommands.FOCUS_EDITOR, session.dispatched.last())
  }

  /**
   * An area you opened by hand is hidden again rather than treated as a restore.
   *
   * There is no latch to get stuck on once the layout can be read: the direction is "is anything
   * showing", which is IntelliJ's rule as well, so this self-corrects however the user got there.
   */
  @Test
  fun `test an area reopened by hand is hidden rather than restored`() {
    val session = Session(known = null)
    session.run("action HideAllWindows", ALL_THREE)
    session.dispatched.clear()

    session.run("action HideAllWindows", setOf(WorkbenchArea.PANEL))

    assertEquals(listOf(VsCodeCommands.CLOSE_PANEL), session.dispatched)
  }

  /**
   * With nothing showing and nothing remembered, everything comes back.
   *
   * A window that starts with all three closed has nothing for this to restore. Doing nothing at
   * all would be the strictly honest answer and is useless: the user pressed a key and would see
   * no reason why it did nothing.
   */
  @Test
  fun `test nothing open and nothing hidden yet opens all three`() {
    val session = Session(known = null)
    session.run("action HideAllWindows", emptySet())

    assertEquals(
      listOf(
        VsCodeCommands.FOCUS_SIDEBAR,
        VsCodeCommands.FOCUS_PANEL,
        VsCodeCommands.FOCUS_AUXILIARY_BAR,
        VsCodeCommands.FOCUS_EDITOR,
      ),
      session.dispatched,
    )
  }

  /**
   * With no reading at all it alternates, which is what it did before one was possible.
   *
   * A mapping replays `<CR>` through the engine rather than through the keybinding, and
   * `<Action>(HideAllWindows)` presses no key, so both arrive here. Alternating is not right, but
   * it is predictable, and it is better than a stale reading - one that always claimed something
   * was visible would hide forever and never restore.
   */
  @Test
  fun `test with no reading it alternates as it used to`() {
    val session = Session(known = null)
    session.run("action HideAllWindows")
    assertEquals(
      listOf(
        VsCodeCommands.CLOSE_SIDEBAR,
        VsCodeCommands.CLOSE_PANEL,
        VsCodeCommands.CLOSE_AUXILIARY_BAR,
      ),
      session.dispatched,
    )

    session.dispatched.clear()
    session.run("action HideAllWindows")
    assertEquals(VsCodeCommands.FOCUS_SIDEBAR, session.dispatched.first())
    assertEquals(VsCodeCommands.FOCUS_EDITOR, session.dispatched.last())
  }

  /** ...and a third hides again, so the two directions alternate rather than latching. */
  @Test
  fun `test HideAllWindows alternates without a reading`() {
    val session = Session(known = null)
    session.run("action HideAllWindows")
    session.run("action HideAllWindows")
    session.dispatched.clear()

    session.run("action HideAllWindows")

    assertEquals(VsCodeCommands.CLOSE_SIDEBAR, session.dispatched.first())
  }

  /**
   * A reading is spent by the press it arrived with, and the next press falls back.
   *
   * The alternative is to keep it, and that is the failure worth naming: a reading kept from three
   * minutes ago that says the sidebar is open would hide a sidebar that is already hidden, remember
   * it, and answer the same way for ever. This never restores. Alternating does.
   */
  @Test
  fun `test a reading is not reused by a later press`() {
    val session = Session(known = null)
    session.run("action HideAllWindows", setOf(WorkbenchArea.SIDEBAR))
    session.dispatched.clear()

    session.run("action HideAllWindows")

    assertEquals(VsCodeCommands.FOCUS_SIDEBAR, session.dispatched.first(), "the latch says restore")
  }

  /**
   * Asking whether the action exists must not spend a press, or the reading it would run on.
   *
   * `:action` looks a name up before running it, so a toggle that advanced on lookup would flip
   * twice per press and never appear to do anything. This is the reason both the direction and the
   * reading move in `executeAction` rather than in `resolve`.
   */
  @Test
  fun `test looking the action up does not move the toggle`() {
    val session = Session(known = null)
    val executor = injector.actionExecutor

    executor.getAction("HideAllWindows")
    executor.getAction("HideAllWindows")
    session.run("action HideAllWindows", setOf(WorkbenchArea.PANEL))

    assertEquals(listOf(VsCodeCommands.CLOSE_PANEL), session.dispatched, "the first press still hides")
  }

  /**
   * A reading has to name all three areas or it is not a reading.
   *
   * A partial one is the single thing this must not produce: an area missing from the set reads as
   * "closed", and a restore would quietly stop putting it back. The manifest's eight bindings each
   * carry all three, so anything short of that came from somewhere else.
   */
  @Test
  fun `test a partial reading is refused`() {
    assertEquals(null, workbenchLayoutOf(true, true, null))
    assertEquals(null, workbenchLayoutOf(null, null, null))
    assertEquals(emptySet(), workbenchLayoutOf(false, false, false))
    assertEquals(ALL_THREE, workbenchLayoutOf(true, true, true))
    assertEquals(setOf(WorkbenchArea.PANEL), workbenchLayoutOf(false, true, false))
  }

  /**
   * The manifest carries the layout on Enter, once per combination of the three context keys.
   *
   * Eight bindings for one key is a cost, and this is what buys it: `sideBarVisible`, `panelVisible`
   * and `auxiliaryBarVisible` are readable in a `when` clause and nowhere else, so the only way to
   * learn the layout is to be told by a key. They have to be exhaustive - a combination with no
   * binding is an Enter that does nothing at all - and each argument has to agree with the clause it
   * is written beside, which is the half no reader can check by eye.
   */
  @Test
  fun `test every combination of the layout is bound to Enter and says what it means`() {
    val root = repositoryRoot()
    assertTrue(root != null, "could not find the repository root, so package.json could not be read")
    val parsed = JSON.parse<dynamic>(readText("$root/vscode-extension/package.json"))
    val bindings = parsed.contributes.keybindings as Array<dynamic>

    val enters = bindings.filter { it.key == "enter" && it.command == "vimperor.key" }
    assertEquals(8, enters.size, "one per combination of three context keys")

    val seen = enters.map { binding ->
      val clause = binding.`when` as String
      assertEquals("<CR>", binding.args.key as? String, "every one of them is still the Enter key")
      WorkbenchArea.entries.map { area ->
        val visible = binding.args[area.key] as? Boolean
        assertTrue(visible != null, "${area.key} is missing from an Enter binding's arguments")
        val expected = if (visible == true) " ${area.key}Visible" else " !${area.key}Visible"
        assertTrue(clause.contains(expected), "`$clause` does not agree with ${area.key}=$visible")
        visible
      }
    }
    assertEquals(8, seen.toSet().size, "and no combination is bound twice")
  }

  // The table itself.

  @Test
  fun `test no alias is blank on either side`() {
    val blank = IdeaActionAliases.all
      .filter { (key, commands) -> key.isBlank() || commands?.any { it.isBlank() } == true }
    assertEquals(emptyMap(), blank)
  }

  /**
   * No name is its own translation.
   *
   * A key that is also a target would mean the table had been filled in with VS Code ids on the
   * left, which is the mistake this file's whole ordering is built to survive - and one worth
   * catching where it is written rather than where it fails.
   */
  @Test
  fun `test no IntelliJ name is also a VS Code command this table sends`() {
    val confused = IdeaActionAliases.all.keys.filter { it in IdeaActionAliases.targets }
    assertEquals(emptyList(), confused)
  }

  @Test
  fun `test the activation check notices a target this window lacks`() {
    val missing = IdeaActionAliases.missingFrom(listOf("workbench.action.quickOpen"))

    assertTrue("workbench.action.showAllSymbols" in missing, "the ones that are gone should be named")
    assertTrue("workbench.action.quickOpen" !in missing, "and the one that is there should not be")
    assertTrue(missing.size < IdeaActionAliases.targets.size, "the comparison has to be able to pass")
  }

  @Test
  fun `test action accepts anything until the list has arrived`() {
    // Activation asks VS Code what it can do and the answer comes back over a promise. Refusing a
    // name because that promise has not landed would be a lie: the command may well exist.
    val session = Session(known = null)
    session.run("action anything.at.all")

    assertEquals(listOf("anything.at.all"), session.dispatched)
    assertEquals(emptyList(), session.errors)
  }

  @Test
  fun `test an internal command is still an action`() {
    // VS Code's `filterInternal` drops the underscore-prefixed ones, and this host asks for the
    // unfiltered list: "internal" is a naming convention, not a promise that it will not run.
    val session = Session()
    session.run("action _internalOne")

    assertEquals(listOf("_internalOne"), session.dispatched)
  }

  @Test
  fun `test an Action mapping runs the same command`() {
    // `<Action>(...)` is the form IdeaVim tells people to prefer, and it goes through the same
    // executor by a different door - the key handler rather than the `:` prompt.
    val session = Session()
    session.run("nmap <Leader>f <Action>(editor.action.formatDocument)")
    session.type("\\f")

    assertEquals(listOf("editor.action.formatDocument"), session.dispatched)
  }

  // `:actionlist`

  @Test
  fun `test actionlist prints every command, sorted`() {
    val session = Session()
    session.run("actionlist")

    val listed = session.printed.lines().filter { it in SOME_COMMANDS }
    assertEquals(SOME_COMMANDS.sorted(), listed, "every command, in an order a reader can scan")
    assertTrue("--- Actions ---" in session.printed, "IdeaVim's header, which is the engine's string")
    assertTrue("--- 6 of 6 ---" in session.printed, "and how much of the list is showing")
  }

  @Test
  fun `test actionlist filters by what it was given`() {
    val session = Session()
    session.run("actionlist git")

    assertTrue("git.pull" in session.printed)
    assertTrue("cursorDown" !in session.printed, "the ones that do not match should be gone")
    assertTrue("--- 2 of 6 ---" in session.printed)
  }

  /** IdeaVim's `*`, which splits the pattern rather than matching anything. */
  @Test
  fun `test a star splits the pattern into pieces that must all appear`() {
    val session = Session()
    session.run("actionlist git*commit")

    assertTrue("git.commitStagedAll" in session.printed)
    assertTrue("git.pull" !in session.printed, "`pull` has no `commit` in it")
    assertTrue("--- 1 of 6 ---" in session.printed)
  }

  @Test
  fun `test the filter ignores case`() {
    val session = Session()
    session.run("actionlist FORMATDOCUMENT")

    assertTrue("editor.action.formatDocument" in session.printed)
    assertTrue("--- 1 of 6 ---" in session.printed)
  }

  @Test
  fun `test actionl is enough of it`() {
    // `actionl[ist]`, the way IdeaVim spells it - so `:actionl` resolves and `:action` does not.
    val session = Session()
    session.run("actionl git")

    assertTrue("git.pull" in session.printed)
    assertEquals(emptyList(), session.dispatched, "`:actionl` is not `:action` with an argument")
  }

  // The chord beside each command, which is the half of IdeaVim's list VS Code will not hand over.

  @Test
  fun `test actionlist prints the chord bound to a command`() {
    val session = Session()
    session.host.keybindings.setDefaults(listOf(Keybinding("git.pull", "shift+cmd+p")))
    session.run("actionlist git.pull")

    val line = session.printed.lines().single { it.startsWith("git.pull") }
    assertTrue(line.endsWith("shift+cmd+p"), "the chord should follow the name: '$line'")
    assertTrue(line.startsWith("git.pull "), "and the name should still be the start of the line")
  }

  /** A command with nothing bound to it is the name alone, not a name with a column of spaces. */
  @Test
  fun `test a command nothing is bound to is printed on its own`() {
    val session = Session()
    session.host.keybindings.setDefaults(listOf(Keybinding("git.pull", "shift+cmd+p")))
    session.run("actionlist")

    assertTrue("cursorDown" in session.printed.lines(), "got ${session.printed}")
  }

  /**
   * IdeaVim filters on the whole line rather than on the name, so the list answers the other
   * question a reader has: not "what is this command called" but "what is this key doing".
   */
  @Test
  fun `test the filter matches the chord as well as the name`() {
    val session = Session()
    session.host.keybindings.setDefaults(listOf(Keybinding("git.pull", "shift+cmd+p")))
    session.run("actionlist cmd+p")

    assertTrue("git.pull" in session.printed)
    assertTrue("--- 1 of 6 ---" in session.printed, "got ${session.printed}")
  }

  @Test
  fun `test both chords are printed when a command has two`() {
    val session = Session()
    session.host.keybindings.setDefaults(listOf(Keybinding("git.pull", "cmd+g"), Keybinding("git.pull", "cmd+shift+g")))
    session.run("actionlist git.pull")

    val line = session.printed.lines().single { it.startsWith("git.pull") }
    assertTrue(line.endsWith("cmd+g  cmd+shift+g"), "got '$line'")
  }

  /** Nothing read yet is the list as it was before any of this, rather than an error. */
  @Test
  fun `test a window whose keybindings could not be read still lists its commands`() {
    val session = Session()
    session.run("actionlist")

    assertTrue("git.pull" in session.printed.lines(), "got ${session.printed}")
    assertTrue("--- 6 of 6 ---" in session.printed)
  }

  @Test
  fun `test actionlist says so when it has not been told anything`() {
    val session = Session(known = null)
    session.run("actionlist")

    assertTrue(
      "has not been told what commands this VS Code has" in session.printed,
      "an empty list would read as an editor that can do nothing: ${session.printed}",
    )
  }
}
