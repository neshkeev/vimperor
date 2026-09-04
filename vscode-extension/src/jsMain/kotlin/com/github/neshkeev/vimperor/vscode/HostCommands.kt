/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.CommandProvider
import com.maddyhome.idea.vim.action.change.LazyVimCommand
import com.maddyhome.idea.vim.action.change.VimRepeater
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.Options
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.setChangeMarks
import com.maddyhome.idea.vim.command.Argument
import com.maddyhome.idea.vim.command.Command
import com.maddyhome.idea.vim.command.CommandFlags
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.common.argumentCaptured
import com.maddyhome.idea.vim.ex.ExException
import com.maddyhome.idea.vim.group.visual.VimSelection
import com.maddyhome.idea.vim.handler.ChangeEditorActionHandler
import com.maddyhome.idea.vim.handler.EditorActionHandlerBase
import com.maddyhome.idea.vim.handler.Motion
import com.maddyhome.idea.vim.handler.VimActionHandler
import com.maddyhome.idea.vim.handler.VisualOperatorActionHandler
import com.maddyhome.idea.vim.helper.enumSetOf
import com.maddyhome.idea.vim.helper.inRepeatMode
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.expressions.SimpleExpression
import com.maddyhome.idea.vim.vimscript.model.functions.toVimFuncref

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
 * `g@` is the fourth, and it is here for a different reason: IdeaVim's version reaches for the IDE
 * only to compute the motion's range, and the engine can compute that itself.
 */
object VsCodeCommandProvider : CommandProvider {
  override fun getCommands(): Collection<LazyVimCommand> = listOf(
    command("J", MappingMode.NORMAL, "DeleteJoinLinesSpacesAction") { DeleteJoinLinesSpacesAction() },
    command("J", MappingMode.VISUAL, "DeleteJoinVisualLinesSpacesAction") { DeleteJoinVisualLinesSpacesAction() },
    command("gJ", MappingMode.NORMAL, "DeleteJoinLinesAction") { DeleteJoinLinesAction() },
    command("gJ", MappingMode.VISUAL, "DeleteJoinVisualLinesAction") { DeleteJoinVisualLinesAction() },
    command(".", MappingMode.NORMAL, "RepeatChangeAction") { RepeatChangeAction() },
    command("g@", MappingMode.NORMAL, "OperatorAction") { OperatorAction() },
    command("g@", MappingMode.VISUAL, "VisualOperatorAction") { VisualOperatorAction() },
    command("<Del>", MappingMode.INSERT, "VimEditorDelete") { VimEditorDelete() },
    command("<Tab>", MappingMode.INSERT, "VimEditorTab") { VimEditorTab() },
    command("<Up>", MappingMode.INSERT, "VimEditorUp") { VimEditorUp() },
    command("<kUp>", MappingMode.INSERT, "VimEditorUp") { VimEditorUp() },
    command("<Down>", MappingMode.INSERT, "VimEditorDown") { VimEditorDown() },
    command("<kDown>", MappingMode.INSERT, "VimEditorDown") { VimEditorDown() },
  )

  private fun command(
    keys: String,
    mode: MappingMode,
    className: String,
    factory: () -> EditorActionHandlerBase,
  ) = LazyVimCommand(setOf(injector.parser.parseKeys(keys)), setOf(mode), className, factory)
}

/**
 * Insert mode's remaining keys.
 *
 * `package.json` binds Escape, Backspace, Delete, Enter, Tab, the four arrows and `<C-R>`, because
 * a key VS Code handles itself never reaches the engine. That makes an unbound-in-Vim key worse
 * than an unbound-in-VS-Code one: the extension has taken the key away from VS Code and has nothing
 * to do with it, so it does nothing at all. Backspace, the horizontal arrows and `<C-R>` are the
 * engine's. Delete, Tab and the vertical arrows were IntelliJ's, and so were nobody's here.
 *
 * IdeaVim hands all three to the IDE - `EditorDelete`, `EditorTab`, `EditorMoveCaretUp` - which is
 * how Tab picks up the project's indent settings and how Up leaves a live template alone. Every
 * VS Code equivalent is an asynchronous command, and these are keys pressed in the middle of
 * typing, so they are done here instead, in the buffer, synchronously.
 *
 * Tab used to be the cost of that: it inserted a literal tab where VS Code's own `tab` command
 * would have used the file's indentation. It reads `editor.options` now, so it indents the way the
 * file does. What is still lost is what VS Code's command does *besides* indent - expanding a
 * snippet, accepting a suggestion - and the answer to that is not to reimplement it but to publish
 * the mode as a context key and let `package.json` stop claiming Tab in Insert mode, the way it
 * already stops claiming it for ghost text.
 */
internal class VimEditorDelete : ChangeEditorActionHandler.SingleExecution() {
  override val type: Command.Type = Command.Type.DELETE

  override fun execute(
    editor: VimEditor,
    context: ExecutionContext,
    argument: Argument?,
    operatorArguments: OperatorArguments,
  ): Boolean {
    var deleted = false
    editor.nativeCarets().sortedByDescending { it.offset }.forEach { caret ->
      val offset = caret.offset
      // Deleting the line break is how Vim's Insert-mode Delete joins the next line onto this one,
      // so this deliberately does not stop at the end of a line - only at the end of the file.
      if (offset < editor.fileSize().toInt()) {
        editor.deleteString(TextRange(offset, offset + 1))
        deleted = true
      }
    }
    return deleted
  }
}

/**
 * `<Tab>` in Insert mode: whitespace up to the next tab stop, the way this file writes it.
 *
 * Vim would consult `'expandtab'`, `'tabstop'` and `'softtabstop'`; the engine has none of those,
 * because IdeaVim asks the IDE and so this asks VS Code. See [VimEditorDelete] for why it is not
 * VS Code's own `tab` command.
 *
 * A tab stop is a column and not a width, so this fills the distance to the next one rather than
 * inserting a fixed number of spaces - two characters into a four-wide file, Tab inserts two.
 *
 * The column is the primary caret's, and every caret gets the same string. That is right where
 * more than one caret comes from, which here is only a blockwise insert: those carets are in the
 * same column by construction.
 */
internal class VimEditorTab : ChangeEditorActionHandler.SingleExecution() {
  override val type: Command.Type = Command.Type.INSERT
  override val flags: MutableSet<CommandFlags> = enumSetOf(CommandFlags.FLAG_SAVE_STROKE)

  override fun execute(
    editor: VimEditor,
    context: ExecutionContext,
    argument: Argument?,
    operatorArguments: OperatorArguments,
  ): Boolean {
    val indent = editor.indentConfig as VsCodeIndentConfig
    val column = editor.offsetToBufferPosition(editor.primaryCaret().offset).column
    // A tab character *is* the jump to the next stop, so it is one character however far it goes.
    val text = if (indent.usesTabs) "\t" else " ".repeat(indent.toNextTabStop(column))
    injector.changeGroup.type(editor, context, text)
    return true
  }
}

/** `<Up>` in Insert mode. */
internal class VimEditorUp : ChangeEditorActionHandler.SingleExecution() {
  override val type: Command.Type = Command.Type.MOTION
  override val flags: MutableSet<CommandFlags> = enumSetOf(CommandFlags.FLAG_CLEAR_STROKES)

  override fun execute(
    editor: VimEditor,
    context: ExecutionContext,
    argument: Argument?,
    operatorArguments: OperatorArguments,
  ): Boolean = moveVertically(editor, -operatorArguments.count1)
}

/** `<Down>` in Insert mode. */
internal class VimEditorDown : ChangeEditorActionHandler.SingleExecution() {
  override val type: Command.Type = Command.Type.MOTION
  override val flags: MutableSet<CommandFlags> = enumSetOf(CommandFlags.FLAG_CLEAR_STROKES)

  override fun execute(
    editor: VimEditor,
    context: ExecutionContext,
    argument: Argument?,
    operatorArguments: OperatorArguments,
  ): Boolean = moveVertically(editor, operatorArguments.count1)
}

/**
 * A line up or down, keeping the column.
 *
 * `getVerticalMotionOffset` is the same call `j` and `k` make, and it answers with the column the
 * caret was *aiming* for as well as the offset it could actually reach - which is what makes a run
 * of `<Down>` through a short line come back out at the original column rather than at the short
 * line's end.
 */
private fun moveVertically(editor: VimEditor, count: Int): Boolean {
  editor.nativeCarets().forEach { caret ->
    when (val motion = injector.motion.getVerticalMotionOffset(editor, caret, count)) {
      is Motion.AdjustedOffset -> {
        caret.vimLastColumn = motion.intendedColumn
        caret.moveToOffset(motion.offset)
      }

      is Motion.AbsoluteOffset -> caret.moveToOffset(motion.offset)
      else -> {}
    }
  }
  return true
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

/**
 * `g@` - run the function named by 'operatorfunc' over the text a motion covers.
 *
 * Every other operator in Vim is a fixed pairing of a verb and a range. `g@` unbundles them: the
 * plugin supplies the verb, and Vim's own motions, text objects, counts and `.` supply everything
 * else. It is what every operator-pending plugin is built on, and it costs this host almost
 * nothing, because 'operatorfunc' is an engine option and the function it names is run by the
 * engine's own Vimscript interpreter. The only thing IdeaVim reaches into IntelliJ for is the
 * range - and the engine can work that out too.
 *
 * Which is the one place this deliberately parts company with IdeaVim. IdeaVim computes the range
 * with `MotionGroup.getMotionRange2`, a near-copy of the engine's [VimMotionGroup.getMotionRange]
 * that leaves out the linewise normalisation, so that after `g@j` the mark `'[` keeps the caret's
 * original column instead of moving to the start of the line. That is done for the sake of where
 * Commentary leaves the caret; Vim itself puts `'[` in column 1 for a linewise operator. This host
 * has no Commentary to keep happy, so it follows Vim and uses the engine's range unchanged.
 */
private fun doOperatorAction(
  editor: VimEditor,
  context: ExecutionContext,
  textRange: TextRange,
  motionType: SelectionType,
): Boolean {
  val operatorfunc = injector.optionGroup.getOptionValue(Options.operatorfunc, OptionAccessScope.GLOBAL(editor))
  if (operatorfunc.value.isEmpty()) {
    injector.messages.showErrorMessage(editor, injector.messages.message("E774"))
    return false
  }

  val scriptContext = CommandLineVimLContext

  return try {
    val funcref = operatorfunc.toVimFuncref(editor, context, scriptContext)

    val arg = when (motionType) {
      SelectionType.LINE_WISE -> "line"
      SelectionType.CHARACTER_WISE -> "char"
      SelectionType.BLOCK_WISE -> "block"
    }

    // The function is free to make a change of its own, and a change made inside it must not be
    // mistaken for the `g@` itself when `.` comes looking for something to repeat.
    val savedRepeatHandler = VimRepeater.repeatHandler
    injector.markService.setChangeMarks(editor.primaryCaret(), textRange)
    // The operator has finished; anything still pending belongs to whatever the function types.
    KeyHandler.getInstance().reset(editor)

    funcref.execute(listOf(SimpleExpression(arg)), range = null, editor, context, scriptContext)

    VimRepeater.repeatHandler = savedRepeatHandler
    true
  } catch (e: ExException) {
    injector.messages.showErrorMessage(editor, e.message)
    false
  }
}

internal class OperatorAction : VimActionHandler.SingleExecution() {
  override val type: Command.Type = Command.Type.OTHER_SELF_SYNCHRONIZED

  override val argumentType: Argument.Type = Argument.Type.MOTION

  override fun execute(
    editor: VimEditor,
    context: ExecutionContext,
    cmd: Command,
    operatorArguments: OperatorArguments,
  ): Boolean {
    val argument = cmd.argument as? Argument.Motion ?: return false
    // `.` replays the motion rather than re-reading it, so the captured argument is what makes
    // `g@` repeatable at all - but a replay must not overwrite the argument it is replaying.
    if (!editor.inRepeatMode) {
      argumentCaptured = argument
    }
    val range = injector.motion.getMotionRange(
      editor,
      editor.primaryCaret(),
      context,
      argument,
      operatorArguments,
      expandCollapsedFolds = false,
    ) ?: return false
    return doOperatorAction(editor, context, range, argument.getMotionType())
  }
}

internal class VisualOperatorAction : VisualOperatorActionHandler.ForEachCaret() {
  override val type: Command.Type = Command.Type.OTHER_SELF_SYNCHRONIZED

  override fun executeAction(
    editor: VimEditor,
    caret: VimCaret,
    context: ExecutionContext,
    cmd: Command,
    range: VimSelection,
    operatorArguments: OperatorArguments,
  ): Boolean {
    return doOperatorAction(editor, context, range.toVimTextRange(), range.type)
  }
}
