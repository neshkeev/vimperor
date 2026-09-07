/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `<C-W>+ - < > _ | =` and `:resize`, which were IntelliJ's alone.
 *
 * Seven keys and an ex command, and none of them had a line of IntelliJ in the part that matters:
 * the keys, the counts, and `:resize`'s argument forms are all Vim, and only the arithmetic that
 * turns "three rows taller" into something a host can be told belongs to the host. So the three
 * files moved to `vim-engine` and `VimWindowResizeService` is what is left behind - IntelliJ's
 * splitters on one side, VS Code's editor groups on the other.
 *
 * What a test can check here is which command was sent and how many times, not the resulting size:
 * the stub host has no layout. Whether VS Code's `increaseViewHeight` grows a group by the right
 * amount is VS Code's business - and it is the amount nobody can name, which is the whole reason
 * `Absolute` is refused rather than approximated.
 */
class WindowResizeTest {

  private class RecordingSink : MessageSink {
    val errors: MutableList<String> = mutableListOf()
    override fun message(text: String?) {}
    override fun error(text: String?) { text?.let { errors += it } }
    override fun status(text: String?) {}
  }

  private class Session {
    val fake = FakeEditor("one\ntwo\n")
    val sink = RecordingSink()
    val host = VimHost(sink = sink).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      commandsExecuted().length = 0
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(notation: String) = host.key(fake, notation)

    /** `<C-W>` arrives as a bound key; what follows it is ordinary typing. */
    fun windowKey(rest: String) {
      key("<C-W>")
      type(rest)
    }

    fun ex(command: String) {
      ":$command".forEach { host.type(fake, it.toString()) }
      key("<CR>")
    }
  }

  // ---- relative, which is the shape that carries over -------------------------------------------

  @Test
  fun `test C-W plus grows the group`() {
    val session = Session()

    session.windowKey("+")

    assertEquals(listOf(VsCodeCommands.INCREASE_VIEW_HEIGHT), executed())
  }

  @Test
  fun `test C-W minus shrinks it`() {
    val session = Session()

    session.windowKey("-")

    assertEquals(listOf(VsCodeCommands.DECREASE_VIEW_HEIGHT), executed())
  }

  @Test
  fun `test the width keys go the other way`() {
    val session = Session()

    session.windowKey(">")
    assertEquals(listOf(VsCodeCommands.INCREASE_VIEW_WIDTH), executed())

    commandsExecuted().length = 0
    session.windowKey("<")
    assertEquals(listOf(VsCodeCommands.DECREASE_VIEW_WIDTH), executed())
  }

  /**
   * A count is Vim's number of rows and VS Code's number of steps, which is the honest translation
   * available: the unit differs, but "three times bigger than one press" is what was asked for.
   */
  @Test
  fun `test a count repeats the step`() {
    val session = Session()

    // `3<C-W>+`: the count goes in front, which is where Vim documents it and the only place the
    // engine takes it - `<C-W>3+` counts nothing.
    session.type("3")
    session.key("<C-W>")
    session.type("+")

    assertEquals(List(3) { VsCodeCommands.INCREASE_VIEW_HEIGHT }, executed())
  }

  // ---- maximise and equalise --------------------------------------------------------------------

  @Test
  fun `test C-W underscore maximises`() {
    val session = Session()

    session.windowKey("_")

    assertEquals(listOf(VsCodeCommands.MAXIMIZE_EDITOR_GROUP), executed())
  }

  @Test
  fun `test C-W bar maximises the width`() {
    val session = Session()

    session.windowKey("|")

    assertEquals(listOf(VsCodeCommands.TOGGLE_EDITOR_WIDTHS), executed())
  }

  /** `<C-W>=` evens the widths and not the heights, because VS Code has no command for those. */
  @Test
  fun `test C-W equals evens the widths`() {
    val session = Session()

    session.windowKey("=")

    assertEquals(listOf(VsCodeCommands.EVEN_EDITOR_WIDTHS), executed())
  }

  // ---- the ex command ---------------------------------------------------------------------------

  @Test
  fun `test resize with a signed argument steps`() {
    val session = Session()

    session.ex("resize +2")

    assertEquals(List(2) { VsCodeCommands.INCREASE_VIEW_HEIGHT }, executed())
    assertEquals(emptyList(), session.sink.errors)
  }

  @Test
  fun `test resize with no argument maximises`() {
    val session = Session()

    session.ex("resize")

    assertEquals(listOf(VsCodeCommands.MAXIMIZE_EDITOR_GROUP), executed())
  }

  /**
   * `:vertical` is a modifier in the engine - it raises a flag and runs the rest of the line - so
   * `:vertical resize` is the same command reading that flag, exactly as Vim arranges it.
   */
  @Test
  fun `test vertical resize changes the width instead`() {
    val session = Session()

    session.ex("vertical resize +2")

    assertEquals(List(2) { VsCodeCommands.INCREASE_VIEW_WIDTH }, executed())
  }

  // ---- the shape that does not carry over --------------------------------------------------------

  /**
   * `:resize 20` asks for twenty rows, and VS Code has no command that takes a size. Guessing at a
   * number of steps would be an error nobody could see, so this says so - which is more than the
   * command did before, when it was absent from this host altogether.
   */
  @Test
  fun `test an absolute size is refused rather than guessed at`() {
    val session = Session()

    session.ex("resize 20")

    assertEquals(emptyList(), executed(), "nothing should have been sent")
    assertEquals(1, session.sink.errors.size, "got ${session.sink.errors}")
    assertTrue(session.sink.errors[0].contains("rows"), session.sink.errors[0])
  }

  @Test
  fun `test an absolute width says columns`() {
    val session = Session()

    session.ex("vertical resize 80")

    assertEquals(emptyList(), executed())
    assertTrue(session.sink.errors[0].contains("columns"), session.sink.errors[0])
  }
}

private fun commandsExecuted(): dynamic = js("require('vscode')").commands.executed

private fun executed(): List<String> {
  val raw = commandsExecuted()
  return (0 until (raw.length as Int)).map { raw[it] as String }
}
