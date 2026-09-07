/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.file

import com.github.neshkeev.vimperor.directory.WorkingDirectory
import com.intellij.vim.annotations.CommandOrMotion
import com.intellij.vim.annotations.Mode
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.Command
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.handler.VimActionHandler
import com.maddyhome.idea.vim.state.mode.inVisualMode

/**
 * `gf` - open the file whose name is under the caret.
 *
 * IdeaVim does not have this and never did, so there is no reference implementation to follow here
 * and nothing in `src/test` to replay: the behaviour below is Vim's, read from `:h gf`.
 *
 * ## What counts as a name
 *
 * `'isfname'`, which the engine already carries and already uses for `<C-R><C-F>` on the command
 * line - [com.maddyhome.idea.vim.api.VimSearchHelper.findFilenameAtOrFollowingCursor] is the same
 * scan. It is worth knowing what that buys, because it is the whole reason this works on prose: the
 * default set has no backtick, quote, bracket or colon in it, so a path written as
 * `` `.omc/research/platform/survey.md` `` in a Markdown file stops at the backticks rather than
 * swallowing them. Vim also looks *forward* on the line when the caret is not on a name, which is
 * what makes `gf` usable from the start of a line.
 *
 * In Visual mode the selection is the name, exactly as it is typed - no `'isfname'` scan at all,
 * because the point of selecting it is to say where it ends.
 *
 * ## Where it looks
 *
 * Vim searches `'path'`, whose default is `.,,` - the directory of the current file, then the
 * current directory. This host has no `'path'` option, so those two are what [resolve] does and in
 * that order; an absolute name short-circuits both. The current-file directory has to come first
 * or a repository with `docs/notes.md` and `notes.md` opens the wrong one from inside `docs/`.
 *
 * Each candidate is put to [com.maddyhome.idea.vim.api.VimFile.findFile], which is the host's
 * "does this exist, and where". Handing it an already-absolute path is how existence gets checked
 * without the engine needing a filesystem of its own.
 *
 * ## What is deliberately not here
 *
 * `gF` is not registered. It is `gf` plus "if a number follows the name, put the caret on that
 * line", and the second half is not free on this host: opening a file is a command dispatched to VS
 * Code and the editor does not exist to move a caret in until it lands, so it needs
 * [com.maddyhome.idea.vim.api.VimApplication.runAfterHostCatchesUp]. A `gF` that opened the file
 * and ignored the line number would be worse than one that does nothing, because the caret would
 * be somewhere plausible and wrong. Same for `[count]gf`, which is "the count'th match in
 * `'path'`" and needs a `'path'` to count through, and for `<C-W>f`, which wants a split.
 */
internal object GotoFile {

  fun open(editor: VimEditor, context: ExecutionContext): Boolean {
    val name = nameAtCaret(editor)
    if (name.isNullOrBlank()) {
      injector.messages.showStatusBarMessage(editor, injector.messages.message("E446"))
      injector.messages.indicateError()
      return false
    }

    val found = resolve(name, editor, context)
    if (found == null) {
      injector.messages.showStatusBarMessage(editor, injector.messages.message("E447", name))
      injector.messages.indicateError()
      return false
    }

    // Vim remembers where you jumped from, so `<C-O>` comes back. Saved before the file opens,
    // because afterwards the current editor is the new one.
    injector.jumpService.saveJumpLocation(editor)
    val error = injector.file.openFile(found, context)
    if (error != null) {
      injector.messages.showStatusBarMessage(editor, error)
      injector.messages.indicateError()
      return false
    }
    return true
  }

  /** The selection in Visual mode, the `'isfname'` run at or after the caret otherwise. */
  private fun nameAtCaret(editor: VimEditor): String? {
    val caret = editor.primaryCaret()
    val text = editor.text()
    if (editor.inVisualMode) {
      val start = caret.selectionStart.coerceIn(0, text.length)
      val end = caret.selectionEnd.coerceIn(start, text.length)
      return text.substring(start, end)
    }
    val range = injector.searchHelper.findFilenameAtOrFollowingCursor(editor, caret.offset) ?: return null
    // `findFilenameAtOrFollowingCursor` ends inclusive, as `<C-R><C-F>` also has to allow for.
    return text.substring(range.startOffset, (range.endOffset + 1).coerceAtMost(text.length))
  }

  /** Vim's default `'path'` of `.,,`: beside the current file, then under the working directory. */
  private fun resolve(name: String, editor: VimEditor, context: ExecutionContext): String? {
    val expanded = injector.pathExpansion.expandPath(name.trim())
    if (expanded.isEmpty()) return null
    if (WorkingDirectory.isAbsolute(expanded)) return injector.file.findFile(expanded, context)

    val beside = currentFileDirectory(editor)?.let { injector.file.findFile("$it/$expanded", context) }
    if (beside != null) return beside

    return injector.file.findFile(WorkingDirectory.resolve(expanded, editor), context)
  }

  /**
   * The directory holding the buffer, or null when it is not a file on disk.
   *
   * `getPath()` is not a path, whatever the name says: it is the editor's *identity*, and this host
   * makes it `scheme://path` on purpose so that an untitled buffer has one and can therefore carry
   * marks. `getVirtualFile().path` is the same string. So the scheme has to come off before any of
   * it is treated as a place on disk, and only `file` is one - joining onto the path half of an
   * `untitled://` identity would name a directory that has nothing to do with the buffer.
   */
  private fun currentFileDirectory(editor: VimEditor): String? {
    if (editor.extractProtocol() != "file") return null
    val identity = editor.getPath() ?: return null
    return identity.substringAfter("://", identity)
      .substringBeforeLast('/', missingDelimiterValue = "")
      .takeIf { it.isNotEmpty() }
  }
}

@CommandOrMotion(keys = ["gf"], modes = [Mode.NORMAL, Mode.VISUAL])
class GotoFileUnderCaretAction : VimActionHandler.SingleExecution() {
  override val type: Command.Type = Command.Type.OTHER_READONLY

  override fun execute(
    editor: VimEditor,
    context: ExecutionContext,
    cmd: Command,
    operatorArguments: OperatorArguments,
  ): Boolean = GotoFile.open(editor, context)
}
