/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.extension
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.action.change.Extension
import com.maddyhome.idea.vim.action.change.VimRepeater
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.helper.inRepeatMode
import com.maddyhome.idea.vim.helper.isCloseKeyStroke
import com.maddyhome.idea.vim.key.VimKeyCodes
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.key.interceptors.VimInputInterceptorBase

/**
 * Keys straight from the keyboard, until [isComplete] says there are enough of them.
 *
 * This is `getchar()` as an extension actually wants it: `s` in `vim-sneak` takes two characters,
 * and `ys{motion}` in `vim-surround` takes one - unless that one is `<`, in which case it takes a
 * whole tag name after it. Both were written against `injector.keyGroup.getChar`, which *blocks*
 * until a key arrives.
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
 * ## One session, however many keys
 *
 * The prompt stays open until [isComplete] is satisfied, however many keys that takes. That matters
 * more than it looks. The obvious alternative - read one character, and if it turns out to be `<`
 * open a *second* prompt for the tag name - loses a keystroke in the handover: measured, `ysiw<em>`
 * arrived as `<m>`, the `e` having gone to the editor as a motion. There is nothing to hand over
 * here.
 *
 * ## Why the decision and the work are separate
 *
 * [isComplete] decides and must do nothing else; [onInput] does the work. The prompt is closed
 * between them, and it has to be: [onInput] typically runs normal-mode keys of its own - a
 * surround runs ``  `[ `` to jump back - and a prompt still open would swallow the first of them.
 *
 * ## What a caller has to know
 *
 * [onInput] runs *later*, on a subsequent keystroke, and may never run at all: `<Esc>` cancels. An
 * operator function that used to compute its answer inside `apply` has to answer before it knows.
 *
 * [onCancel] is that `<Esc>`, and a caller that set anything up before asking has to undo it there.
 * The prompt used to close silently, which was enough while every caller had nothing to undo. A jump
 * motion has two things: labels on the screen, and - under an operator - a `d` still waiting for its
 * motion, which would take the next key typed as one.
 *
 * [onProgress] sees each key that did not finish the input, while the prompt is still open. It is for
 * redrawing, never for running keys - a prompt still open would swallow them, which is the reason
 * [isComplete] and [onInput] are kept apart. A jump motion's labels narrow as each key is typed, and
 * redrawing them here is what lets that happen inside one prompt rather than by opening a second one
 * and losing a keystroke in the handover.
 *
 * [label] is what the prompt shows. Vim shows nothing while `s` waits for its two characters, so
 * the default is nothing.
 */
public fun readKeys(
  editor: VimEditor,
  context: ExecutionContext,
  label: String = "",
  isComplete: (List<VimKeyStroke>) -> Boolean,
  onProgress: (List<VimKeyStroke>) -> Unit = {},
  onCancel: () -> Unit = {},
  onInput: (List<VimKeyStroke>) -> Unit,
) {
  if (editor.inRepeatMode) {
    replayKeys(isComplete, onInput)
    return
  }
  injector.modalInput.create(editor, context, label, KeysInterceptor(isComplete, onProgress, onCancel, onInput))
}

/**
 * `.` does not replay keystrokes; it re-runs the extension handler. So the keys the handler asked
 * for the first time have to be given back to it, and `Extension` is where the engine keeps them -
 * `ds"` remembers the `"` so that `.` can delete the next pair of quotes without asking again.
 *
 * Synchronous, which is the point: a repeat has all its input already, so the handler does its work
 * before returning, exactly as it did when the read blocked.
 */
private fun replayKeys(
  isComplete: (List<VimKeyStroke>) -> Boolean,
  onInput: (List<VimKeyStroke>) -> Unit,
) {
  val typed = mutableListOf<VimKeyStroke>()
  do {
    val key = Extension.consumeKeystroke() ?: error("Not enough keystrokes saved: ${Extension.lastExtensionHandler}")
    typed.add(key)
  } while (!isComplete(typed))
  onInput(typed)
}

/**
 * [readKeys] for the common case: [count] characters, then [onInput].
 *
 * A keystroke with no character of its own - a bare modifier, a function key - is passed over rather
 * than counted.
 */
public fun readCharacters(
  count: Int,
  editor: VimEditor,
  context: ExecutionContext,
  label: String = "",
  onCancel: () -> Unit = {},
  onInput: (String) -> Unit,
) {
  require(count > 0) { "readCharacters needs a positive count, got $count" }
  readKeys(
    editor,
    context,
    label,
    onCancel = onCancel,
    isComplete = { keys -> keys.count { it.keyChar != VimKeyCodes.CHAR_UNDEFINED } >= count },
    onInput = { keys -> onInput(keys.mapNotNull { it.keyChar.takeIf { c -> c != VimKeyCodes.CHAR_UNDEFINED } }.joinToString("")) },
  )
}

/** [readCharacters] for the common case of one. */
public fun readCharacter(
  editor: VimEditor,
  context: ExecutionContext,
  label: String = "",
  onCancel: () -> Unit = {},
  onInput: (Char) -> Unit,
) {
  readCharacters(1, editor, context, label, onCancel) { onInput(it.single()) }
}

/**
 * Runs [action] as an editor command, which is what a keystroke's own work runs inside.
 *
 * The reason this is here rather than left to each caller: an extension that used to block for its
 * input did its work inside the command the *original* keystroke set up, and IntelliJ refuses a
 * document change made outside one - "Must not change document outside command or undo-transparent
 * action", which is what 58 of `vim-surround`'s tests said the moment its input stopped blocking.
 * Deferring the work to a later keystroke has to put it back in a command, and doing that here
 * means every user of these helpers gets it rather than remembering to.
 */
private fun asExtensionCommand(editor: VimEditor, action: () -> Unit) {
  injector.actionExecutor.executeCommand(editor, action, "Vim extension input", null)
}

private class KeysInterceptor(
  private val isComplete: (List<VimKeyStroke>) -> Boolean,
  private val onProgress: (List<VimKeyStroke>) -> Unit,
  private val onCancel: () -> Unit,
  private val onInput: (List<VimKeyStroke>) -> Unit,
) : VimInputInterceptorBase<List<VimKeyStroke>>() {

  private val typed = mutableListOf<VimKeyStroke>()

  override fun buildInput(key: VimKeyStroke): List<VimKeyStroke>? {
    // `<Esc>` abandons the whole thing, which is what it does at every other Vim prompt. The
    // interceptor still has to *complete* so that the prompt closes, so cancellation is an input of
    // its own - the empty one, which no finished input can be - rather than a refusal to build one.
    if (key.isCloseKeyStroke()) return emptyList()
    typed.add(key)
    // Remembered as it arrives, so that `.` can hand it back - see `replayKeys`. Recorded even when
    // the sequence turns out to be incomplete, because the replay reads the same number of keys the
    // same way.
    Extension.addKeystroke(key)
    if (isComplete(typed)) return typed.toList()
    // Still open, which is the one moment a redraw can happen without anything being swallowed.
    onProgress(typed.toList())
    return null
  }

  override fun executeInput(input: List<VimKeyStroke>, editor: VimEditor, context: ExecutionContext) {
    // Closed before the callback, never after: see `readKeys`.
    onFinish()
    if (input.isEmpty()) {
      onCancel()
      return
    }

    // `ToHandlerMappingInfo.execute` marks the change as an extension's *after* running the handler,
    // so that any normal-mode commands the handler ran along the way - `ds"` runs `di"` - do not
    // leave themselves as what `.` repeats. The handler now finishes here instead, one or more
    // keystrokes later, so the marking has to be put back the same way or `.` after `dsb` repeats
    // `di(`. Measured: four of vim-surround's repeat tests said exactly that.
    val handler = Extension.lastExtensionHandler
    val wasRepeatHandler = VimRepeater.repeatHandler
    asExtensionCommand(editor) { onInput(input) }
    if (handler != null) {
      Extension.lastExtensionHandler = handler
      VimRepeater.repeatHandler = wasRepeatHandler
    }
  }
}
