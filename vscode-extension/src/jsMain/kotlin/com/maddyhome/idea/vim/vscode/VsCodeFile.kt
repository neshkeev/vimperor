/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimBuffer
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
   * This said Vim numbers its buffers and VS Code does not, so "number three" could only mean
   * whatever happened to be third. Half of that is still true - a tab has no identity beyond its
   * file - and the conclusion no longer follows: [getBuffers] is a numbered list, `:ls` prints it,
   * and this closes the file that list names. Bringing it forward first is what [VsCodeTabs] does
   * for `:tabclose`, because VS Code closes the *active* editor and has no command for closing one
   * by name.
   */
  override fun closeFile(number: Int, context: ExecutionContext) {
    val target = openBuffers().getOrNull(number) ?: return
    host.run(VsCodeCommands.OPEN, arrayOf(target.uri), waitForIt = false)
    host.run(VsCodeCommands.CLOSE_ACTIVE_EDITOR, waitForIt = false)
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
   * By URI rather than by `workbench.action.openEditorAtIndexN`, which is what this used before:
   * those commands stop at nine and count within one editor group, so `:buffer 3` and the third row
   * of `:ls` were not necessarily the same file. Both read [openBuffers] now, which is the point of
   * having the list at all - Vim's buffer numbers only mean anything if the command that prints
   * them and the command that takes one agree.
   */
  override fun selectFile(count: Int, context: ExecutionContext): Boolean {
    val buffers = openBuffers()
    val index = if (count == VimFile.LAST_FILE_SENTINEL) buffers.size - 1 else count
    val target = buffers.getOrNull(index) ?: return false
    host.run(VsCodeCommands.OPEN, arrayOf(target.uri), waitForIt = false)
    return true
  }

  /**
   * `:ls` and `:buffer name` - Vim's buffer list, out of VS Code's tabs.
   *
   * This was written off, in `ExCommandsOnlyInIntelliJTest`, as something "VS Code's tab model does
   * not carry": tabs rather than buffers, and no modified state to print. The first half is a real
   * difference and does not matter here - a tab holds one file, which is all a row of this table
   * describes. The second half was simply not so. `Tab.isDirty` is Vim's `+` and `Tab.isActive` is
   * its `%`, and both have been in the API since 1.68. What made the note look right is that
   * `window.tabGroups` is the one part of the workbench that answers synchronously, and it is easy
   * to assume otherwise about a workbench API.
   *
   * The order is the workbench's own - groups left to right, tabs within each - so the numbers
   * `:ls` prints are the order the user sees, and [selectFile] opens by the same list.
   *
   * Two of Vim's columns have no answer here and say so rather than guessing. There is no read-only
   * flag on a tab, so `=` never appears. And `#` needs the alternate file, which VS Code keeps as
   * an MRU order it will act on - `openPreviousRecentlyUsedEditorInGroup`, which is how `<C-^>`
   * works - but will not report, so no row is ever marked as the alternate.
   */
  override fun getBuffers(context: ExecutionContext): List<VimBuffer> {
    // A registered editor is a buffer Vim would call loaded; a tab that has never been given one is
    // listed with `line 0`, which is exactly what Vim prints for a buffer it only knows the name of.
    val loaded = injector.editorGroup.getEditors()
      .filterIsInstance<VsCodeEditor>()
      .associateBy { it.nativeEditor.document.uri.path }

    return openBuffers().map { buffer ->
      val editor = loaded[buffer.uri.path]
      VimBuffer(
        name = buffer.uri.path.substringAfterLast('/'),
        displayPath = relativeToWorkspace(buffer.uri.fsPath),
        isCurrent = buffer.isCurrent,
        isAlternate = false,
        isReadOnly = false,
        isModified = buffer.isDirty,
        line = editor?.let { it.primaryCaret().getBufferPosition().line + 1 } ?: 0,
      )
    }
  }

  /**
   * The tabs that hold a file, in workbench order, with the URI each one is showing.
   *
   * Kept apart from [getBuffers] because the numbered commands need the URI and Vim's table does
   * not: `VimBuffer` deliberately carries no handle, so that a list built for `:ls` cannot be used
   * to reach a buffer the user has closed since.
   *
   * `TabInputText` and nothing else. A diff, a notebook, a terminal and a webview are all tabs, and
   * none of them is a file Vim could put a cursor in - `TabInputTextDiff` does not even have a
   * single `uri` to name.
   */
  private fun openBuffers(): List<OpenBuffer> {
    return window.tabGroups.all.flatMap { group ->
      group.tabs.mapNotNull { tab ->
        val input = tab.input as? TabInputText ?: return@mapNotNull null
        OpenBuffer(input.uri, isCurrent = tab.isActive && group.isActive, isDirty = tab.isDirty)
      }
    }
  }

  private class OpenBuffer(val uri: Uri, val isCurrent: Boolean, val isDirty: Boolean)

  private fun withLineEndingsOf(editor: VimEditor, content: String): String {
    val document = (editor as? VsCodeEditor)?.nativeEditor?.document ?: return content
    return if (document.eol == EndOfLine.CRLF) content.replace("\n", "\r\n") else content
  }

  /** The path as `:ls` shows it: shortened against the open folder, the way IdeaVim shortens against a project. */
  private fun relativeToWorkspace(path: String): String {
    val root = workspaceRoot()?.let { if (it.endsWith("/")) it else "$it/" } ?: return path
    return if (path.startsWith(root)) path.removePrefix(root) else path
  }

  /**
   * The folder open in this window, which is the nearest thing VS Code has to a current directory.
   *
   * Already what a relative path is resolved against here, so `:pwd` prints the truth rather than a
   * guess. Null when the window has no folder open.
   */
  override fun getWorkingDirectory(context: ExecutionContext): String? = workspaceRoot()

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
    // In the line ending the buffer came with. The engine's text is normalised to `\n`, so writing
    // it straight out would turn a CRLF file into an LF one on its way to a new name - which is
    // Vim's `'fileformat'`, and Vim keeps it.
    val failure = files.writeText(absolute(filename), withLineEndingsOf(editor, content.orEmpty()))
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
}
