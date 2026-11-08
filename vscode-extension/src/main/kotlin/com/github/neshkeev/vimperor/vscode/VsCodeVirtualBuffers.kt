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
 * A light editor over no file: the command-line window (`q:`, `q/`, `q?`), `:help`, and `:actionlist`.
 *
 * ## Untitled, not virtual
 *
 * VS Code has two ways to put text in an editor that is not a file, and only one of them is
 * editable. A `TextDocumentContentProvider` serves a custom scheme and is **read only**, which is
 * how this host reads VS Code's own default keybindings; an untitled document is an ordinary
 * editable buffer with no path yet. The command-line window has to be editable - the whole point is
 * that you can change the line before running it - so every one of these is untitled.
 *
 * The cost is that VS Code would ask to save it on the way out, which is exactly wrong for a
 * scratch buffer. [VsCodeCommands.REVERT_AND_CLOSE] is the way past that: it discards the content
 * and closes in one step, with nothing to ask about.
 *
 * ## Read-only is an engine flag
 *
 * An untitled document cannot be marked read-only in VS Code, so [VirtualBufferKind.readOnly] is
 * what refuses a user edit: [VsCodeEditor.isWritable] answers false and [com.maddyhome.idea.vim.KeyHandler]
 * declines `x` and `i`. `:help` and the action list set the flag. Replacing the text from here still
 * goes through [VsCodeEditor.replaceString], which is the host's own write and does not ask.
 *
 * How a kind opens — exclusive, reused, a viewer — is on the kind. `ControlCharsEditor` and
 * `SubstitutePreview` have no caller on this host; the preview is the one kind that still opens
 * while an exclusive buffer is up, and [VirtualBufferKind.viewer] records that its content can be
 * replaced. There is no viewer API here to hang that on.
 */
internal class VsCodeVirtualBuffers(
  private val hostCommands: HostCommandRunner,
  /** How a document is opened. Injectable so a test can drive this without a window. */
  private val openDocument: (String, (TextDocument) -> Unit) -> Unit = ::openUntitledDocument,
) : VirtualBufferGroup {

  /** What is open, by the document identity the rest of this host keys editors on. */
  private val open: MutableMap<String, Buffer> = mutableMapOf()

  private class Buffer(val kind: VirtualBufferKind, val originalPath: String?, val document: TextDocument)

  override fun open(
    context: ExecutionContext,
    editor: VimEditor,
    kind: VirtualBufferKind,
    content: String,
    focus: Boolean,
    caretLine: Int,
  ) {
    // A second `:actionlist` or `:help` is the same buffer with new text. Checked before the
    // exclusive refusal, so the one already open is updated rather than reported as in the way.
    if (kind.reuseOpen) {
      val existing = open.entries.firstOrNull { it.value.kind == kind }
      if (existing != null) {
        refresh(context, kind, content)
        placeCaret(existing.key, caretLine)
        if (focus) showDocument(existing.value.document, caretLine)
        return
      }
    }
    // An exclusive buffer is the command-line window or the control-chars editor. Anything else
    // opened while one is up gets that buffer's message — `:help` inside `q:` is E1292 — except the
    // substitute preview, which is transient and opens beside them.
    if (kind != VirtualBufferKind.SubstitutePreview) {
      val blocking = open.values.firstOrNull { it.kind.exclusive }
      if (blocking != null) {
        injector.messages.showErrorMessage(editor, blocking.kind.alreadyOpenMessage)
        return
      }
    }

    val originalPath = editor.getPath()
    openDocument(content) { document ->
      open[identityOf(document)] = Buffer(kind, originalPath, document)
      // `focus` is false only for the substitute preview, which cannot reach this host - so this
      // always shows and focuses, and the parameter is honoured rather than assumed away.
      if (focus) showDocument(document, caretLine)
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
    val editor = editorOf(identity) ?: return
    if (editor.text().contentEquals(content)) return
    // [VsCodeEditor.replaceString] does not ask [VsCodeEditor.isWritable], so a read-only kind can
    // still take a new listing. A user key cannot: that path asks first.
    editor.replaceString(0, editor.text().length, content)
  }

  /** The caret goes to the start of [caretLine]. A reused `:help {subject}` is how it moves. */
  private fun placeCaret(identity: String, caretLine: Int) {
    val editor = editorOf(identity) ?: return
    val line = caretLine.coerceIn(0, editor.lineCount() - 1)
    editor.primaryCaret().moveToOffset(editor.getLineStartOffset(line))
  }

  private fun editorOf(identity: String): VsCodeEditor? =
    injector.editorGroup.getEditors().filterIsInstance<VsCodeEditor>().firstOrNull { it.getPath() == identity }

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

/**
 * Shows the document and puts the caret on [caretLine], which is how `:help {subject}` arrives at its tag.
 *
 * The caret is placed inside the promise's callback, because that is the first moment there is an editor to place it
 * in - `showTextDocument` is what hands one over. Line zero is left alone, which is where a new editor already is.
 */
private fun showDocument(document: TextDocument, caretLine: Int = 0) {
  window.showTextDocument(document).then(
    { shown: TextEditor ->
      if (caretLine > 0) {
        val position = Position(caretLine.coerceAtMost((document.lineCount - 1).coerceAtLeast(0)), 0)
        shown.selections = arrayOf(Selection(position, position))
        shown.revealRange(Range(position, position), TextEditorRevealType.InCenterIfOutsideViewport)
      }
    },
    { _: Any? -> },
  )
}
