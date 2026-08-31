/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimFile
import com.maddyhome.idea.vim.api.VimFileBase
import com.maddyhome.idea.vim.api.injector

/**
 * `:w`, `:q`, `<C-G>` - the file, as opposed to the buffer.
 *
 * These were the largest hole in the port and the one nothing had found, because the inventory that
 * finds holes presses keys and every one of these lives behind a colon. `:w` and `:q` are not
 * obscure; they were reporting "Not implemented yet :(" since the first commit.
 *
 * Saving and closing are VS Code commands like any other. Saving *waits*, and for a reason worth
 * saying out loud: it does not change the text by itself, but format-on-save does, and a keystroke
 * computed against the pre-format text would land on top of it.
 *
 * Opening a file by name is where this stops. `:e file` and `:w file` need a path turned into a
 * document, which is `showTextDocument` and `workspace.fs` rather than a command - a different
 * piece of API surface than this host has declared, and a promise besides. They say so.
 */
internal class VsCodeFile(private val host: HostCommandRunner) : VimFileBase() {

  /**
   * Vim's `<C-G>` line, built here because there is nobody to ask for it.
   *
   * The form is Vim's own: the name, whether it has unsaved changes, and where the caret is as a
   * line, a percentage and a column.
   */
  override fun displayFileInfo(vimEditor: VimEditor, fullPath: Boolean): String? {
    val editor = vimEditor as? VsCodeEditor ?: return null
    val document = editor.nativeEditor.document
    val name = if (fullPath) document.fileName else document.fileName.substringAfterLast('/')
    val position = editor.primaryCaret().getBufferPosition()
    val lines = editor.lineCount()
    val percent = if (lines <= 1) 100 else (position.line * 100) / (lines - 1)
    val modified = if (document.isDirty) " [Modified]" else ""
    return "\"$name\"$modified line ${position.line + 1} of $lines --$percent%-- col ${position.column + 1}"
  }

  override fun saveFile(editor: VimEditor, context: ExecutionContext) =
    host.run(VsCodeCommands.SAVE)

  override fun saveFiles(editor: VimEditor, context: ExecutionContext) =
    host.run(VsCodeCommands.SAVE_ALL)

  override fun closeFile(editor: VimEditor, context: ExecutionContext) =
    host.run(VsCodeCommands.CLOSE_ACTIVE_EDITOR, waitForIt = false)

  /**
   * `:bdelete N` - close the buffer with that number.
   *
   * Vim numbers its buffers and VS Code does not: an editor has a position among the open tabs and
   * no identity beyond its file. Closing "number three" would mean closing whatever happens to be
   * third, which is not what was asked for.
   */
  override fun closeFile(number: Int, context: ExecutionContext) {
    unsupported("closing a buffer by number")
  }

  /**
   * `<C-^>` and `:e#` - back to the file you were in before this one.
   *
   * VS Code's nearest equivalent is its Ctrl+Tab order, which is most-recently-used rather than
   * Vim's single alternate file. For two open editors they are the same thing, which is the case
   * this key is nearly always used in.
   */
  override fun selectPreviousTab(context: ExecutionContext): Boolean {
    host.run(VsCodeCommands.PREVIOUS_USED_EDITOR_IN_GROUP, waitForIt = false)
    return true
  }

  /**
   * `:buffer N`, and `:last` through [VimFile.LAST_FILE_SENTINEL].
   *
   * VS Code numbers the first nine editors in a group with a command each and stops there, which is
   * as far as this can go.
   */
  override fun selectFile(count: Int, context: ExecutionContext): Boolean {
    if (count == VimFile.LAST_FILE_SENTINEL) {
      host.run(VsCodeCommands.LAST_EDITOR_IN_GROUP, waitForIt = false)
      return true
    }
    if (count < 0 || count > 8) return false
    host.run(VsCodeCommands.openEditorAtIndex(count), waitForIt = false)
    return true
  }

  /** `:bnext` and `:bprevious`, which VS Code calls the next and previous editor. */
  override fun selectNextFile(count: Int, context: ExecutionContext) {
    if (count == 0) return
    val command = if (count > 0) VsCodeCommands.NEXT_EDITOR else VsCodeCommands.PREVIOUS_EDITOR
    repeat(kotlin.math.abs(count)) { host.run(command, waitForIt = false) }
  }

  override fun createFile(filename: String, context: ExecutionContext, content: String?, editor: VimEditor) {
    unsupported("writing to a named file")
  }

  override fun openFile(filename: String, context: ExecutionContext, focusEditor: Boolean): String? =
    "IdeaVim: opening a file by name is not supported yet."

  /**
   * IntelliJ has projects and this host does not: a VS Code window is one workspace and the engine
   * only ever compares these for equality, so one name for the one thing there is says as much as
   * a real id would.
   */
  override fun getProjectId(project: Any): String = "vscode"

  override fun selectEditor(projectId: String, documentPath: String, protocol: String): VimEditor? =
    injector.editorGroup.getEditors()
      .filterIsInstance<VsCodeEditor>()
      .firstOrNull { it.nativeEditor.document.fileName == documentPath }

  private fun unsupported(what: String) {
    val editor = injector.editorGroup.getEditors().firstOrNull() ?: return
    injector.messages.showErrorMessage(editor, "IdeaVim: $what is not supported yet.")
  }
}
