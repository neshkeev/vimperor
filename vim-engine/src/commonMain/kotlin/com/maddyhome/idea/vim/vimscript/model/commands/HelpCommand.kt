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
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * @author vlan
 * see "h :help"
 *
 * Vim opens its own help in a split; there is no help file to open in an IDE, so this opens the
 * online copy in a browser instead. Which browser is the host's question, and it is the same one
 * `gx` asks - `VimExternalOpener` was already there for it.
 *
 * This lived in the IntelliJ module until now, for `BrowserUtil.browse` and `URLEncoder`. Neither
 * is a reason: the first is the host service above, and the second is a dozen lines of percent
 * encoding that the JDK happens to ship.
 */
@ExCommand(command = "h[elp]")
data class HelpCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags = flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    injector.externalOpener.open(helpTopicUrl(argument), null)
    return ExecutionResult.Success
  }

  private fun helpTopicUrl(topic: String): String {
    if (topic.isBlank()) return HELP_ROOT_URL
    return "$HELP_QUERY_URL?docs=help&search=${percentEncode(topic)}"
  }

  /**
   * `application/x-www-form-urlencoded`, which is what a query parameter needs.
   *
   * The unreserved set is RFC 3986's, plus the two that form encoding keeps - and a space becomes
   * `+` rather than `%20`, which is the one place form encoding differs from percent encoding and
   * the reason `:help i_CTRL-W` and `:help i CTRL-W` reach different pages.
   */
  private fun percentEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
      val character = byte.toInt().toChar()
      when {
        character.isLetterOrDigit() && byte.toInt() in 0..127 -> append(character)
        character in "-_.*" -> append(character)
        character == ' ' -> append('+')
        else -> {
          append('%')
          append(HEX[(byte.toInt() shr 4) and 0xF])
          append(HEX[byte.toInt() and 0xF])
        }
      }
    }
  }

  companion object {
    private const val HELP_BASE_URL = "http://vimdoc.sourceforge.net"
    private const val HELP_ROOT_URL = "$HELP_BASE_URL/htmldoc/"
    private const val HELP_QUERY_URL = "$HELP_BASE_URL/search.php"
    private const val HEX = "0123456789ABCDEF"
  }
}
