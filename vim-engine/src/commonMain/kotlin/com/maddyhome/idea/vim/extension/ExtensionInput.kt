/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.helper.isCloseKeyStroke
import com.maddyhome.idea.vim.key.VimKeyCodes
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.key.interceptors.VimInputInterceptorBase

/**
 * Reads [count] characters straight from the keyboard, then calls [onInput].
 *
 * This is `getchar()` as an extension actually wants it: `s` in `vim-sneak` takes two characters,
 * `cs"'` in `vim-surround` takes two one after another. Both were written against
 * `injector.keyGroup.getChar`, which *blocks* until a key arrives.
 *
 * ## Why blocking was never going to travel
 *
 * IntelliJ answers `getChar` by pumping a nested event loop, and under test by reading
 * `TestInputModel`. Neither has an equivalent on a runtime with one thread and no nested loop, so
 * the VS Code host answers `null` and an extension built on it silently does nothing. That is what
 * kept `surround` and `sneak` in the plugin, and calling it "blocked on `getchar()`" was near enough
 * to right that it went unexamined for a long time - the real shape is narrower and had an answer
 * already sitting in the engine.
 *
 * The engine's modal input is that answer. `ModalInputConsumer` asks before *every* keystroke
 * whether a prompt is open and hands the key to its interceptor, so nothing has to wait for
 * anything: the extension returns, the next key arrives through the ordinary path, and [onInput]
 * runs when enough of them have. Both hosts already implement it - it is what `:s///c` asks its
 * question through.
 *
 * ## What a caller has to know
 *
 * [onInput] runs *later*, on a subsequent keystroke, and may never run at all: `<Esc>` cancels and
 * the callback is dropped. An operator function that used to compute its answer inside `apply` has
 * to answer before it knows, which is the one real cost of the change.
 *
 * [label] is what the prompt shows. Vim shows nothing while `s` waits for its two characters, so
 * the default is nothing.
 */
public fun readCharacters(
  count: Int,
  editor: VimEditor,
  context: ExecutionContext,
  label: String = "",
  onInput: (String) -> Unit,
) {
  require(count > 0) { "readCharacters needs a positive count, got $count" }
  injector.modalInput.create(editor, context, label, CharactersInterceptor(count, onInput))
}

/** [readCharacters] for the common case of one. */
public fun readCharacter(
  editor: VimEditor,
  context: ExecutionContext,
  label: String = "",
  onInput: (Char) -> Unit,
) {
  readCharacters(1, editor, context, label) { onInput(it.single()) }
}

private class CharactersInterceptor(
  private val count: Int,
  private val onInput: (String) -> Unit,
) : VimInputInterceptorBase<String>() {

  private val typed = StringBuilder()
  private var cancelled = false

  override fun buildInput(key: VimKeyStroke): String? {
    // `<Esc>` abandons the whole thing, which is what it does at every other Vim prompt. The
    // interceptor still has to *complete* so that the prompt closes, so the cancellation is carried
    // in a flag rather than by returning null forever.
    if (key.isCloseKeyStroke()) {
      cancelled = true
      return ""
    }
    if (key.keyChar == VimKeyCodes.CHAR_UNDEFINED) return null
    typed.append(key.keyChar)
    return if (typed.length >= count) typed.toString() else null
  }

  override fun executeInput(input: String, editor: VimEditor, context: ExecutionContext) {
    // Before the callback, not after: the callback may run normal-mode keys of its own, and a
    // prompt still open when they arrive would swallow the first of them.
    onFinish()
    if (!cancelled) onInput(input)
  }
}
