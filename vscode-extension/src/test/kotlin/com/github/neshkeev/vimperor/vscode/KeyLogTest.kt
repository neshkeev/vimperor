/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.extension.keylog.disposeKeyLog
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `keylog`: every key you type, appended to a file in Vim's own notation - Vim's `-w {scriptout}`.
 *
 * Ported from IdeaVim (VIM-1306). What it is for is finding out which commands you actually use.
 *
 * The part worth testing is what it leaves *out*. A mapping's right-hand side, a macro being played
 * back and a `.` repeat are not keys anybody pressed, and `KeySource` cannot tell them from real
 * ones - a `.` repeat is `TYPED` too, because as far as the command machinery is concerned it was.
 * So the stream comes from `KeyHandler.handleUserKey`, which the host calls for a keystroke and not
 * for anything it is feeding back, and these tests are mostly about that distinction holding.
 */
class KeyLogTest {

  private class Session(text: String = "one two three") {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }
    val logFile: String = temporaryDirectory() + "/keys.txt"

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      script("let g:keylog_file = '$logFile'")
      // `set keylog`, which is how IdeaVim's own documentation turns an extension on
      script("set keylog")
    }

    /** An ex command, typed at the prompt, so the whole path is the user's. */
    fun script(line: String) {
      type(":$line")
      key("<CR>")
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)

    /** Writes out whatever is buffered, which is what the timer would have done. */
    fun flush() = disposeKeyLog()

    /** Everything written so far, starting after the `:let` the session typed to set the file. */
    val logged: String
      get() = if (nodeFs.existsSync(logFile) as Boolean) nodeFs.readFileSync(logFile, "utf8") as String else ""
  }

  @Test
  fun `test the keys you type are written in Vim notation`() {
    val session = Session()

    session.type("dw")
    session.flush()

    assertEquals("dw", session.logged)
  }

  @Test
  fun `test a named key is written as its notation`() {
    val session = Session()

    session.type("i")
    session.key("<Esc>")
    session.flush()

    assertEquals("i<Esc>", session.logged)
  }

  /** `<` is the one character that cannot be written as itself, or the log could not be read back. */
  @Test
  fun `test a typed less-than is written as lt`() {
    val session = Session()

    session.type("i")
    session.type("<")
    session.flush()

    assertEquals("i<lt>", session.logged)
  }

  @Test
  fun `test what the engine feeds back to itself is not logged`() {
    val session = Session()

    // `<C-V>065` inserts an `A` by its decimal code, and the engine hands the `x` back to be
    // handled after the literal - a key nobody pressed.
    session.type("i")
    session.key("<C-V>")
    session.type("065x")
    session.flush()

    assertTrue(
      session.logged.endsWith("065x") || session.logged.contains("065"),
      "the typed keys are there. Got: ${session.logged}",
    )
    assertEquals(1, session.logged.count { it == 'x' }, "and the posted `x` is not there twice")
  }

  /**
   * A `.` is one key in the log, not the keys it repeats.
   *
   * This is the distinction the whole extension turns on. `.` replays the last change, and those
   * replayed keys arrive at the key handler as `KeySource.TYPED` - so a log driven by the source
   * would show `dw` twice and no `.` at all.
   */
  @Test
  fun `test a dot repeat is logged as the dot and not as what it repeats`() {
    val session = Session("one two three four")

    session.type("dw")
    session.type(".")
    session.flush()

    assertEquals("dw.", session.logged)
  }

  @Test
  fun `test it writes on its own once the keys stop`(): Promise<Unit> {
    val session = Session()

    session.type("x")

    return after(1200) {
      assertEquals("x", session.logged, "the buffer goes out on a timer, not on every keystroke")
    }
  }

  @Test
  fun `test turning it off writes out what was buffered`() {
    val session = Session()

    session.type("x")
    injector.extensionLoader.disableExtension("keylog")

    assertEquals("x", session.logged, "the pending keys are written rather than dropped")
  }

  @Test
  fun `test nothing is logged while it is off`() {
    val session = Session()
    injector.extensionLoader.disableExtension("keylog")

    session.type("dw")
    session.flush()

    assertEquals("", session.logged)
  }

  private fun after(millis: Int, block: () -> Unit): Promise<Unit> =
    Promise { resolve, _ ->
      setTimeout({
        block()
        resolve(Unit)
      }, millis)
    }
}

private external fun setTimeout(handler: () -> Unit, timeout: Int): Int
