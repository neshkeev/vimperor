/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.changelist.VimChangeList
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.helper.EngineStringHelper
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import kotlin.math.absoluteValue

/**
 * `:changes` - the change list, printed the way `:jumps` prints the jump list.
 *
 * Deliberately the same shape as [JumpsCommand], because Vim's two are the same table with a
 * different first column and a reader who has seen one should not have to learn the other. The
 * number counts away from where `g;` has walked to, so `1` is one `g;` from here in both.
 *
 * The text column is the line as it is *now*, not as it was when the change was made - which is
 * what Vim shows too, since it holds positions rather than snapshots. A position in another file
 * shows the file's name instead, for the same reason `:jumps` does.
 *
 * See "h :changes".
 */
@ExCommand(command = "changes")
data class ChangesCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_FORBIDDEN, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val changes = VimChangeList.getChanges(editor.projectId)
    val index = VimChangeList.getIndex(editor.projectId)

    val text = StringBuilder("change line  col text\n")
    changes.forEachIndexed { position, change ->
      val distance = position - index
      text.append(if (distance == 0) ">" else " ")
      text.append(distance.absoluteValue.toString().padStart(5))
      text.append(" ")
      text.append((change.line + 1).toString().padStart(4))
      text.append("  ")
      text.append(change.col.toString().padStart(3))
      text.append(" ")

      if (editor.getPath() == change.filepath && change.line < editor.lineCount()) {
        val line = editor.getLineText(change.line).trim().take(200)
        text.append(EngineStringHelper.toPrintableCharacters(injector.parser.stringToKeys(line)).take(200))
      } else {
        text.append(change.filepath)
      }
      text.append("\n")
    }

    // Vim marks the position past the newest with a bare `>` on its own line, which is where a
    // list nobody has walked always sits. Without it there is no way to tell "not walking" from
    // "on the newest entry", and those behave differently under the next `g;`.
    if (index >= changes.size) text.append(">\n")

    injector.outputPanel.output(editor, context, text.toString())
    return ExecutionResult.Success
  }
}
