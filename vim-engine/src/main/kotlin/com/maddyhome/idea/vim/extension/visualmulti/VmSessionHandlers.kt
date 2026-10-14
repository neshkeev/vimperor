/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.visualmulti

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtensionFacade.executeNormalWithoutMapping
import com.maddyhome.idea.vim.extension.multiplecursors.leaveVisualMode
import com.maddyhome.idea.vim.key.VimKeyStroke

/** Runs [handler], and starts the session if it created any regions. */
internal class StartSessionHandler(private val handler: ExtensionHandler) : ExtensionHandler {
  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    handler.execute(editor, context, operatorArguments)
    VmSession.startIfRegionsCreated(editor)
  }
}

/** Runs [handler] inside a session and [outsideSession] outside one. */
internal class SessionOnlyHandler(
  private val handler: ExtensionHandler,
  private val outsideSession: ExtensionHandler,
) : ExtensionHandler {

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    val active = if (VmSession.isActive(editor)) handler else outsideSession
    active.execute(editor, context, operatorArguments)
  }
}

/**
 * Runs [keys] without mappings, so they keep their Vim meaning - outside a session `n` still extends
 * the Visual selection to the next match.
 */
internal class NativeKeysHandler(private val keys: List<VimKeyStroke>) : ExtensionHandler {

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    executeNormalWithoutMapping(countKeys(operatorArguments.count0) + keys, editor)
  }

  private fun countKeys(count: Int): List<VimKeyStroke> =
    if (count > 0) injector.parser.stringToKeys(count.toString()) else emptyList()
}

/**
 * Keeps only the primary cursor and goes back to Normal mode. The regions can be had again with
 * Reselect Last.
 */
internal class ExitHandler : ExtensionHandler {
  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    saveLastRegions(editor)
    VmSession.end(editor)
    editor.removeSecondaryCarets()
    leaveVisualMode(editor)
  }
}
