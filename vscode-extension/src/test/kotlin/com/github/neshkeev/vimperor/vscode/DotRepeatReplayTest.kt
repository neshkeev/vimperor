/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.change.VimRedoBuffer
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `.` as a keystroke replay, which is how Vim does it (`redobuff` in getchar.c).
 *
 * This used to keep the last change as a `Command` and repeat it by handing that command back to
 * the action executor. It is the keys now, fed back through the key handler the way `@` replays a
 * macro. Ported from IdeaVim (VIM-1153).
 *
 * The motivation upstream gives is one this host does not share, and that is worth saying plainly:
 * IdeaVim wanted `.` to work when the *IDE* contributed part of the change - an auto-closed bracket,
 * a reformat, the closing tag `XmlTagNameSynchronizer` renames - and a `Command` carries only the
 * engine's half. Vimperor intercepts `type`, so VS Code's own typing features never fire during a
 * change and there is no second contributor to ask again.
 *
 * What it buys here is the rendering rules, which are Vim's and now live in one place
 * (`VimRedoBuffer`, Vim's `prep_redo`): a count on the `.` replaces the original rather than
 * multiplying it, `2d3w` repeats as `6dw`, a numbered register walks on, and a Visual change
 * reselects an area of the same size with `1v` rather than re-running its motion.
 */
class DotRepeatReplayTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      VimRedoBuffer.resetAll()
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)
    val content: String get() = fake.document.content
    val recorded: String get() = injector.parser.toKeyNotation(VimRedoBuffer.keysForReplay(0))
  }

  // ---- what gets recorded ------------------------------------------------------------------------

  @Test
  fun `test the keys of the change are what is remembered`() {
    val session = Session("one two three")

    session.type("dw")

    assertEquals("dw", session.recorded)
  }

  /**
   * The prefix is rendered rather than replayed, which is what lets a count on the `.` replace it.
   * `2d3w` comes back as `6dw`: the counts are multiplied into one and the `2` is not in the keys.
   */
  @Test
  fun `test a count is rendered in front of the keys rather than recorded in them`() {
    val session = Session("one two three four five six seven")

    session.type("2d3w")

    assertEquals("6dw", session.recorded)
  }

  @Test
  fun `test an insert is remembered with everything typed into it`() {
    val session = Session("one two")

    session.type("cwfoo")
    session.key("<Esc>")

    assertEquals("cwfoo<Esc>", session.recorded)
  }

  // ---- what the replay does ----------------------------------------------------------------------

  @Test
  fun `test dot repeats a change that ends in an insert`() {
    val session = Session("one two")

    session.type("cwfoo")
    session.key("<Esc>")
    session.type("w.")

    assertEquals("foo foo", session.content)
  }

  @Test
  fun `test dot repeats an insert that deleted forwards`() {
    val session = Session("1234567890")

    session.type("i")
    session.key("<Del>")
    session.key("<Esc>")
    session.type(".")

    assertEquals("34567890", session.content, "one character each time, not two")
  }

  /** `:help .` - a count on the `.` replaces the original rather than multiplying it. */
  @Test
  fun `test a count on the dot replaces the original count`() {
    val session = Session("one two three four five six")

    session.type("3dw")
    session.type("2.")

    assertEquals("six", session.content, "three words, then two - not three then six")
  }

  @Test
  fun `test a forced motion is part of the change`() {
    val session = Session("aa bb cc dd")

    session.type("dvw")
    session.type(".")

    assertEquals("c dd", session.content)
  }

  /**
   * A change made through a mapping repeats by running the mapping again, because what is recorded
   * is the key the user pressed. The same rule a macro follows.
   */
  @Test
  fun `test a change made through a mapping is repeated through the mapping`() {
    val session = Session("one two three")
    session.type(":nnoremap X dw")
    session.key("<CR>")

    session.type("X")
    session.type(".")

    assertEquals("three", session.content)
  }

  @Test
  fun `test the dot is not itself a change, so a second one repeats the first`() {
    val session = Session("one two three four")

    session.type("x")
    session.type("...")

    assertEquals("two three four", session.content, "one `x` and three repeats of it")
  }
}
