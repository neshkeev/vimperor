/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.maddyhome.idea.vim.helper.indexOfOrNull
import com.maddyhome.idea.vim.helper.lastIndexOfOrNull

// TODO do we really need a tree structure here?
class ExCommandTree {
  private val abbrevToCommand = mutableMapOf<String, String>()
  private val commandToInstance = mutableMapOf<String, LazyExCommandInstance>()

  fun addCommand(commandsPattern: String, lazyInstance: LazyExCommandInstance) {
    val subCommands = parseCommandPattern(commandsPattern)
    for ((requiredPart, optionalPart) in subCommands) {
      val fullCommand = requiredPart + optionalPart
      commandToInstance[fullCommand] = lazyInstance

      for (i in (0..optionalPart.length)) {
        abbrevToCommand[requiredPart + optionalPart.substring(0, i)] = fullCommand
      }
    }
  }

  fun getCommand(command: String): LazyExCommandInstance? {
    return abbrevToCommand[command]?.let { commandToInstance[it] }
  }

  fun getFullCommandName(abbreviation: String): String? {
    return abbrevToCommand[abbreviation]
  }

  /**
   * Results are sorted alphabetically; command-name Tab completion relies on this
   * order to cycle through matches deterministically (e.g. `:set` -> `set`, `setglobal`, `sethandler`, ...).
   */
  /**
   * Every registered command: its full name, the shortest abbreviation that reaches it, and its handler.
   *
   * `:help` is the only caller. It needs both names because Vim's help tags a command under both - `:h` and `:help` -
   * and it needs the handler because that is where the description is. The pattern the command was declared with
   * (`h[elp]`) is not kept, so the abbreviation is recovered from the table that was built out of it.
   */
  fun allCommands(): List<ExCommandNames> =
    commandToInstance.entries.sortedBy { it.key }.map { (fullCommand, instance) ->
      val abbreviation = abbrevToCommand.entries
        .filter { it.value == fullCommand }
        .minByOrNull { it.key.length }
        ?.key
        ?: fullCommand
      ExCommandNames(fullCommand, abbreviation, instance)
    }

  fun findFullCommandsByPrefix(prefix: String): List<String> {
    return commandToInstance.keys.filter { it.startsWith(prefix) }.sorted()
  }

  private fun parseCommandPattern(commandsPattern: String): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    val commands = commandsPattern.split(",")
    for (command in commands) {
      val leftBraceIndex = command.indexOfOrNull('[')
      val rightBraceIndex = command.indexOfOrNull(']')
      if (
        (leftBraceIndex == null && rightBraceIndex != null) ||
        (leftBraceIndex != null && rightBraceIndex == null) ||
        (leftBraceIndex != null && rightBraceIndex != null && leftBraceIndex > rightBraceIndex) ||
        (leftBraceIndex != null && leftBraceIndex != command.lastIndexOfOrNull('[')) ||
        (rightBraceIndex != null && rightBraceIndex != command.lastIndexOfOrNull(']'))
      ) {
        throw RuntimeException("Invalid ex-command pattern $commandsPattern")
      }
      val primaryPart = command.substring(0, leftBraceIndex ?: command.length)
      val optionalPart = if (leftBraceIndex != null && rightBraceIndex != null) command.substring(
        leftBraceIndex + 1,
        rightBraceIndex
      ) else ""
      result.add(Pair(primaryPart, optionalPart))
    }
    return result
  }
}

/** One registered ex command, under both of the names Vim's help gives it. See [ExCommandTree.allCommands]. */
class ExCommandNames(
  val fullCommand: String,
  val abbreviation: String,
  val instance: LazyExCommandInstance,
)
