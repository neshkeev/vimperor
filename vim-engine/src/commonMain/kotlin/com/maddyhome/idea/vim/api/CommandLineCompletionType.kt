/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

enum class CommandLineCompletionType {
  NONE,
  FILE,

  /**
   * The host's own named actions - IntelliJ's action ids, VS Code's command ids.
   *
   * Both hosts already answer `VimActionExecutor.getActionIdList`, which is a prefix query, so
   * `:action` completes the same way `:edit` completes a path. There is nothing host-specific about
   * it beyond what the ids are called.
   */
  ACTION,
}

object CommandCompletionTypes {
  private val commandToCompletionType = mapOf(
    "edit" to CommandLineCompletionType.FILE,
    "browse" to CommandLineCompletionType.FILE,
    "find" to CommandLineCompletionType.FILE,
    "source" to CommandLineCompletionType.FILE,
    "write" to CommandLineCompletionType.FILE,
    "read" to CommandLineCompletionType.FILE,
    "split" to CommandLineCompletionType.FILE,
    "vsplit" to CommandLineCompletionType.FILE,
    "action" to CommandLineCompletionType.ACTION,
  )

  fun getCompletionType(fullCommandName: String): CommandLineCompletionType {
    return commandToCompletionType[fullCommandName] ?: CommandLineCompletionType.NONE
  }
}
