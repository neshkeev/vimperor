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
import com.maddyhome.idea.vim.api.VimOutputPanel
import com.maddyhome.idea.vim.api.VimOutputPanelService
import com.maddyhome.idea.vim.api.MessageType
import com.maddyhome.idea.vim.api.injector

/**
 * Where `:registers`, `:marks` and `:!` output go.
 *
 * Vim's output panel takes over the screen and then takes keys: space pages, `q` closes, and
 * nothing else happens until it does. VS Code has no equivalent, and building one would mean a
 * webview that competes with the editor for focus.
 *
 * So this is an output channel, which is VS Code's own answer to "a program produced text". The
 * consequence is deliberate and worth stating: the panel never *holds* keys. `getCurrentOutputPanel`
 * returns null even while text is showing, so the engine keeps interpreting keystrokes as Vim
 * commands rather than waiting for a hit-enter prompt that this host has no way to draw. A user who
 * runs `:registers` can keep typing, which is the behaviour a VS Code user expects and not the
 * behaviour Vim has.
 */
class OutputChannelPanel(private val channel: OutputChannel) : VimOutputPanel {

  private val content = StringBuilder()

  override val text: String get() = content.toString()

  /** Vim shows this on the right of the panel - a search count, or "-- More --". */
  override var statusText: String = ""

  override fun addText(text: String, isNewLine: Boolean, messageType: MessageType) {
    if (isNewLine && content.isNotEmpty()) content.append('\n')
    content.append(text)
  }

  /**
   * [requireHitEnter] is Vim waiting for acknowledgement before giving the screen back. There is no
   * screen to give back here, so the text appears and the editor keeps focus.
   */
  override fun show(requireHitEnter: Boolean) {
    content.toString().split('\n').forEach { channel.appendLine(it) }
    if (statusText.isNotEmpty()) channel.appendLine(statusText)
    channel.show(preserveFocus = true)
    content.clear()
  }

  override fun close() {
    content.clear()
  }

  override fun clearText() {
    content.clear()
  }
}

/**
 * Builds output panels, and reports that none is ever *open*.
 *
 * The distinction matters on the key path: the engine asks whether a panel is holding the screen
 * before it interprets a keystroke, and here the answer is always no - see [OutputChannelPanel].
 */
class OutputChannelPanelService(private val channel: OutputChannel) : VimOutputPanelService {

  private var current: OutputChannelPanel? = null

  override fun create(editor: VimEditor, context: ExecutionContext): VimOutputPanel =
    OutputChannelPanel(channel).also { current = it }

  override fun getOrCreate(editor: VimEditor, context: ExecutionContext): VimOutputPanel =
    current ?: create(editor, context)

  /** Never holds the screen, so never holds keys. */
  override fun getCurrentOutputPanel(): VimOutputPanel? = null

  override fun getActiveOutputPanelHeight(): Int? = null

  override fun output(
    editor: VimEditor,
    context: ExecutionContext,
    text: String,
    messageType: MessageType,
  ) {
    if (injector.messages.hides(messageType)) return
    val shown = injector.messages.filterOutput(text) ?: return
    val panel = getOrCreate(editor, context)
    panel.addText(shown, isNewLine = true, messageType = messageType)
    panel.show()
  }

  override fun clear(editor: VimEditor, context: ExecutionContext) {
    current?.clearText()
  }
}
