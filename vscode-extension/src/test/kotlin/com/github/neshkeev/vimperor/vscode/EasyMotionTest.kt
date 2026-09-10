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
import com.maddyhome.idea.vim.state.mode.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `easymotion`, pressed.
 *
 * `LabelTreeTest` in the engine checks the grouping. This checks the rest, which is where the new
 * seams are: that labels reach the editor through `setDecorations`, that the keys typed next arrive
 * through one prompt and narrow it, that an operator receives the jump as its motion - linewise when
 * the jump is by lines - and that `<Esc>` leaves nothing behind, neither labels nor a `d` still
 * waiting for a motion.
 *
 * The leader is Vim's default `\`, so `\\w` below is `<Leader><Leader>w`.
 *
 * None of this can check where a label is *drawn*: the stub records the decoration and paints
 * nothing. See `VsCodeJumpLabelDisplay`.
 */
class EasyMotionTest {

  private class Session(text: String, caretAt: Int = 0, setup: List<String> = emptyList()) {
    val fake = FakeEditor(text)
    val statuses: MutableList<String> = mutableListOf()
    val host = VimHost(
      sink = object : MessageSink {
        override fun message(text: String?) {}
        override fun error(text: String?) {}
        override fun status(text: String?) {
          statuses += text.orEmpty()
        }
      },
      runCommand = { _, _, onDone -> onDone(true) },
    ).also { it.start() }

    init {
      val position = positionOf(text, caretAt)
      fake.selection = Selection(position, position)
      KeyHandler.getInstance().fullReset(editor)
      setup.forEach { script(it) }
      injector.extensionLoader.enableExtension(ExtensionBean("easymotion", VsCodeExtensions.PLUGIN_ID, "init", ""))
    }

    val editor get() = host.editorFor(fake)
    val caret: Int get() = editor.primaryCaret().offset
    val content: String get() = fake.document.content
    val mode: Mode get() = editor.mode

    fun script(line: String) {
      injector.vimscriptExecutor.execute(line, editor, VsCodeExecutionContext, skipHistory = true)
    }

    /** Typed as a user types, with `<...>` read as one named key. */
    fun type(keys: String) {
      var index = 0
      while (index < keys.length) {
        val close = if (keys[index] == '<') keys.indexOf('>', index) else -1
        if (close > index + 1) {
          host.key(fake, keys.substring(index, close + 1))
          index = close + 1
        } else {
          host.type(fake, keys[index].toString())
          index++
        }
      }
    }

    /** The labels showing, as the label's text to the offset it is drawn at. */
    fun labels(): Map<String, Int> {
      val lines = content.split("\n")
      return fake.decorationOptions.values.flatten()
        .filter { it.renderOptions != undefined }
        .associate { decoration ->
          val start = decoration.range.start
          val offset = lines.take(start.line as Int).sumOf { it.length + 1 } + (start.character as Int)
          (decoration.renderOptions.before.contentText as String) to offset
        }
    }

    /** The `before` attachment a label was drawn with, found by its text: that is where its colours are. */
    fun labelStyle(label: String): dynamic =
      fake.decorationOptions.values.flatten()
        .filter { it.renderOptions != undefined }
        .single { it.renderOptions.before.contentText == label }
        .renderOptions.before

    /** The options of every decoration type painting something, which is where the shading's colour is. */
    fun paintingTypes(): List<dynamic> =
      fake.decorations.entries.filter { it.value.isNotEmpty() }.map { it.key.asDynamic().options }
  }

  // ---- labels -------------------------------------------------------------------------------------

  @Test
  fun `test w labels the word starts after the caret, nearest first`() {
    val session = Session("one two three four")
    session.type("\\\\w")
    assertEquals(mapOf("a" to 4, "s" to 8, "d" to 14), session.labels())
  }

  @Test
  fun `test typing a label jumps there and takes the labels down`() {
    val session = Session("one two three four")
    session.type("\\\\w")
    session.type("s")
    assertEquals(8, session.caret)
    assertEquals(emptyMap(), session.labels())
  }

  @Test
  fun `test a lone target is jumped to without asking`() {
    // vim-easymotion's `s:PromptUser` does the same; anything else is a label to read and type for no
    // choice at all.
    val session = Session("one two")
    session.type("\\\\w")
    assertEquals(4, session.caret)
    assertEquals(emptyMap(), session.labels())
    session.type("x")
    assertEquals("one wo", session.content, "no prompt was left open to swallow the next key")
  }

  @Test
  fun `test b goes backwards, nearest first`() {
    val session = Session("one two three", caretAt = 10)
    session.type("\\\\b")
    assertEquals(mapOf("a" to 8, "s" to 4, "d" to 0), session.labels())
  }

  @Test
  fun `test e labels word ends`() {
    val session = Session("one two three")
    session.type("\\\\e")
    assertEquals(mapOf("a" to 2, "s" to 6, "d" to 12), session.labels())
  }

  @Test
  fun `test j labels the first non-blank of each line below`() {
    val session = Session("one\n  two\n\tthree\nfour")
    session.type("\\\\j")
    assertEquals(mapOf("a" to 6, "s" to 11, "d" to 17), session.labels())
  }

  @Test
  fun `test f labels the character it was given`() {
    val session = Session("one two to")
    session.type("\\\\fo")
    assertEquals(mapOf("a" to 6, "s" to 9), session.labels())
  }

  @Test
  fun `test s looks both ways, nearest first`() {
    val session = Session("o two o", caretAt = 3)
    session.type("\\\\so")
    assertEquals(mapOf("a" to 4, "s" to 6, "d" to 0), session.labels())
  }

  @Test
  fun `test n labels the matches of the last search`() {
    val session = Session("one two x two y two")
    session.type("/two<CR>")
    session.type("\\\\n")
    assertEquals(mapOf("a" to 10, "s" to 16), session.labels())
  }

  @Test
  fun `test only lines on screen are labelled`() {
    // A label nobody can see would take a short key from a target somebody can.
    val session = Session((0 until 30).joinToString("\n") { "line $it" })
    session.fake.viewportHeight = 10
    session.type("\\\\j")
    val lastLineOnScreen = (0 until 9).sumOf { "line $it".length + 1 }
    assertEquals(9, session.labels().size, "got ${session.labels()}")
    assertTrue(session.labels().values.all { it <= lastLineOnScreen }, "got ${session.labels()}")
  }

  @Test
  fun `test more targets than keys opens a group, and the next key narrows it`() {
    // Thirty words: twenty-nine targets for twenty-seven keys, so the last key, `;`, opens a group
    // of three.
    val session = Session((0 until 30).joinToString(" ") { "w" + (it + 10) })
    session.type("\\\\w")
    assertEquals(29, session.labels().size)
    assertEquals(setOf(";a", ";s", ";d"), session.labels().keys.filter { it.length == 2 }.toSet())

    session.type(";")
    assertEquals(mapOf("a" to 108, "s" to 112, "d" to 116), session.labels())

    session.type("s")
    assertEquals(112, session.caret)
  }

  // ---- colours -----------------------------------------------------------------------------------

  @Test
  fun `test a label is a badge in the theme's own colours`() {
    // The first version drew vim-easymotion's red text, which was hardly visible on VS Code's dark
    // background. A badge brings its own background, and a theme colour pair is legible in whatever
    // theme is on.
    val session = Session("one two three four")
    session.type("\\\\w")
    val style = session.labelStyle("a")
    assertEquals("activityBarBadge.background", style.backgroundColor.id)
    assertEquals("activityBarBadge.foreground", style.color.id)
  }

  @Test
  fun `test a label that opens a group is a badge in a second theme colour`() {
    val session = Session((0 until 30).joinToString(" ") { "w" + (it + 10) })
    session.type("\\\\w")
    assertEquals("activityWarningBadge.background", session.labelStyle(";a").backgroundColor.id)
    assertEquals("activityWarningBadge.foreground", session.labelStyle(";a").color.id)
  }

  @Test
  fun `test highlight EasyMotionTarget recolours the labels, as it does in Vim`() {
    val session = Session("one two three four", setup = listOf("highlight EasyMotionTarget guifg=#000000 guibg=#ffd700"))
    session.type("\\\\w")
    val style = session.labelStyle("a")
    assertEquals("#000000", (style.color as String).lowercase())
    assertEquals("#ffd700", (style.backgroundColor as String).lowercase())
  }

  @Test
  fun `test highlight EasyMotionShade recolours the shading`() {
    val session = Session("one two three four", setup = listOf("highlight EasyMotionShade guifg=#555555"))
    session.type("\\\\w")
    val colours = session.paintingTypes().map { (it.color as? String)?.lowercase() }
    assertTrue("#555555" in colours, "got $colours")
  }

  // ---- as a motion -------------------------------------------------------------------------------

  @Test
  fun `test an operator takes the jump as its motion`() {
    val session = Session("one two three")
    session.type("d\\\\w")
    session.type("s")
    assertEquals("three", session.content)
  }

  @Test
  fun `test an inclusive jump takes the character it lands on`() {
    val session = Session("one two three")
    session.type("d\\\\e")
    session.type("s")
    assertEquals(" three", session.content)
  }

  @Test
  fun `test a jump by lines under an operator takes whole lines`() {
    // What `isLinewiseMotion` is for: without it `d` sees two offsets on two lines and takes a ragged
    // slice between them.
    val session = Session("a\nb\nc\nd")
    session.type("d\\\\j")
    session.type("s")
    assertEquals("d", session.content)
  }

  @Test
  fun `test in Visual mode a jump extends the selection`() {
    val session = Session("one two three")
    session.type("v\\\\w")
    session.type("s")
    assertIs<Mode.VISUAL>(session.mode)
    assertEquals(8, session.caret)
  }

  // ---- giving up ----------------------------------------------------------------------------------

  @Test
  fun `test Escape takes the labels down and leaves the caret`() {
    val session = Session("one two three four")
    session.type("\\\\w<Esc>")
    assertEquals(emptyMap(), session.labels())
    assertEquals(0, session.caret)
    session.type("x")
    assertEquals("ne two three four", session.content)
  }

  @Test
  fun `test Escape under an operator leaves no operator waiting`() {
    // Why `readKeys` grew `onCancel`: before it the `d` stayed pending, and the next key - here `w` -
    // became its motion.
    val session = Session("one two three four")
    session.type("d\\\\w<Esc>")
    session.type("w")
    assertEquals("one two three four", session.content)
    assertEquals(4, session.caret)
  }

  @Test
  fun `test a key that is no label cancels`() {
    val session = Session("one two three four")
    session.type("\\\\wz")
    assertEquals(emptyMap(), session.labels())
    assertEquals(0, session.caret)
    assertTrue(session.statuses.any { "Cancelled" in it }, "got ${session.statuses}")
  }

  // ---- configuration ------------------------------------------------------------------------------

  @Test
  fun `test labels follow the keyboard layout`() {
    // A label is a command key, so `'keyboardlayout'` reaches it: `ы` sits where `s` does.
    val session = Session("one two three four", setup = listOf("set keyboardlayout=russian"))
    session.type("\\\\w")
    session.type("ы")
    assertEquals(8, session.caret)
  }

  @Test
  fun `test the character a find motion reads is not translated`() {
    // It is text. On a Russian layout `о` is where `j` is, and a find for `о` must not find `j`.
    val session = Session("x j о j о", setup = listOf("set keyboardlayout=russian"))
    session.type("\\\\fо")
    assertEquals(mapOf("a" to 4, "s" to 8), session.labels())
  }

  @Test
  fun `test g EasyMotion_keys chooses the labels`() {
    val session = Session("one two three four", setup = listOf("let g:EasyMotion_keys = 'jk'"))
    session.type("\\\\w")
    assertEquals(mapOf("j" to 4, "kj" to 8, "kk" to 14), session.labels())
  }

  @Test
  fun `test g EasyMotion_do_mapping 0 leaves the keys alone and the Plug names working`() {
    val session = Session("one two three four", setup = listOf("let g:EasyMotion_do_mapping = 0"))
    session.type("\\\\w")
    assertEquals(emptyMap(), session.labels())
    assertEquals(4, session.caret, "with no mapping, `\\\\w` is only `w`")

    session.script("nmap gz <Plug>(easymotion-w)")
    session.type("gz")
    assertEquals(mapOf("a" to 8, "s" to 14), session.labels())
  }

  @Test
  fun `test the prefix can be moved, the way vim-easymotion documents`() {
    val session = Session("one two three four", setup = listOf("map <Leader> <Plug>(easymotion-prefix)"))
    session.type("\\w")
    assertEquals(mapOf("a" to 4, "s" to 8, "d" to 14), session.labels())
  }
}

private fun positionOf(text: String, offset: Int): Position {
  val before = text.substring(0, offset)
  return Position(before.count { it == '\n' }, offset - (before.lastIndexOf('\n') + 1))
}
