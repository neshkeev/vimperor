/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.action.CommandProvider
import com.maddyhome.idea.vim.action.change.LazyVimCommand
import com.maddyhome.idea.vim.action.change.VimRepeater
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.Argument
import com.maddyhome.idea.vim.command.Command
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.group.visual.VimSelection
import com.maddyhome.idea.vim.handler.ChangeEditorActionHandler
import com.maddyhome.idea.vim.handler.EditorActionHandlerBase
import com.maddyhome.idea.vim.handler.VimActionHandler
import com.maddyhome.idea.vim.handler.VisualOperatorActionHandler

/**
 * The Vim commands that vim-engine does not declare.
 *
 * Nearly all of Vim lives in the engine - 250 of the 264 actions IdeaVim registers. The remaining
 * fourteen are declared by the IntelliJ plugin, and most of those are IntelliJ furniture: inlays,
 * the plugin toggle, a redraw, `K` for quick documentation, the insert-mode arrow keys that
 * delegate to the IDE's own caret movement. A host that is not IntelliJ has no use for any of it.
 *
 * But four of the fourteen are ordinary Vim, declared there only because IdeaVim's implementations
 * reach for the IDE: `J` and `gJ` consult the `ideajoin` option and can hand the join to IntelliJ's
 * own smart-join, and `.` wraps the replay in an IntelliJ undo group. Strip those away and what is
 * left is engine work that this host can do.
 *
 * They are declared here rather than moved into vim-engine because the IntelliJ versions are not
 * wrong - `ideajoin` is a real feature and belongs where it is. The two hosts genuinely want
 * different code, so they get different code, and each keeps the action id that IdeaVim gave it.
 *
 * `g@` is the fourth, and it is not here: it needs a motion range computed the way the IntelliJ
 * `MotionGroup` computes it, and nothing asks for `g@` until an extension does.
 */
object VsCodeCommandProvider : CommandProvider {
  override fun getCommands(): Collection<LazyVimCommand> = listOf(
    command("J", MappingMode.NORMAL, "DeleteJoinLinesSpacesAction") { DeleteJoinLinesSpacesAction() },
    command("J", MappingMode.VISUAL, "DeleteJoinVisualLinesSpacesAction") { DeleteJoinVisualLinesSpacesAction() },
    command("gJ", MappingMode.NORMAL, "DeleteJoinLinesAction") { DeleteJoinLinesAction() },
    command("gJ", MappingMode.VISUAL, "DeleteJoinVisualLinesAction") { DeleteJoinVisualLinesAction() },
    command(".", MappingMode.NORMAL, "RepeatChangeAction") { RepeatChangeAction() },
  )

  private fun command(
    keys: String,
    mode: MappingMode,
    className: String,
    factory: () -> EditorActionHandlerBase,
  ) = LazyVimCommand(setOf(injector.parser.parseKeys(keys)), setOf(mode), className, factory)
}

/**
 * `J` - join lines, replacing the join with a single space and dropping the next line's indent.
 *
 * IdeaVim asks `ideajoin` first, and if it is set hands the whole thing to IntelliJ, which knows
 * how to join a string literal or an `if` with a single statement. There is no such service here,
 * so this is always Vim's own join.
 */
internal class DeleteJoinLinesSpacesAction : ChangeEditorActionHandler.SingleExecution() {
  override val type: Command.Type = Command.Type.DELETE

  override fun execute(
    editor: VimEditor,
    context: ExecutionContext,
    argument: Argument?,
    operatorArguments: OperatorArguments,
  ): Boolean {
    if (editor.isOneLineMode()) return false
    var res = true
    // Descending, so that joining one caret's lines does not move the offsets the next caret holds.
    editor.nativeCarets().sortedByDescending { it.offset }.forEach { caret ->
      if (!injector.changeGroup.deleteJoinLines(editor, context, caret, operatorArguments.count1, true)) {
        res = false
      }
    }
    return res
  }
}

/** `gJ` - join lines exactly as they are, without adding or removing a single space. */
internal class DeleteJoinLinesAction : ChangeEditorActionHandler.SingleExecution() {
  override val type: Command.Type = Command.Type.DELETE

  override fun execute(
    editor: VimEditor,
    context: ExecutionContext,
    argument: Argument?,
    operatorArguments: OperatorArguments,
  ): Boolean {
    if (editor.isOneLineMode()) return false
    var res = true
    editor.nativeCarets().sortedByDescending { it.offset }.forEach { caret ->
      if (!injector.changeGroup.deleteJoinLines(editor, context, caret, operatorArguments.count1, false)) {
        res = false
      }
    }
    return res
  }
}

/** `J` over a visual selection: join every line the selection touches. */
internal class DeleteJoinVisualLinesSpacesAction : VisualOperatorActionHandler.SingleExecution() {
  override val type: Command.Type = Command.Type.DELETE

  override fun executeForAllCarets(
    editor: VimEditor,
    context: ExecutionContext,
    cmd: Command,
    caretsAndSelections: Map<VimCaret, VimSelection>,
    operatorArguments: OperatorArguments,
  ): Boolean = joinSelections(editor, context, caretsAndSelections, operatorArguments, spaces = true)
}

/** `gJ` over a visual selection. */
internal class DeleteJoinVisualLinesAction : VisualOperatorActionHandler.SingleExecution() {
  override val type: Command.Type = Command.Type.DELETE

  override fun executeForAllCarets(
    editor: VimEditor,
    context: ExecutionContext,
    cmd: Command,
    caretsAndSelections: Map<VimCaret, VimSelection>,
    operatorArguments: OperatorArguments,
  ): Boolean = joinSelections(editor, context, caretsAndSelections, operatorArguments, spaces = false)
}

private fun joinSelections(
  editor: VimEditor,
  context: ExecutionContext,
  caretsAndSelections: Map<VimCaret, VimSelection>,
  operatorArguments: OperatorArguments,
  spaces: Boolean,
): Boolean {
  if (editor.isOneLineMode()) return false
  var res = true
  editor.nativeCarets().sortedByDescending { it.offset }.forEach { caret ->
    if (!caret.isValid) return@forEach
    val range = caretsAndSelections[caret] ?: return@forEach
    val joined = injector.changeGroup.deleteJoinRange(
      editor,
      context,
      caret,
      range.toVimTextRange(true).normalize(),
      spaces,
      operatorArguments,
    )
    if (!joined) res = false
  }
  return res
}

/**
 * `.` - do the last change again.
 *
 * The repeat is not a replay of keystrokes: the engine kept the last change as a [Command], and
 * running it again means handing that same command back to the action executor. What surrounds
 * that is bookkeeping - the register the change used, the last `f`/`t` search, and the flag that
 * tells the rest of the engine a dot repeat is in progress, which several handlers read to avoid
 * asking the user for input a second time.
 *
 * IdeaVim also wraps the replay in a single IntelliJ undo group, because a `c`-style change is a
 * delete and an insert and the platform would otherwise record two undo steps. This host's undo is
 * VS Code's, driven through [HostCommandRunner], and the flush turns the whole replay into one
 * document edit already - so there is nothing here to group.
 */
internal class RepeatChangeAction : VimActionHandler.SingleExecution() {
  override val type: Command.Type = Command.Type.OTHER_WRITABLE

  override fun execute(
    editor: VimEditor,
    context: ExecutionContext,
    cmd: Command,
    operatorArguments: OperatorArguments,
  ): Boolean {
    val state = injector.vimState
    var lastCommand = VimRepeater.lastChangeCommand ?: return false

    val save = state.executingCommand
    val lastFTCmd = injector.motion.lastFTCmd
    val lastFTChar = injector.motion.lastFTChar
    val reg = injector.registerGroup.currentRegister

    state.isDotRepeatInProgress = true
    try {
      // The redo-register feature: repeating a change that came from a numbered register walks to
      // the next one, so `"1p....` pastes the last five deletes in turn. See `:h redo-register`.
      if (VimRepeater.lastChangeRegister in '1'..'8') {
        VimRepeater.lastChangeRegister = VimRepeater.lastChangeRegister.inc()
      }
      injector.registerGroup.selectRegister(VimRepeater.lastChangeRegister)

      // A count on the `.` itself replaces the count the original change carried, rather than
      // multiplying it: `3dw` then `2.` deletes two words, not six.
      if (cmd.rawCount > 0) {
        lastCommand = lastCommand.copy(rawCount = cmd.rawCount)
      }
      state.executingCommand = lastCommand

      val arguments = operatorArguments.copy(count0 = lastCommand.rawCount)
      injector.actionExecutor.executeVimAction(editor, lastCommand.action, context, arguments)

      VimRepeater.saveLastChange(lastCommand)
    } finally {
      state.isDotRepeatInProgress = false
      if (save != null) state.executingCommand = save
      injector.motion.setLastFTCmd(lastFTCmd, lastFTChar)
      injector.registerGroup.selectRegister(reg)
    }
    return true
  }
}
