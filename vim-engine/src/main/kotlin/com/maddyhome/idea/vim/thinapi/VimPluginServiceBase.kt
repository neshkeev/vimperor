/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.thinapi

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.CommandAlias
import com.maddyhome.idea.vim.common.CommandAliasHandler
import com.maddyhome.idea.vim.extension.ScriptFunctions
import com.maddyhome.idea.vim.key.KeySource
import com.maddyhome.idea.vim.key.OperatorFunction

/**
 * The three things a plugin asks the *host* for, none of which turn out to need one.
 *
 * This was on the VS Code host's list of services it could not provide, with the reason that it was
 * blocked behind `modalInput.activate` - the blocking read a `getchar()` needs, which JavaScript
 * cannot do on one thread. That reason was about a different part of the extension system entirely.
 * Nothing here reads a key: running normal-mode keys is the key handler, exporting an operator
 * function is a Vimscript function declaration, and adding a command is a command alias. All three
 * are the engine's, and IdeaVim's implementation is three one-line delegations to a facade in its
 * IntelliJ module whose bodies have no IntelliJ in them.
 *
 * So both hosts share this, and the reason on that list has been corrected rather than deleted -
 * a wrong reason is worse than no reason, because it stops the next person looking.
 */
abstract class VimPluginServiceBase : VimPluginService {

  /**
   * `:normal!` from a plugin - the keys are executed, and mappings do not apply to them.
   *
   * [KeySource.NORMAL_COMMAND_NOT_MAPPED] is the whole of the "without mapping" part: a plugin that
   * feeds `dd` means the built-in `dd`, not whatever the user has mapped it to.
   */
  override fun executeNormalWithoutMapping(command: String, editor: VimEditor) {
    val context = injector.executionContextManager.getEditorExecutionContext(editor)
    val keyHandler = KeyHandler.getInstance()
    for (key in injector.parser.parseKeys(command)) {
      keyHandler.handleKey(editor, key, KeySource.NORMAL_COMMAND_NOT_MAPPED, context, keyHandler.keyHandlerState)
    }
  }

  override fun exportOperatorFunction(name: String, function: OperatorFunction) {
    ScriptFunctions.exportOperatorFunction(name, function)
  }

  override fun addCommand(name: String, commandHandler: CommandAliasHandler) {
    injector.commandGroup.setAlias(name, CommandAlias.Call(0, 0, name, commandHandler))
  }
}
