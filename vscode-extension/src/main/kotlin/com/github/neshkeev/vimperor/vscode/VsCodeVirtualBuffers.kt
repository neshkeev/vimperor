/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VirtualBufferGroup
import com.maddyhome.idea.vim.api.VirtualBufferKind
import com.maddyhome.idea.vim.api.injector

/**
 * A light editor over no file, which is what Vim's command-line window is: `q:`, `q/` and `q?`.
 *
 * The last part of this host that the engine could reach and not find - every other key now lands
 * on something built, or on a refusal that is itself an answer.
 *
 * ## Untitled, not virtual
 *
 * VS Code has two ways to put text in an editor that is not a file, and only one of them is
 * editable. A `TextDocumentContentProvider` serves a custom scheme and is **read only**, which is
 * how this host reads VS Code's own default keybindings; an untitled document is an ordinary
 * editable buffer with no path yet. The command-line window has to be editable - the whole point is
 * that you can change the line before running it - so it is untitled.
 *
 * The cost is that VS Code would ask to save it on the way out, which is exactly wrong for a
 * scratch buffer. [VsCodeCommands.REVERT_AND_CLOSE] is the way past that: it discards the content
 * and closes in one step, with nothing to ask about.
 *
 * ## Two of the four kinds arrive here
 *
 * [VirtualBufferKind] has four cases and this host can only ever see two. `ControlCharsEditor` is
 * opened by an IntelliJ action and `SubstitutePreview` by IntelliJ's `:` prompt for
 * `inccommand=split`; neither has a caller outside `src/main/java`. They are handled the same way
 * regardless, because the difference is in who calls rather than in what is asked for - except that
 * a preview should be read-only and an untitled document cannot be, which is recorded here rather
 * than discovered later.
 */
internal class VsCodeVirtualBuffers(
  private val hostCommands: HostCommandRunner,
  /** How a document is opened. Injectable so a test can drive this without a window. */
  private val openDocument: (String, (TextDocument) -> Unit) -> Unit = ::openUntitledDocument,
) : VirtualBufferGroup {

  /** What is open, by the document identity the rest of this host keys editors on. */
  private val open: MutableMap<String, Buffer> = mutableMapOf()

  private class Buffer(val kind: VirtualBufferKind, val originalPath: String?)

  override fun open(
    context: ExecutionContext,
    editor: VimEditor,
    kind: VirtualBufferKind,
    content: String,
    focus: Boolean,
  ) {
    // A preview is transient and never blocks; everything else refuses to nest, which is Vim's own
    // rule - `:help cmdwin` - and the reason `q:` inside `q:` says E1292 rather than opening two.
    if (kind != VirtualBufferKind.SubstitutePreview && refuseIfAlreadyOpen(editor)) return

    val originalPath = editor.getPath()
    openDocument(content) { document ->
      open[identityOf(document)] = Buffer(kind, originalPath)
      // `focus` is false only for the substitute preview, which cannot reach this host - so this
      // always shows and focuses, and the parameter is honoured rather than assumed away.
      if (focus) showDocument(document)
    }
  }

  override fun close(editor: VimEditor) {
    val path = editor.getPath() ?: return
    if (open.remove(path) == null) return
    hostCommands.run(VsCodeCommands.REVERT_AND_CLOSE, waitForIt = false)
  }

  override fun close(context: ExecutionContext, kind: VirtualBufferKind) {
    val identity = open.entries.firstOrNull { it.value.kind == kind }?.key ?: return
    open.remove(identity)
    hostCommands.run(VsCodeCommands.REVERT_AND_CLOSE, waitForIt = false)
  }

  override fun isOpen(context: ExecutionContext, kind: VirtualBufferKind): Boolean =
    open.values.any { it.kind == kind }

  override fun refresh(context: ExecutionContext, kind: VirtualBufferKind, content: String) {
    val identity = open.entries.firstOrNull { it.value.kind == kind }?.key ?: return
    val editor = injector.editorGroup.getEditors()
      .filterIsInstance<VsCodeEditor>()
      .firstOrNull { it.getPath() == identity } ?: return
    if (editor.text().contentEquals(content)) return
    editor.replaceString(0, editor.text().length, content)
  }

  /** What kind of virtual buffer [path] is hosting, or null if it is an ordinary editor. */
  fun kindOf(path: String?): VirtualBufferKind? = path?.let { open[it]?.kind }

  /**
   * The editor `q:` was opened from, which is where `<CR>` runs the line - `:help cmdwin-execute`.
   *
   * By path rather than by object: the original editor may have been closed and reopened while the
   * window was up, and a stale `VsCodeEditor` would be written to and never seen.
   */
  fun originalEditorFor(path: String?): VimEditor? {
    val originalPath = path?.let { open[it]?.originalPath } ?: return null
    return injector.editorGroup.getEditors().firstOrNull { it.getPath() == originalPath }
  }

  private fun refuseIfAlreadyOpen(editor: VimEditor): Boolean {
    val alreadyOpen = open.values.firstOrNull { it.kind != VirtualBufferKind.SubstitutePreview } ?: return false
    injector.messages.showErrorMessage(editor, alreadyOpenMessage(alreadyOpen.kind))
    return true
  }

  private fun alreadyOpenMessage(kind: VirtualBufferKind): String = when (kind) {
    VirtualBufferKind.Command, is VirtualBufferKind.Search -> "E1292: Command-line window is already open"
    VirtualBufferKind.ControlCharsEditor -> "A control characters editor is already open"
    VirtualBufferKind.SubstitutePreview -> "A substitute preview is already open"
  }
}

/** The identity the rest of this host keys editors on - see `VimHost.identityOf`. */
private fun identityOf(document: TextDocument): String = document.uri.scheme + "://" + document.uri.path

internal fun openUntitledDocument(content: String, onOpen: (TextDocument) -> Unit) {
  val options = js("{}").unsafeCast<UntitledDocumentOptions>()
  options.content = content
  options.language = "plaintext"
  // Both halves of the promise, for the reason `Thenable` gives: a rejection nobody listens for is
  // silent, and "`q:` did nothing" is the least debuggable bug there is.
  workspace.openTextDocument(options).then(
    { document -> onOpen(document) },
    { failure -> injector.messages.showStatusBarMessage(null, "Vimperor: could not open the command-line window: $failure") },
  )
}

private fun showDocument(document: TextDocument) {
  window.showTextDocument(document).then({ _: TextEditor -> }, { _: Any? -> })
}
