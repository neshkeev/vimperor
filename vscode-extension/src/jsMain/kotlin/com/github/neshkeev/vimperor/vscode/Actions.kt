/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.NativeAction
import com.maddyhome.idea.vim.api.VimActionExecutor
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.handler.EditorActionHandlerBase
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.commands.Command
import com.maddyhome.idea.vim.vimscript.model.commands.CommandModifier

/**
 * Vim's own actions, and VS Code's commands as if they were IntelliJ's actions.
 *
 * IdeaVim gives a user `:action GotoClass`, `:actionlist` and `<Action>(Back)`, and behind all
 * three is one idea: the editor has a named thing it can do, and Vim can name it. IntelliJ calls
 * them actions and VS Code calls them commands, and the engine does not care which - it asks the
 * host for the names it needs by [ACTION_COLLAPSE_REGION] and the rest, and passes everything else
 * through from what the user typed.
 *
 * `executeVimAction` is the odd one out and the one that matters most: it is how `KeyHandler` runs
 * the handler it found for a keystroke, and it is pure engine code with no host in it.
 *
 * [known] is the list of command ids this VS Code actually has, which is the only part that needs
 * arranging. There is no synchronous way to ask - `commands.getCommands` returns a promise - so it
 * is fetched once at activation and remembered here. Until it arrives every name is accepted, which
 * is the right way round: a `:action` that runs and fails names the command in its error, and one
 * refused because a list had not loaded yet would be a lie.
 *
 * The other half of the vocabulary is [IdeaActionAliases]: a name this window does not have may
 * still be one IntelliJ does, and an `.ideavimrc` is full of them. See [resolve].
 */
internal class VsCodeActionExecutor(private val host: HostCommandRunner) : VimActionExecutor {

  private var known: Set<String>? = null

  /**
   * What each command is bound to, for `:actionlist`'s second column.
   *
   * Filled a source at a time as activation reads them, and correct in between - see
   * [KeybindingTable]. Empty in a host that never fills it, which is a blank column and not a
   * missing one.
   */
  val keybindings: KeybindingTable = KeybindingTable()

  /** Called once at activation, when VS Code has answered what commands it has. */
  fun remember(ids: Collection<String>) {
    known = ids.toSet()
  }

  /**
   * What a name means: this window's command, IntelliJ's word for one, or neither.
   *
   * This window is asked first. A name it actually has is the name the user meant, and no table
   * should be able to take that away - the two vocabularies do not collide in practice (VS Code's
   * ids are dotted and lowercase, IntelliJ's are not), but the order is what guarantees it rather
   * than the observation.
   *
   * Before the id list arrives the table is still consulted, because `GotoClass` is not going to
   * turn out to be a VS Code command; anything the table has never heard of is passed through, for
   * the same reason [getAction] accepts everything then.
   */
  private fun resolve(name: String): ResolvedAction {
    val id = name.trim()
    if (id.isEmpty()) return ResolvedAction.Unknown

    val ids = known
    if (ids != null && id in ids) return ResolvedAction.Command(id)
    // The extension's own ids, whatever the window has said. These are checked against the real
    // VS Code by their own activation pass, and the engine reaches them for `zo`, `zR` and `gd`
    // through [ACTION_EXPAND_REGION] and its neighbours - a list that had not arrived, or a stub
    // host with a short one, must not be able to turn those off.
    if (id in VsCodeCommands.all) return ResolvedAction.Command(id)

    if (IdeaActionAliases.contains(id)) {
      val command = IdeaActionAliases.commandFor(id)
      return if (command == null) ResolvedAction.IntelliJOnly(id) else ResolvedAction.Command(command)
    }

    return if (ids == null) ResolvedAction.Command(id) else ResolvedAction.Unknown
  }

  private fun report(editor: VimEditor?, ideaId: String) {
    val where = editor ?: injector.fallbackWindow
    injector.messages.showErrorMessage(
      where,
      "$ideaId is one of IntelliJ's actions and this VS Code has nothing that does it.",
    )
  }
  override val ACTION_EDITOR_NEXT_TEMPLATE_VARIABLE: String = ""
  override val ACTION_COLLAPSE_ALL_REGIONS: String = VsCodeCommands.FOLD_ALL
  override val ACTION_COLLAPSE_REGION: String = VsCodeCommands.FOLD
  override val ACTION_COLLAPSE_REGION_RECURSIVELY: String = VsCodeCommands.FOLD_RECURSIVELY
  override val ACTION_EXPAND_ALL_REGIONS: String = VsCodeCommands.UNFOLD_ALL
  override val ACTION_EXPAND_REGION: String = VsCodeCommands.UNFOLD
  override val ACTION_EXPAND_REGION_RECURSIVELY: String = VsCodeCommands.UNFOLD_RECURSIVELY
  override val ACTION_EXPAND_COLLAPSE_TOGGLE: String = VsCodeCommands.TOGGLE_FOLD
  override val ACTION_UNDO: String = VsCodeCommands.UNDO
  override val ACTION_REDO: String = VsCodeCommands.REDO
  override val ACTION_GOTO_DECLARATION: String = VsCodeCommands.REVEAL_DEFINITION

  override fun executeVimAction(
    editor: VimEditor,
    cmd: EditorActionHandlerBase,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ) {
    // IntelliJ wraps this in its CommandProcessor so the change becomes one undoable unit. VS Code
    // groups undo by edit, and the whole command reaches the document as a single edit, so the
    // grouping IntelliJ needs a wrapper for is what this host gets for free.
    cmd.execute(editor, context, operatorArguments)
  }

  override fun executeCommand(editor: VimEditor?, runnable: () -> Unit, name: String?, groupId: Any?) = runnable()

  override fun executeAction(editor: VimEditor?, action: NativeAction, context: ExecutionContext): Boolean =
    executeAction(editor, action)

  /**
   * The host's own actions, of which this host has exactly one - see
   * [VsCodeInjector.nativeActionManager] for why Enter has to be one of them.
   */
  override fun executeAction(editor: VimEditor?, action: NativeAction): Boolean {
    // Before the editor is looked at, because a VS Code command is not addressed to one: it goes to
    // whatever has focus, and `:action` from the fallback window is still a command worth running.
    if (action is HostCommandAction) {
      host.run(action.id)
      return true
    }
    if (action is IntelliJOnlyAction) {
      report(editor, action.ideaId)
      return false
    }
    val vsCode = editor as? VsCodeEditor ?: return false
    return when (action) {
      is InsertNewLineAction -> {
        // Vim's own `o` would indent the new line to match; `'autoindent'` is not wired up yet, so
        // this is the newline and nothing else.
        vsCode.typeAtCarets("\n")
        true
      }

      is DeleteSelectionAction -> {
        vsCode.deleteSelections()
        true
      }

      else -> false
    }
  }

  /**
   * A VS Code command, by name.
   *
   * The engine reaches this for the folds - `za`, `zo`, `zc`, `zR`, `zM` - for `gd` and `<C-]>`,
   * and for whatever a user puts in an `<Action>` mapping. IdeaVim's names are IntelliJ action ids
   * and this host's are VS Code command ids; the engine does not care which, because it asks the
   * host for the names it uses by [ACTION_COLLAPSE_REGION] and the rest.
   *
   * It waits. The result cannot be reported either way - `executeCommand` resolves a promise long
   * after this has had to answer - so `true` here means "dispatched" and nothing more, in the same
   * way `u` reports success it cannot check. But an `<Action>` mapping can name anything, including
   * a reformat, so the conservative half of the decision is the right one: hold the keys.
   */
  override fun executeAction(editor: VimEditor, name: String, context: ExecutionContext): Boolean {
    if (name.isEmpty()) return false
    val resolved = resolve(name)
    if (resolved is ResolvedAction.IntelliJOnly) {
      report(editor, resolved.ideaId)
      return false
    }
    // A name this host cannot place is still sent. The window is the authority on what it has and
    // says so when a command is missing - refusing here instead would mean a stale or unfinished
    // id list could silently disable a mapping that works.
    host.run(if (resolved is ResolvedAction.Command) resolved.id else name.trim())
    return true
  }

  /**
   * Whether the *host* consumed Escape. IntelliJ runs its own Escape action first, so that closing
   * a completion popup does not also leave insert mode. VS Code settles that with `when` clauses in
   * its keybindings rather than by asking an extension, so nothing is consumed here and the engine
   * goes on to treat Escape as Vim's.
   */
  override fun executeEsc(editor: VimEditor, context: ExecutionContext): Boolean = false

  /**
   * A VS Code command, as the `NativeAction` the engine's `:action` asks for.
   *
   * `:action` looks the name up first and reports `Action not found` when this answers null, which
   * is the whole reason this exists rather than dispatching blind: a typo should say so at the `:`
   * prompt rather than half a second later in the output panel.
   *
   * Before the id list has arrived nothing can be ruled out, so nothing is - see [known].
   */
  override fun getAction(actionId: String): NativeAction? = when (val resolved = resolve(actionId)) {
    is ResolvedAction.Command -> HostCommandAction(resolved.id)
    // Found, so that `:action` reports what is actually wrong with it rather than "not found".
    is ResolvedAction.IntelliJOnly -> IntelliJOnlyAction(resolved.ideaId)
    ResolvedAction.Unknown -> null
  }

  /**
   * Every command this VS Code has, for `:actionlist`.
   *
   * Empty until the list has arrived, which `:actionlist` says out loud rather than printing an
   * empty list and letting the reader conclude their editor can do nothing.
   */
  override fun getActionIdList(idPrefix: String): List<String> =
    (known ?: emptySet()).filter { it.startsWith(idPrefix) }.sorted()

  /** Vim's own actions are findable, because the registry holding them is the engine's. */
  override fun findVimAction(id: String): EditorActionHandlerBase? =
    engineCommandProvider.getCommands().firstOrNull { it.actionId == id }?.instance

  override fun findVimActionOrDie(id: String): EditorActionHandlerBase =
    findVimAction(id) ?: error("no Vim action with id $id")
}


/**
 * One VS Code command, named.
 *
 * `NativeAction.action` is `Any` because only the host knows what it holds - IntelliJ puts an
 * `AnAction` in it. This host has nothing to put but the id, since a command is only ever a string
 * until it is executed.
 */
private data class HostCommandAction(val id: String) : NativeAction {
  override val action: Any get() = id
}

/**
 * An IntelliJ action this editor has no version of.
 *
 * A found action rather than a missing one, deliberately. `:action MakeGradleModule` reporting
 * "Action not found" would send the reader looking for a typo; what they need to hear is that the
 * name is right and the feature is IntelliJ's.
 */
private data class IntelliJOnlyAction(val ideaId: String) : NativeAction {
  override val action: Any get() = ideaId
}

/** What a name turned out to mean. See [VsCodeActionExecutor.resolve]. */
private sealed interface ResolvedAction {
  data class Command(val id: String) : ResolvedAction
  data class IntelliJOnly(val ideaId: String) : ResolvedAction
  object Unknown : ResolvedAction
}

/**
 * `:actionlist [pattern]` - every command this VS Code has, filtered.
 *
 * IdeaVim's, in the terms this editor uses. The pattern is split on `*` and every piece has to
 * appear in the line, case-insensitively, so `:actionlist git*commit` finds
 * `git.commitStagedAll` - which is IdeaVim's rule and not a glob, whatever it looks like.
 *
 * The chord bound to each command is printed beside it, as IdeaVim prints IntelliJ's shortcuts.
 * VS Code has no API that answers what is bound, so the three files it builds its keymap out of are
 * read instead and the column is as complete as they are - see [KeybindingTable]. The pattern is
 * matched against the whole line, chord included, so `:actionlist cmd+k` is a question this can
 * answer as well as `:actionlist git`.
 *
 * A host command rather than an engine one, for the same reason as [TutorCommand]: IdeaVim has its
 * own `:actionlist`, in its own module, over `ActionManager`.
 */
data class ActionListCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val ids = injector.actionExecutor.getActionIdList("")
    if (ids.isEmpty()) {
      injector.outputPanel.output(
        editor,
        context,
        "Vimperor has not been told what commands this VS Code has yet, so there is nothing to " +
          "list. It is asked once when the extension activates; reloading the window will ask again.",
      )
      return ExecutionResult.Success
    }

    val keybindings = (injector.actionExecutor as? VsCodeActionExecutor)?.keybindings
    val lines = ids.map { id ->
      val chords = keybindings?.shortcutsFor(id).orEmpty()
      if (chords.isEmpty()) id else id.padEnd(NAME_COLUMN) + " " + chords.joinToString("  ")
    }

    val pattern = argument.trim().lowercase().split("*").filter { it.isNotEmpty() }
    val matching = lines.filter { line -> pattern.all { it in line.lowercase() } }
    val text = buildString {
      appendLine(injector.messages.message("command.action.list.header"))
      matching.forEach { appendLine(it) }
      appendLine("--- ${matching.size} of ${ids.size} ---")
    }
    injector.outputPanel.output(editor, context, text)
    return ExecutionResult.Success
  }

  private companion object {
    /** Where the chords start, which is IdeaVim's column and wide enough for most VS Code ids. */
    const val NAME_COLUMN = 50
  }
}
