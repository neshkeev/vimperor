/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.api

import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.handler.EditorActionHandlerBase
import com.maddyhome.idea.vim.annotations.NonNls

interface VimActionExecutor {

  val ACTION_EDITOR_NEXT_TEMPLATE_VARIABLE: String
  val ACTION_COLLAPSE_ALL_REGIONS: String
  val ACTION_COLLAPSE_REGION: String
  val ACTION_COLLAPSE_REGION_RECURSIVELY: String
  val ACTION_EXPAND_ALL_REGIONS: String
  val ACTION_EXPAND_REGION: String
  val ACTION_EXPAND_REGION_RECURSIVELY: String
  val ACTION_EXPAND_COLLAPSE_TOGGLE: String
  val ACTION_UNDO: String
  val ACTION_REDO: String

  /**
   * `gd` and `<C-]>`: go to the definition of whatever is under the caret.
   *
   * Named by the host rather than written into the action, for the same reason the fold actions
   * are: the engine knows which *idea* it wants, and only the host knows what its own editor calls
   * it. IntelliJ says `GotoDeclaration`; a different host will say something else.
   */
  val ACTION_GOTO_DECLARATION: String

  /**
   * Execute an action
   *
   * @param action  The action to execute
   * @param context The context to run it in
   */
  fun executeAction(editor: VimEditor?, action: NativeAction, context: ExecutionContext): Boolean

  /**
   * Execute an action
   *
   * @param action  The action to execute
   */
  fun executeAction(editor: VimEditor?, action: NativeAction): Boolean

  /**
   * Execute an action by name
   *
   * @param name    The name of the action to execute
   * @param context The context to run it in
   */
  fun executeAction(editor: VimEditor, name: @NonNls String, context: ExecutionContext): Boolean

  fun executeCommand(
    editor: VimEditor?,
    runnable: () -> Unit,
    name: String?,
    groupId: Any?,
  )

  fun executeEsc(editor: VimEditor, context: ExecutionContext): Boolean

  fun executeVimAction(
    editor: VimEditor,
    cmd: EditorActionHandlerBase,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  )

  fun findVimAction(id: String): EditorActionHandlerBase?
  fun findVimActionOrDie(id: String): EditorActionHandlerBase

  fun getAction(actionId: String): NativeAction?
  fun getActionIdList(idPrefix: String): List<String>
}
