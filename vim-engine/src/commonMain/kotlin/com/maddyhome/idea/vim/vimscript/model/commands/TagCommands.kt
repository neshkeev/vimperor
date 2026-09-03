/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.tags.Tags
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * The tag commands, over a real tags file. See [Tags] for why it is a file and not a symbol search.
 *
 * The whole family is here because they are one feature: a list of matches, a place in that list,
 * and a stack of the places you jumped from. `:tag` makes a list, `:tnext` and its relatives walk
 * the one that is current, `:pop` goes back down the stack and `:tags` prints it.
 *
 * **`:tselect` prints its list instead of prompting for a number.** Vim shows the matches and waits
 * at a `Type number and <Enter>` prompt; this host draws its output in a panel and its prompt on
 * the status bar, and a prompt over a list the reader cannot see while answering is worse than no
 * prompt. So the list is printed, numbered as Vim numbers it, and `:tnext`, `:tfirst` and `:tlast`
 * walk it - which is the same journey by a different door. `:copen` diverges the same way and for
 * the same reason.
 */
sealed class TagCommandBase(
  range: Range,
  modifier: CommandModifier,
  argument: String,
) : Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  /**
   * Goes to a match, opening its file if it is not the one on screen.
   *
   * The address is resolved here rather than when the tags file was read, because resolving it
   * means reading the file it names - and `:tselect` over forty matches would read forty files to
   * print a list nobody has chosen from yet.
   */
  protected fun jumpTo(
    editor: VimEditor,
    context: ExecutionContext,
    match: Tags.TagMatch,
    position: String,
  ): ExecutionResult {
    val failure = injector.file.openFile(match.path, context)
    if (failure != null) {
      injector.messages.showErrorMessage(editor, failure)
      return ExecutionResult.Error
    }

    // The editor that has focus now is the one the file went into. Falling back to the one the
    // command was typed in covers a host that opens asynchronously, the way the quickfix list does.
    val target = injector.editorGroup.getFocusedEditor() ?: editor
    val line = Tags.lineFor(match, target.text().toString())
      ?: match.address.toIntOrNull()?.minus(1)
      ?: 0

    target.currentCaret().moveToBufferPosition(
      BufferPosition(line.coerceIn(0, (target.lineCount() - 1).coerceAtLeast(0)), 0),
    )
    injector.scroll.scrollCaretIntoView(target)
    injector.messages.showStatusBarMessage(target, "$position  ${match.name}")
    return ExecutionResult.Success
  }

  /** Pushes where we are now, so that `:pop` and `<C-t>` have somewhere to go back to. */
  protected fun pushStack(editor: VimEditor, name: String, matches: List<Tags.TagMatch>, index: Int) {
    val at = editor.offsetToBufferPosition(editor.currentCaret().offset)
    Tags.push(
      editor.projectId,
      Tags.StackEntry(
        tagName = name,
        fromPath = editor.getPath() ?: "",
        fromLine = at.line,
        fromColumn = at.column,
        matches = matches,
        index = index,
      ),
    )
  }

  /** Vim's table of matches, which `:tselect` shows and `:tjump` shows when it cannot choose. */
  protected fun printMatches(
    editor: VimEditor,
    context: ExecutionContext,
    matches: List<Tags.TagMatch>,
    current: Int,
  ) {
    val text = buildString {
      appendLine("  # pri kind tag                file")
      matches.forEachIndexed { position, match ->
        append(if (position == current) ">" else " ")
        append((position + 1).toString().padStart(3))
        append("  F  ")
        append((match.kind ?: " ").padEnd(5))
        append(match.name.padEnd(19))
        append(" ")
        append(match.path)
        appendLine()
        appendLine("               ${match.address}")
      }
    }
    injector.outputPanel.output(editor, context, text)
  }

  /** The list `:tnext` and its relatives walk, or `E73` when nothing has been jumped to. */
  protected fun currentEntry(editor: VimEditor): Tags.StackEntry =
    Tags.current(editor.projectId) ?: throw exExceptionMessage("E73")
}

/**
 * `:tag[!] [name]` - jump to a tag, or back up the stack when given nothing.
 *
 * Two commands wearing one name, which is Vim's doing: with an argument it finds a tag and pushes
 * the stack, and without one it walks *up* the stack it has already walked down, which is the other
 * half of `:pop`.
 *
 * see "h :tag"
 */
@ExCommand(command = "ta[g]")
data class TagCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  TagCommandBase(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val name = commandArgument.trim()
    if (name.isEmpty()) return goUpTheStack(editor, context, operatorArguments.count1)

    val matches = Tags.find(name, editor, context)
    if (matches.isEmpty()) throw exExceptionMessage("E426", name)

    pushStack(editor, name, matches, 0)
    return jumpTo(editor, context, matches.first(), "tag 1 of ${matches.size}")
  }

  private fun goUpTheStack(editor: VimEditor, context: ExecutionContext, count: Int): ExecutionResult {
    val stack = Tags.stack(editor.projectId)
    val pointer = Tags.pointer(editor.projectId)
    if (pointer >= stack.size) throw exExceptionMessage("E556")

    val target = (pointer + count).coerceAtMost(stack.size)
    Tags.setPointer(editor.projectId, target)
    val entry = stack[target - 1]
    return jumpTo(editor, context, entry.matches[entry.index], "tag ${entry.index + 1} of ${entry.matches.size}")
  }
}

/**
 * `:pop[!] [count]` - back to where the tag was jumped from.
 *
 * The entries are not thrown away, which is what lets `:tag` with no argument walk back up. See
 * [Tags.pointer].
 *
 * see "h :pop"
 */
@ExCommand(command = "po[p]")
data class TagPopCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  TagCommandBase(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val pointer = Tags.pointer(editor.projectId)
    if (pointer <= 0) throw exExceptionMessage("E555")

    val target = (pointer - operatorArguments.count1).coerceAtLeast(0)
    val entry = Tags.stack(editor.projectId)[target]
    Tags.setPointer(editor.projectId, target)

    if (entry.fromPath.isNotEmpty() && entry.fromPath != editor.getPath()) {
      val failure = injector.file.openFile(entry.fromPath, context)
      if (failure != null) {
        injector.messages.showErrorMessage(editor, failure)
        return ExecutionResult.Error
      }
    }
    val target2 = injector.editorGroup.getFocusedEditor() ?: editor
    target2.currentCaret().moveToBufferPosition(
      BufferPosition(entry.fromLine.coerceIn(0, (target2.lineCount() - 1).coerceAtLeast(0)), entry.fromColumn),
    )
    injector.scroll.scrollCaretIntoView(target2)
    return ExecutionResult.Success
  }
}

/**
 * `:tags` - the stack, printed.
 *
 * see "h :tags"
 */
@ExCommand(command = "tags")
data class TagStackCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  TagCommandBase(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val stack = Tags.stack(editor.projectId)
    val pointer = Tags.pointer(editor.projectId)

    val text = buildString {
      appendLine("  # TO tag         FROM line  in file/text")
      stack.forEachIndexed { position, entry ->
        append(if (position == pointer - 1) ">" else " ")
        append((position + 1).toString().padStart(3))
        append(" ")
        append((entry.index + 1).toString().padStart(2))
        append(" ")
        append(entry.tagName.padEnd(12))
        append((entry.fromLine + 1).toString().padStart(5))
        append("  ")
        append(entry.fromPath)
        appendLine()
      }
      // Vim's marker for "at the top of the stack", which is where a session that has not popped
      // always sits - and the only way to tell that from being on the newest entry.
      if (pointer >= stack.size) appendLine(">")
    }
    injector.outputPanel.output(editor, context, text)
    return ExecutionResult.Success
  }
}

/**
 * `:tselect[!] [name]` and `:tjump[!] [name]`.
 *
 * The difference in Vim is what happens when there is exactly one match: `:tjump` goes there and
 * `:tselect` still shows a list of one. That difference survives here; what does not is the prompt.
 * See [TagCommandBase].
 */
sealed class TagSelectCommandBase(
  range: Range,
  modifier: CommandModifier,
  argument: String,
  private val jumpsWhenAlone: Boolean,
) : TagCommandBase(range, modifier, argument) {

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val name = commandArgument.trim()
    val matches = if (name.isEmpty()) {
      currentEntry(editor).matches
    } else {
      Tags.find(name, editor, context).ifEmpty { throw exExceptionMessage("E426", name) }
    }

    if (name.isNotEmpty()) pushStack(editor, name, matches, 0)

    if (jumpsWhenAlone && matches.size == 1) {
      return jumpTo(editor, context, matches.first(), "tag 1 of 1")
    }
    printMatches(editor, context, matches, Tags.current(editor.projectId)?.index ?: 0)
    return ExecutionResult.Success
  }
}

/** see "h :tselect" */
@ExCommand(command = "ts[elect]")
data class TagSelectCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  TagSelectCommandBase(range, modifier, argument, jumpsWhenAlone = false)

/** see "h :tjump" */
@ExCommand(command = "tj[ump]")
data class TagJumpCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  TagSelectCommandBase(range, modifier, argument, jumpsWhenAlone = true)

/**
 * `:tnext`, `:tprevious`, `:tfirst` and `:tlast` - walking the matches of the current tag.
 *
 * The list belongs to the stack entry rather than to the session, so walking after a `:pop` walks
 * the list of the tag you popped back into. That is Vim's, and it is the reason a stack entry holds
 * the matches instead of just the one it went to.
 */
sealed class TagStepCommand(
  range: Range,
  modifier: CommandModifier,
  argument: String,
  private val step: Step,
) : TagCommandBase(range, modifier, argument) {

  protected enum class Step { NEXT, PREVIOUS, FIRST, LAST }

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val entry = currentEntry(editor)
    val count = operatorArguments.count1

    val target = when (step) {
      Step.NEXT -> entry.index + count
      Step.PREVIOUS -> entry.index - count
      Step.FIRST -> 0
      Step.LAST -> entry.matches.size - 1
    }

    // Vim distinguishes the two ends, and the messages are worth keeping apart: one says you are
    // at the first match and the other that there is only one.
    if (target < 0) throw exExceptionMessage(if (entry.matches.size == 1) "E427" else "E425")
    if (target >= entry.matches.size) throw exExceptionMessage(if (entry.matches.size == 1) "E427" else "E428")

    entry.index = target
    return jumpTo(editor, context, entry.matches[target], "tag ${target + 1} of ${entry.matches.size}")
  }
}

/** see "h :tnext" */
@ExCommand(command = "tn[ext]")
data class TagNextCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  TagStepCommand(range, modifier, argument, Step.NEXT)

/** see "h :tprevious" */
@ExCommand(command = "tp[revious],tN[ext]")
data class TagPreviousCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  TagStepCommand(range, modifier, argument, Step.PREVIOUS)

/** see "h :tfirst" */
@ExCommand(command = "tf[irst],tr[ewind]")
data class TagFirstCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  TagStepCommand(range, modifier, argument, Step.FIRST)

/** see "h :tlast" */
@ExCommand(command = "tl[ast]")
data class TagLastCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  TagStepCommand(range, modifier, argument, Step.LAST)
