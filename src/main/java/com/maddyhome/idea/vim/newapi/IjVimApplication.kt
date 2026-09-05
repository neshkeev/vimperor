/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.newapi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Computable
import com.intellij.util.ExceptionUtil
import com.intellij.util.Alarm
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.api.ScheduledTask
import com.maddyhome.idea.vim.api.VimApplicationBase
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.diagnostic.vimLogger
import com.maddyhome.idea.vim.key.VimKeyStroke
import java.awt.Component
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities


internal class IjVimApplication : VimApplicationBase() {
  override fun isMainThread(): Boolean {
    return ApplicationManager.getApplication().isDispatchThread
  }

  override fun invokeLater(editor: VimEditor, action: () -> Unit) {
    ApplicationManager.getApplication()
      .invokeLater(action, ModalityState.stateForComponent(editor.ij.component))
  }

  override fun invokeLater(action: () -> Unit) {
    ApplicationManager.getApplication().invokeLater(action)
  }

  override fun invokeAndWait(action: () -> Unit) {
    ApplicationManager.getApplication().invokeAndWait(action)
  }

  override fun isUnitTest(): Boolean {
    return ApplicationManager.getApplication().isUnitTestMode
  }

  override fun isInternal(): Boolean {
    return ApplicationManager.getApplication().isInternal
  }

  override fun postKey(stroke: VimKeyStroke, editor: VimEditor) {
    val component: Component = SwingUtilities.getAncestorOfClass(Window::class.java, editor.ij.component)
    val event = createKeyEvent(stroke, component)
    ApplicationManager.getApplication().invokeLater {
      if (logger.isDebug()) {
        logger.debug("posting $event")
      }
      Toolkit.getDefaultToolkit().systemEventQueue.postEvent(event)
    }
  }

  override fun <T> runWriteAction(action: () -> T): T {
    return ApplicationManager.getApplication().runWriteAction(Computable(action))
  }

  override fun <T> runReadAction(action: () -> T): T {
    return ApplicationManager.getApplication().runReadAction(Computable(action))
  }

  override fun currentStackTrace(): String {
    return ExceptionUtil.currentStackTrace()
  }

  override fun runAfterGotFocus(runnable: () -> Unit) {
    com.maddyhome.idea.vim.helper.runAfterGotFocus(runnable)
  }

  /**
   * IntelliJ's `Alarm`, one per request, at `ModalityState.any()`.
   *
   * The modality matters and is the reason this is not a bare `invokeLater` with a sleep: a
   * highlight put on an editor while a modal dialog is open - the resolve-conflict diff view is the
   * one that gets reported - would otherwise never be taken off again, because the request would
   * wait for the dialog to close.
   *
   * `Alarm` needs a parent disposable to be tied to; the plugin's on/off disposable is the right
   * one, since a request outliving IdeaVim being switched off is exactly what it must not do.
   */
  override fun schedule(delayMillis: Int, action: () -> Unit): ScheduledTask {
    val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, VimPlugin.getInstance().onOffDisposable)
    alarm.addRequest(action, delayMillis, ModalityState.any())
    return ScheduledTask { alarm.cancelAllRequests() }
  }

  /**
   * Now, because there is never anything to wait for: IntelliJ's undo, redo and reformatting are
   * all done by the time the call that asked for them returns.
   */
  override fun runAfterHostCatchesUp(action: () -> Unit) = action()

  private fun createKeyEvent(stroke: VimKeyStroke, component: Component): KeyEvent {
    return KeyEvent(
      component,
      if (stroke.keyChar == KeyEvent.CHAR_UNDEFINED) KeyEvent.KEY_PRESSED else KeyEvent.KEY_TYPED,
      System.currentTimeMillis(),
      stroke.modifiers,
      stroke.keyCode,
      stroke.keyChar,
    )
  }

  companion object {
    private val logger = vimLogger<IjVimApplication>()
  }
}
