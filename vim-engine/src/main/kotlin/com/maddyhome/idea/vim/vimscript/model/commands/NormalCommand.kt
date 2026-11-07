/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.key.KeySource
import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

// todo make it for each caret
@ExCommand(
  command = "norm[al]",
  barSeparates = false,
  description = "Execute {commands} as Normal mode keys, once for each line in [range]. With [!], mappings are not used.",
)
data class NormalCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags = flags(
    RangeFlag.RANGE_OPTIONAL,
    ArgumentFlag.ARGUMENT_OPTIONAL,
    Access.WRITABLE,
  )

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val modeBefore = editor.mode
    if (modeBefore.isInsertOrReplace()) {
      executeFromInsertMode(editor, context, modeBefore)
    } else {
      executeOnEachLine(editor, context)
    }
    return ExecutionResult.Success
  }

  /**
   * From Insert or Replace the keys run once, in Normal mode, and that mode is back when they are done.
   *
   * It is back even when the line is past the end of the buffer and the keys never run. The old loop restored the
   * mode only after the line check, so the `break` left Normal mode behind.
   */
  private fun executeFromInsertMode(editor: VimEditor, context: ExecutionContext, insertMode: Mode) {
    editor.mode = Mode.NORMAL()
    val line = getLineRange(editor, editor.primaryCaret()).startLine
    // 0-based, and not clamped. Past the end is `line > lineCount()`, the old `lineCount() < line`.
    // `line >= lineCount()` is a different test: line `lineCount()` itself still runs.
    if (line <= editor.lineCount()) {
      executeOnLine(editor, context, line)
      closeCommandLine(editor)
    }
    editor.mode = insertMode
  }

  private fun executeOnEachLine(editor: VimEditor, context: ExecutionContext) {
    val lines = getLineRange(editor, editor.primaryCaret())
    for (line in lines.startLine..lines.endLine) {
      // The keys can delete lines, so the end of the buffer is checked again for every line.
      if (line > editor.lineCount()) break
      executeOnLine(editor, context, line)
      abandonUnfinishedCommand(editor, context)
    }
  }

  private fun executeOnLine(editor: VimEditor, context: ExecutionContext, line: Int) {
    // A range moves the caret to the start of the line. Without one, the caret stays where it is.
    if (isRangeGiven) {
      editor.currentCaret().moveToOffset(injector.motion.moveCaretToLineStart(editor, line))
    }
    typeKeys(editor, context)
  }

  private fun typeKeys(editor: VimEditor, context: ExecutionContext) {
    val keys = injector.parser.stringToKeys(argument)
    val keyHandler = KeyHandler.getInstance()
    keyHandler.reset(editor)
    for (key in keys) {
      val keySource =
        if (modifier == CommandModifier.BANG) KeySource.NORMAL_COMMAND_NOT_MAPPED else KeySource.NORMAL_COMMAND
      keyHandler.handleKey(editor, key, keySource, context, keyHandler.keyHandlerState)
    }
  }

  /** Keys that end in Insert or on the command line leave a command unfinished, and it is abandoned. */
  private fun abandonUnfinishedCommand(editor: VimEditor, context: ExecutionContext) {
    when {
      editor.mode is Mode.CMD_LINE -> closeCommandLine(editor)
      editor.mode.isInsertOrReplace() -> editor.exitInsertMode(context)
    }
  }

  private fun closeCommandLine(editor: VimEditor) {
    if (editor.mode is Mode.CMD_LINE) {
      injector.commandLine.getActiveCommandLine()?.close(refocusOwningEditor = true, resetCaret = false)
    }
  }

  private val isRangeGiven: Boolean
    get() = range.size() != 0

  private fun Mode.isInsertOrReplace() = this is Mode.INSERT || this is Mode.REPLACE
}
