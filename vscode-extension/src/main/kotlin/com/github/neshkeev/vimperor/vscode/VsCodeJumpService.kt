/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimJumpServiceBase
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.normalizeOffset
import com.maddyhome.idea.vim.mark.DiffSide
import com.maddyhome.idea.vim.mark.Jump
import com.maddyhome.idea.vim.mark.JumpSource

/**
 * Jumps, and the open diff a jump was made in.
 *
 * A jump only stored the file, so `<C-O>` from a diff came back as the plain file. The source says
 * which side of which open diff it was. A closed diff is not opened again: a side that is the file
 * falls through to the plain editor, and a side that is not (an old revision) is dropped.
 */
internal class VsCodeJumpService : VimJumpServiceBase() {
  /** VS Code's own go-back history, which this does not yet contribute to. */
  override fun includeCurrentCommandAsNavigation(editor: VimEditor) {}
  override var lastJumpTimeStamp: Long = 0

  override fun jumpAtCaret(editor: VimEditor): Jump? {
    val jump = super.jumpAtCaret(editor) ?: return null
    val editorPath = editor.getPath() ?: return jump
    val tab = openDiffs().firstOrNull { diff ->
      identity(diff.original) == editorPath || identity(diff.modified) == editorPath
    } ?: return jump
    // The modified side is checked first, so an editor that is that side is the right-hand one.
    val onModified = identity(tab.modified) == editorPath
    val uri = if (onModified) tab.modified else tab.original
    return jump.copy(
      source = JumpSource.Diff(
        diffId = diffId(tab.original, tab.modified),
        side = if (onModified) DiffSide.RIGHT else DiffSide.LEFT,
        // Only a file on disk can fall back to the plain editor. A `git:` original is not the file.
        isInFile = uri.scheme == "file",
      ),
    )
  }

  override fun showInDiff(editor: VimEditor, jump: Jump): Boolean {
    val source = jump.source as? JumpSource.Diff ?: return false
    val tabs = openDiffs()
    val tab = if (jump.isInFile) {
      val showing = tabs.filter { identity(it.uriOn(source.side)) == jump.filepath }
      showing.firstOrNull { diffId(it.original, it.modified) == source.diffId } ?: showing.firstOrNull()
    } else {
      tabs.firstOrNull { diffId(it.original, it.modified) == source.diffId }
    } ?: return false
    reveal(tab)
    moveCaret(identity(tab.uriOn(source.side)), jump)
    return true
  }

  /** `vscode.diff` reveals a diff that is already open. It is not called for one that is not. */
  private fun reveal(tab: OpenDiff) {
    commands.executeCommand(VsCodeCommands.DIFF, tab.original, tab.modified, tab.label)
  }

  private fun moveCaret(sidePath: String, jump: Jump) {
    val target = injector.editorGroup.getEditors()
      .filterIsInstance<VsCodeEditor>()
      .firstOrNull { it.getPath() == sidePath }
      ?: return
    val position = BufferPosition(jump.line, jump.col, false)
    val offset = target.normalizeOffset(target.bufferPositionToOffset(position), false)
    target.currentCaret().moveToOffset(offset)
  }

  private fun openDiffs(): List<OpenDiff> {
    return window.tabGroups.all.flatMap { group ->
      group.tabs.mapNotNull { tab ->
        val input = tab.input as? TabInputTextDiff ?: return@mapNotNull null
        OpenDiff(tab.label, input.original, input.modified)
      }
    }
  }

  private class OpenDiff(val label: String, val original: Uri, val modified: Uri) {
    fun uriOn(side: DiffSide): Uri = if (side == DiffSide.LEFT) original else modified
  }

  private companion object {
    /** The same string [VsCodeEditor.getPath] stores: `scheme://path`, not a filesystem path. */
    fun identity(uri: Uri): String = uri.scheme + "://" + uri.path

    /** The pair of URI paths. The tab's label is not part of it. */
    fun diffId(original: Uri, modified: Uri): String = original.path + "\u0000" + modified.path
  }
}
