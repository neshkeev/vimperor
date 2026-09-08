/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.tags

import com.intellij.vim.annotations.CommandOrMotion
import com.intellij.vim.annotations.Mode
import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getText
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.Command
import com.maddyhome.idea.vim.command.CommandFlags
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.handler.VimActionHandler
import com.maddyhome.idea.vim.helper.enumSetOf

/**
 * `<C-]>` and `<C-T>`, the two keys the tag stack exists for.
 *
 * The stack, the ctags reader and nine ex commands were all here already - `pushStack` in
 * `TagCommands.kt` even says it is there "so that `:pop` and `<C-t>` have somewhere to go back to".
 * Neither key was wired to any of it: `<C-]>` went straight to the host's go-to-declaration without
 * leaving a trail, and `<C-T>` was bound alongside `<C-O>` to the *jump list*, which is a different
 * stack. `:h CTRL-T` is unambiguous that it pops the tag stack.
 */

/**
 * `<C-]>`: jump to the definition of the keyword under the caret, remembering where we came from.
 *
 * The jump itself still goes to the host, which knows more about what the caret is on than Vim's
 * notion of a keyword does. What is new is the stack entry, which is what lets `<C-T>` come back.
 *
 * The entry carries no ctags matches, because this jump did not come from a tags file. That is a
 * real difference from a `:tag` entry rather than a gap: `:tnext` on it says there is nothing to
 * step through, which is true. An entry that claimed matches it does not have would be worse.
 */
@CommandOrMotion(keys = ["<C-]>"], modes = [Mode.NORMAL, Mode.VISUAL])
class TagJumpAction : VimActionHandler.SingleExecution() {
  override val type: Command.Type = Command.Type.OTHER_READONLY

  override val flags: MutableSet<CommandFlags> = enumSetOf(CommandFlags.FLAG_SAVE_JUMP)

  override fun execute(
    editor: VimEditor,
    context: ExecutionContext,
    cmd: Command,
    operatorArguments: OperatorArguments,
  ): Boolean {
    injector.jumpService.saveJumpLocation(editor)

    // No keyword under the caret means no tag, so there is nothing to walk back over either. The
    // jump still happens - the host may recognise something Vim's keyword rules do not.
    val word = injector.searchHelper.findWordAtOrFollowingCursor(editor, editor.currentCaret(), isBigWord = false)
    if (word != null) {
      val at = editor.offsetToBufferPosition(editor.currentCaret().offset)
      Tags.push(
        editor.projectId,
        Tags.StackEntry(
          tagName = editor.getText(word),
          fromPath = editor.getPath() ?: "",
          fromLine = at.line,
          fromColumn = at.column,
          matches = emptyList(),
          index = 0,
        ),
      )
    }

    injector.actionExecutor.executeAction(
      editor,
      name = injector.actionExecutor.ACTION_GOTO_DECLARATION,
      context = context,
    )
    return true
  }
}

/**
 * `<C-T>`: back to where the last tag jump started, [count] entries down the stack.
 *
 * The same thing `:pop` does, and it shares its implementation rather than repeating it. It was
 * bound to the jump list until now, which meant `<C-]>` then `<C-T>` happened to look right - the
 * jump list had an entry too - and diverged the moment anything else moved the caret.
 */
@CommandOrMotion(keys = ["<C-T>"], modes = [Mode.NORMAL])
class TagPopStackAction : VimActionHandler.SingleExecution() {
  override val type: Command.Type = Command.Type.OTHER_READONLY

  override fun execute(
    editor: VimEditor,
    context: ExecutionContext,
    cmd: Command,
    operatorArguments: OperatorArguments,
  ): Boolean {
    val failure = popTagStack(editor, context, operatorArguments.count1)
    if (failure != null) {
      injector.messages.showErrorMessage(editor, injector.messages.message(failure))
      return false
    }
    return true
  }
}

/**
 * Down [count] entries of the tag stack, to where that jump started.
 *
 * Shared by `:pop` and `<C-T>` because they are the same command. Returns a message id rather than
 * throwing, so that an action can beep where an ex command raises: `E555` is "at the bottom of the
 * tag stack".
 *
 * Nothing is thrown away, which is what lets `:tag` with no argument walk back up. See
 * [Tags.pointer].
 */
internal fun popTagStack(editor: VimEditor, context: ExecutionContext, count: Int): String? {
  val pointer = Tags.pointer(editor.projectId)
  if (pointer <= 0) return "E555"

  val target = (pointer - count).coerceAtLeast(0)
  val entry = Tags.stack(editor.projectId)[target]
  Tags.setPointer(editor.projectId, target)

  if (entry.fromPath.isNotEmpty() && entry.fromPath != editor.getPath()) {
    val failure = injector.file.openFile(entry.fromPath, context)
    if (failure != null) {
      injector.messages.showErrorMessage(editor, failure)
      return null
    }
  }
  val landed = injector.editorGroup.getFocusedEditor() ?: editor
  landed.currentCaret().moveToBufferPosition(
    BufferPosition(entry.fromLine.coerceIn(0, (landed.lineCount() - 1).coerceAtLeast(0)), entry.fromColumn),
  )
  injector.scroll.scrollCaretIntoView(landed)
  return null
}
