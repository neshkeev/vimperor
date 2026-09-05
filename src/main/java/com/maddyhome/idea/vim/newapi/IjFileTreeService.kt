/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.newapi

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ex.ToolWindowManagerEx
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimFileTreeService
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.helper.MessageHelper
import com.maddyhome.idea.vim.helper.runAfterGotFocus

/**
 * IntelliJ's Project view, which is the tree `NERDTree`'s ex commands show and hide.
 *
 * Lifted out of the extension rather than written for this: the four action ids and the
 * `runAfterGotFocus` around them are IdeaVim's own, and they are correct - the id is what makes a
 * tool window open in a way that works in a split frontend/backend IDE, and the focus wait is what
 * stops an action running against a window that is not there yet.
 *
 * What moved out of the extension is only the *naming*. `ActivateProjectToolWindow` is an IntelliJ
 * fact and had no business being written down in something that also has to run in VS Code.
 */
internal class IjFileTreeService : VimFileTreeService {

  override fun focus(editor: VimEditor, context: ExecutionContext) {
    callAction(editor, "ActivateProjectToolWindow")
  }

  override fun toggle(editor: VimEditor, context: ExecutionContext) {
    val toolWindow = projectView(editor)
    if (toolWindow != null && toolWindow.isVisible) {
      toolWindow.hide()
    } else {
      // Not `toolWindow.show()`: the action is what opens it correctly when there is no tool window
      // yet, and it is what IdeaVim has always called.
      callAction(editor, "ActivateProjectToolWindow")
    }
  }

  override fun close(editor: VimEditor, context: ExecutionContext) {
    projectView(editor)?.takeIf { it.isVisible }?.hide()
  }

  override fun revealCurrentFile(editor: VimEditor, context: ExecutionContext) {
    callAction(editor, "SelectInProjectView")
  }

  override fun refresh(editor: VimEditor, context: ExecutionContext) {
    callAction(editor, "Synchronize")
  }

  private fun projectView(editor: VimEditor) =
    editor.ij.project?.let { ToolWindowManagerEx.getInstanceEx(it).getToolWindow(ToolWindowId.PROJECT_VIEW) }

  private fun callAction(editor: VimEditor?, name: String) = runIjAction(editor, name)
}

/**
 * Runs an IntelliJ action once the IDE has focus.
 *
 * The wait matters: an ex command is run from the command line, and the command line gives focus
 * back after the command has been dispatched - so an action run straight away can act on the wrong
 * component. This is `NerdTreeAction.callAction`, which now delegates here.
 *
 * It lives in `newapi` rather than in the extension because that is the direction the dependency
 * belongs: "run an IntelliJ action, carefully" is a fact about this host, and the tree half of
 * NERDTree - which is a fact about this host too - is the caller. Both go when the plugin does.
 */
internal fun runIjAction(editor: VimEditor?, name: String) {
  val action = ActionManager.getInstance().getAction(name) ?: run {
    VimPlugin.showMessage(MessageHelper.message("nerdtree.error.action.not.found", name))
    return
  }
  // There is nothing to wait for when nothing has focus, which is every unit test.
  if (ApplicationManager.getApplication().isUnitTestMode) {
    injector.actionExecutor.executeAction(editor, action.vim)
  } else {
    runAfterGotFocus {
      injector.actionExecutor.executeAction(editor, action.vim)
    }
  }
}
