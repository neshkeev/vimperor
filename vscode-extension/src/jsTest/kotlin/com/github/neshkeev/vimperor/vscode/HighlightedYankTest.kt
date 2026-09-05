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
import com.maddyhome.idea.vim.extension.ExtensionBean
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `vim-highlightedyank`: the text a yank covered flashes, so you can see what you took.
 *
 * The first bundled extension on this host that needs a *timer*. Everything else here happens
 * inside the keystroke that asked for it; a flash that goes away is a flash and a timer, and the
 * timer is `VimApplication.schedule` - IntelliJ's `Alarm`, VS Code's `setTimeout`.
 *
 * Most of these are synchronous, because most of what the extension does is. The one that is not is
 * the fade itself, and it is written the way the plugin's own timing tests are: a duration well
 * below the wait, so the margin is wide rather than exact.
 */
class HighlightedYankTest {

  private class Session(text: String = "one two three") {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }
    val editor = host.editorFor(fake)

    init {
      KeyHandler.getInstance().fullReset(editor)
      fake.decorations.clear()
      injector.extensionLoader.enableExtension(
        ExtensionBean("highlightedyank", VsCodeExtensions.PLUGIN_ID, "init", ""),
      )
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    fun key(stroke: String) = host.key(fake, stroke)

    fun script(line: String) =
      injector.vimscriptExecutor.execute(line, editor, VsCodeExecutionContext, skipHistory = true)

    /** Every decoration type that is painting something, and the ranges it paints. */
    fun painting(): List<Pair<dynamic, List<Range>>> =
      fake.decorations.entries.filter { it.value.isNotEmpty() }.map { Pair(it.key.asDynamic(), it.value) }
  }

  // ---- what a yank puts on the screen -----------------------------------------------------------

  @Test
  fun `test a yank highlights what it took`() {
    val session = Session()

    session.type("yiw")

    val painting = session.painting()
    assertEquals(1, painting.size)
    val range = painting.single().second.single()
    assertEquals(0, range.start.character)
    assertEquals(3, range.end.character, "`one`, and not the space after it")
  }

  @Test
  fun `test a linewise yank highlights the line`() {
    val session = Session("one\ntwo\n")

    session.type("yy")

    val range = session.painting().single().second.single()
    assertEquals(0, range.start.line)
    assertEquals(1, range.end.line, "the newline is part of a linewise yank")
  }

  /** From the plugin's docs: a new yank *replaces* the old highlighting rather than adding to it. */
  @Test
  fun `test a second yank replaces the first highlight`() {
    val session = Session()

    session.type("yiw")
    session.type("wyiw")

    val painting = session.painting()
    assertEquals(1, painting.size, "one highlight, not two")
    assertEquals(4, painting.single().second.single().start.character, "and it is on the second word")
  }

  /** The other half of that sentence: the user starts editing and the old highlighting goes. */
  @Test
  fun `test entering insert mode clears the highlight`() {
    val session = Session()
    session.type("yiw")
    assertEquals(1, session.painting().size)

    session.type("i")

    assertTrue(session.painting().isEmpty())
  }

  // ---- the colours -------------------------------------------------------------------------------

  /**
   * With no colour set the host paints a search match in its *theme's* colour, which is why this
   * cannot be a hex: `editor.findMatchHighlightBackground` is a name VS Code resolves when it
   * paints, so it follows the theme where a literal would not.
   */
  @Test
  fun `test the default colour is the theme's find-match colour`() {
    val session = Session()

    session.type("yiw")

    val options = session.painting().single().first.options
    assertTrue(
      jsTypeOf(options.backgroundColor) == "object",
      "expected a ThemeColor, got ${options.backgroundColor}",
    )
  }

  @Test
  fun `test a colour the user set is used instead`() {
    val session = Session()
    session.script("let g:highlightedyank_highlight_color = 'rgba(160, 160, 160, 155)'")

    session.type("yiw")

    val options = session.painting().single().first.options
    assertEquals("#a0a0a09b", options.backgroundColor, "rgba turned into CSS's eight-digit hex")
  }

  @Test
  fun `test a foreground colour is used when it is set`() {
    val session = Session()
    session.script("let g:highlightedyank_highlight_foreground_color = 'rgba(0, 0, 0, 255)'")

    session.type("yiw")

    assertEquals("#000000ff", session.painting().single().first.options.color)
  }

  /**
   * A colour that cannot be read has to say so. The config that set it ran with
   * `indicateErrors = false`, so nothing else will, and the symptom on its own - no flash - looks
   * exactly like the extension not being enabled.
   */
  @Test
  fun `test an unreadable colour falls back rather than failing`() {
    val session = Session()
    session.script("let g:highlightedyank_highlight_color = 'not a colour'")

    session.type("yiw")

    val options = session.painting().single().first.options
    assertTrue(jsTypeOf(options.backgroundColor) == "object", "back to the theme's colour")
  }

  // ---- the timer ---------------------------------------------------------------------------------

  /**
   * The fade, which is the reason `VimApplication.schedule` exists.
   *
   * Ten milliseconds against a hundred-millisecond wait: the assertion is that it happened, not
   * when, and the margin is wide enough that a slow machine does not turn this red.
   */
  @Test
  fun `test the highlight goes away on its own`(): Promise<Unit> {
    val session = Session()
    session.script("let g:highlightedyank_highlight_duration = '10'")

    session.type("yiw")
    assertEquals(1, session.painting().size, "up straight away")

    return after(100) {
      assertTrue(session.painting().isEmpty(), "and gone once the duration has passed")
    }
  }

  /** From the plugin's docs: a negative duration makes the highlight persistent. */
  @Test
  fun `test a negative duration never fades`(): Promise<Unit> {
    val session = Session()
    session.script("let g:highlightedyank_highlight_duration = '-1'")

    session.type("yiw")

    return after(100) {
      assertEquals(1, session.painting().size, "still there, because nothing was scheduled")
    }
  }

  // ---- turning it off ----------------------------------------------------------------------------

  /** Its listeners go on `listenersNotifier` directly, so no owner covers them. */
  @Test
  fun `test disabling it stops the highlighting`() {
    val session = Session()
    session.type("yiw")
    assertEquals(1, session.painting().size)

    injector.extensionLoader.disableExtension("highlightedyank")
    session.type("wyiw")

    assertTrue(session.painting().isEmpty(), "the highlight is off and no new one is put up")
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
