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
import com.maddyhome.idea.vim.api.MutableVimEditor
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.diagnostic.vimLogger
import com.maddyhome.idea.vim.ex.ExException
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.ex.ranges.toTextRange
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * see "h :!"
 *
 * `:!cmd` runs a shell command and shows its output; `:{range}!cmd` filters those lines through it,
 * which is how `:%!sort` and `:%!jq .` work.
 *
 * This lived in the IntelliJ module until now, which meant this port did not have it and no sweep
 * could say so - the ex command sweep walks the *engine's* registry, and a command that was never
 * in it is not a hole, it is an absence. Nothing here is IntelliJ-shaped: it is a range, a string,
 * and a process.
 */
@ExCommand(command = "!")
data class CmdFilterCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier) {

  override val argFlags = flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.SELF_SYNCHRONIZED)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    logger.debug("execute")
    val command = buildString {
      var inBackslash = false
      argument.forEach { c ->
        when {
          !inBackslash && c == '!' -> {
            val last = lastCommand
            if (last.isNullOrEmpty()) {
              injector.messages.showErrorMessage(editor, injector.messages.message("E34"))
              return ExecutionResult.Error
            }
            append(last)
          }

          !inBackslash && c == '%' -> {
            val path = editor.getVirtualFile()?.path
            if (path == null) {
              // A slightly different message from Vim's, because this does not support alternate
              // files or filename modifiers:
              // (Vim) E499: Empty file name for '%' or '#', only works with ":p:h"
              // (here) E499: Empty file name for '%'
              injector.messages.showErrorMessage(editor, injector.messages.message("E499"))
              return ExecutionResult.Error
            }
            append(path)
          }

          else -> append(c)
        }

        inBackslash = c == '\\'
      }
    }

    if (command.isEmpty()) {
      return ExecutionResult.Error
    }

    // Null rather than a directory: where a shell command runs is the host's answer, and each one
    // has a different name for it - a project's base path, a workspace folder, the process's own
    // working directory.
    val options = injector.globalOptions()
    return try {
      if (range.size() == 0) {
        // Vim always shows the command it ran and waits for Enter afterwards.
        injector.processGroup.executeCommand(editor, command, null, null, options)?.let {
          val outputPanel = injector.outputPanel.getOrCreate(editor, context)
          outputPanel.clearText()
          outputPanel.addText(":!$command\n")
          outputPanel.addText(it)
          outputPanel.show(requireHitEnter = true)
        }
        showExitCodeMessage(editor)
        lastCommand = command
        ExecutionResult.Success
      } else {
        val textRange = getLineRange(editor).toTextRange(editor)
        val input = editor.text().subSequence(textRange.startOffset, textRange.endOffset)
        injector.processGroup.executeCommand(editor, command, input, null, options)?.let {
          injector.application.runWriteAction {
            val start = editor.offsetToBufferPosition(textRange.startOffset)
            val end = editor.offsetToBufferPosition(textRange.endOffset)
            (editor as MutableVimEditor).replaceString(textRange.startOffset, textRange.endOffset, it)
            val linesFiltered = end.line - start.line
            if (linesFiltered > 2) {
              injector.messages.showStatusBarMessage(editor, "$linesFiltered lines filtered")
            }
          }
        }
        showExitCodeMessage(editor)
        lastCommand = command
        ExecutionResult.Success
      }
    } catch (e: ExException) {
      throw e
    } catch (e: Exception) {
      throw ExException(e.message)
    }
  }

  private fun showExitCodeMessage(editor: VimEditor) {
    val exitCode = injector.processGroup.lastExitCode
    if (exitCode != null && exitCode != 0) {
      val outputPanel = injector.outputPanel.getCurrentOutputPanel()
      if (outputPanel != null) {
        outputPanel.addText("\nShell returned $exitCode")
        outputPanel.show()
      } else {
        injector.messages.showMessage(editor, "shell returned $exitCode")
      }
      injector.messages.indicateError()
    }
  }

  companion object {
    private val logger = vimLogger<CmdFilterCommand>()
    private var lastCommand: String? = null
  }
}
