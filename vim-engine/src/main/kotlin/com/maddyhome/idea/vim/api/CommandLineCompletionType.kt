/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

enum class CommandLineCompletionType(
  /**
   * Whether Tab replaces the word the caret is in rather than the whole argument.
   *
   * Most commands take one argument that happens to contain spaces - `:edit my file.txt` names a
   * single file - so completing only the last word would produce a path nobody meant. `:set` is the
   * other kind: it takes a *list*, and the words before the one being typed are already settled.
   */
  val completesLastWord: Boolean = false,
) {
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

  /**
   * The names of the options `:set` knows, which is what `:set syn<Tab>` is asking for.
   *
   * Vim completes the full name rather than the abbreviation - `syn` becomes `syntax`, not `syn` -
   * and offers `no` and `inv` in front of a boolean option's name, because those are how you write
   * one. Both are what the option registry can answer, and it is the same registry in both hosts,
   * so the host that declares `'relativenumber'` gets it completed without doing anything.
   */
  OPTION(completesLastWord = true),
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
    "set" to CommandLineCompletionType.OPTION,
    "setlocal" to CommandLineCompletionType.OPTION,
    "setglobal" to CommandLineCompletionType.OPTION,
  )

  fun getCompletionType(fullCommandName: String): CommandLineCompletionType {
    return commandToCompletionType[fullCommandName] ?: CommandLineCompletionType.NONE
  }
}
