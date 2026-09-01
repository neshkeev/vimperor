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
 * Opening and writing a *named* file is the other half, and the two halves come from opposite
 * directions. `:w file` writes a path and never touches the editor, so it is Node's `fs` - and it
 * has to be, because `:w` reports `E212` when the write fails and a promise cannot answer a command
 * that has already returned. `:e file` is the reverse: nothing to read or write, only VS Code to
 * ask, and `vscode.open` is a command like the folds and the splits once the runner can carry an
 * argument. Keeping it in that lane means it inherits the queue, the rejection branch and the
 * command-id check.
 *
 * A path here is Vim's, so `~` and `$VAR` are expanded, and a relative one is resolved against the
 * workspace folder. Vim would resolve against the current directory; a VS Code window does not have
 * one, it has a workspace, and that is the nearest true thing.
 */
internal class VsCodeFile(
  private val host: HostCommandRunner,
  private val files: NodeFileSystem = NodeFileSystem(),
  /** The folder open in this window, if there is one. Injectable so tests are not run from one. */
  private val workspaceRoot: () -> String? = { workspace.workspaceFolders?.firstOrNull()?.uri?.fsPath },
) : VimFileBase() {

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

  /**
   * `:w file` and `:w! file`, which write the buffer somewhere it did not come from.
   *
   * Straight to disk, and deliberately not through VS Code: this writes a file that is not open in
   * any editor, so there is nothing for `workbench.action.files.save` to save. Vim's own `:w file`
   * does not open the file it wrote either, and neither does this.
   */
  override fun createFile(filename: String, context: ExecutionContext, content: String?, editor: VimEditor) {
    val failure = files.writeText(absolute(filename), content.orEmpty())
    if (failure != null) {
      injector.messages.showErrorMessage(editor, "E212: Can't open file for writing: $failure")
    }
  }

  /**
   * `:e file`, which asks VS Code to open it - and `:e newfile`, which asks for one that is not
   * there yet.
   *
   * Vim gives you an empty buffer with that name, waiting to be written. This used to answer E447
   * and say so in a comment: an untitled document "has no path until it is saved, and saving it
   * asks where to put it". That is true of `workbench.action.files.newUntitledFile` and not of the
   * `untitled:` scheme, which takes a path - `untitled:/home/me/new.txt` is an unsaved buffer whose
   * `:w` writes to exactly that file, with no dialog, and whose language VS Code derives from the
   * name. Which is Vim's new buffer, under another spelling.
   */
  override fun openFile(filename: String, context: ExecutionContext, focusEditor: Boolean): String? {
    val path = absolute(filename)
    val uri = if (files.exists(path)) UriFactory.file(path) else UriFactory.parse(UNTITLED + path)
    host.run(VsCodeCommands.OPEN, arrayOf(uri))
    return null
  }

  /**
   * The path a Vim command names, as a path on disk.
   *
   * `findFile` is what `:w file` asks before refusing to overwrite, and what the engine uses to
   * turn a name into a path. Only the workspace folder is searched, not IntelliJ's content roots
   * and file index - VS Code has no equivalent of either, and a search that guessed would answer
   * `:w` with the wrong file to overwrite.
   */
  override fun findFile(filename: String, context: ExecutionContext): String? =
    absolute(filename).takeIf { files.exists(it) }

  private fun absolute(filename: String): String {
    val expanded = injector.pathExpansion.expandPath(filename.trim())
    if (expanded.startsWith("/") || expanded.startsWith("\\\\") || DRIVE_LETTER.matches(expanded.take(2))) {
      return expanded
    }
    val root = workspaceRoot() ?: return expanded
    return "$root/$expanded"
  }

  private companion object {
    /** `C:` and the rest, so a Windows path is not treated as relative to the workspace. */
    val DRIVE_LETTER = Regex("[A-Za-z]:")

    /** The scheme for a buffer that has a name and no file yet. See [openFile]. */
    const val UNTITLED = "untitled:"
  }

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
