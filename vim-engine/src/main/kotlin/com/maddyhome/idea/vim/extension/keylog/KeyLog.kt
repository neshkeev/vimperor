/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.keylog

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.VimKeyTypedListener
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType

public const val KEYLOG: String = "keylog"

private const val KEYLOG_FILE_VARIABLE_NAME = "keylog_file"
private const val DEFAULT_KEYLOG_FILE_NAME = "vimperor-keylog.txt"

/** How long the keys pile up before they are written. Long enough that a fast typist costs one write. */
private const val FLUSH_AFTER_MS = 1000

/**
 * Every key you type, written to a file in Vim's own key notation - Vim's `-w {scriptout}`.
 *
 * ```vim
 * set keylog
 * let g:keylog_file = "/tmp/keys.txt"   " defaults to vimperor-keylog.txt beside the config
 * ```
 *
 * What it is for is finding out which commands you actually use, which is the premise of
 * [vimlex](https://github.com/dstokes/vimlex) for Vim. Ported from IdeaVim (VIM-1306).
 *
 * **Only what you typed.** A mapping's right-hand side, a macro being played back, a `.` repeat and a
 * replayed mapping prefix are not keys you pressed, and none of them is reported: the stream comes
 * from [com.maddyhome.idea.vim.KeyHandler.handleUserKey], which a host calls for a real keystroke and
 * not for anything it is feeding back. `KeySource` cannot answer this on its own - a `.` repeat is
 * `TYPED` too, because as far as the command machinery is concerned it was.
 *
 * **It buffers.** A listener here runs inside the keystroke, so writing from it would put the disk in
 * front of the caret. The keys collect in memory and go out on a timer, which is what IdeaVim does
 * with a scheduled executor; this uses the engine's own [com.maddyhome.idea.vim.api.VimApplication]
 * timer, so it works on both hosts.
 *
 * **A typed `<` is written `<lt>`.** Otherwise the log could not be read back: `<` is how notation
 * starts, and a file that mixes the two cannot be parsed into keys again.
 */
@VimPlugin(name = KEYLOG)
public fun VimInitApi.init(): Unit = registerKeyLog()

private val listener = KeyLogListener()

public fun registerKeyLog() {
  injector.listenersNotifier.keyTypedListeners.add(listener)
}

/**
 * Undoes [registerKeyLog], and writes out what is still buffered.
 *
 * Named rather than left to the loader's owner-based teardown for the reason `highlightedyank`'s is:
 * the listener goes straight onto `listenersNotifier` rather than through the thin API's listener
 * scope, so nothing removes it by owner.
 */
public fun disposeKeyLog() {
  injector.listenersNotifier.keyTypedListeners.remove(listener)
  listener.flush()
}

private class KeyLogListener : VimKeyTypedListener {
  private val pending = StringBuilder()
  private var scheduled = false

  override fun keyTyped(editor: VimEditor, key: VimKeyStroke) {
    pending.append(notationOf(key))
    if (scheduled) return
    scheduled = true
    injector.application.schedule(FLUSH_AFTER_MS) {
      scheduled = false
      flush()
    }
  }

  fun flush() {
    if (pending.isEmpty()) return
    val text = pending.toString()
    pending.clear()
    // Dropped rather than retried if the write fails: a keylog that reports its own failure on every
    // keystroke would be worse than one that quietly stops.
    injector.fileSystem.appendText(fileName(), text)
  }

  /** `<` is the one character that cannot be written as itself. */
  private fun notationOf(key: VimKeyStroke): String =
    if (key.keyChar == '<') "<lt>" else injector.parser.toKeyNotation(key)

  private fun fileName(): String {
    val configured = injector.variableService.getGlobalVariableValue(KEYLOG_FILE_VARIABLE_NAME)
    return (configured as? VimDataType)?.toOutputString()?.takeIf { it.isNotBlank() }
      ?: DEFAULT_KEYLOG_FILE_NAME
  }
}
