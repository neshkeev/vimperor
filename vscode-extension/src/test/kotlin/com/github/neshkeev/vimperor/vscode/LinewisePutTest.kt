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

/**
 * A put over a line-wise Visual selection that reaches the last line of the file.
 *
 * A put over a selection deletes the selection first, and a line-wise delete takes a newline with
 * it. *Which* newline is the whole of this: the one behind the block when there is one, and the one
 * in front of it when the block runs to the end of a file with no trailing newline. In the second
 * case the caret is left at an offset past the end of the shortened document - there is no line left
 * for it to start - and Vim's answer is to append a line.
 *
 * The engine asked for that case with `caret.offset == fileSize`, an equality an overshot offset
 * cannot satisfy, so the branch written for it was unreachable and the put fell through to an
 * out-of-range offset. Nothing rejects one: the insert clamps it, so the text landed on the end of
 * the previous line - a put that went somewhere plausible and wrong.
 *
 * **The bug is the engine's, and these are plain Vim keys, but the tests are here.** It was found
 * through `grr`, and `ReplaceWithRegisterTest` keeps the one case that reaches it from there - a
 * count, because a single-line range is character-wise. The engine's own headless host cannot press
 * `V` at all: `HeadlessInjector` has no `visualMotionGroup`, so every line-wise Visual shape below
 * is unreachable from `:vim-engine:jvmTest` until one is written. That is worth knowing before
 * assuming the engine's suite covers a Visual-mode regression.
 */
class LinewisePutTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    val content: String get() = fake.document.content
  }

  private val noEol = "one two three\nfour five six\nseven eight"
  private val eol = "one two three\nfour five six\nseven eight\n"

  // ---- the bug ---------------------------------------------------------------------------------

  @Test
  fun `test a line-wise put over the last two lines of a file with no trailing newline`() {
    val session = Session(noEol)

    session.type("yiw")
    session.type("jVjp")

    assertEquals("one two three\none", session.content)
  }

  /**
   * One line, so no count and no multi-line range: the file's last line is the whole of what makes
   * this different. The commit that found this through `grr` reported it as needing a range of more
   * than one line, which was an artefact of `grr` - a single-line range never reaches the line-wise
   * path there.
   */
  @Test
  fun `test a line-wise put over the last line of a file with no trailing newline`() {
    val session = Session(noEol)

    session.type("yiw")
    session.type("jjVp")

    assertEquals("one two three\nfour five six\none", session.content)
  }

  @Test
  fun `test a line-wise put over every line but the first`() {
    val session = Session(noEol)

    session.type("yiw")
    session.type("jVGp")

    assertEquals("one two three\none", session.content)
  }

  /**
   * A line-wise register, where the separator the appended line needs is the one the register
   * already carries rather than one the put adds. Both shapes have to end up with exactly one.
   */
  @Test
  fun `test a line-wise register appended past the last line`() {
    val session = Session(noEol)

    session.type("yy")
    session.type("jVjp")

    assertEquals("one two three\none two three", session.content)
  }

  // ---- what had to keep working ----------------------------------------------------------------

  /**
   * The same selection shape one line earlier, and the half that always worked. It is here because
   * it is what a fix to the append could break: there is a newline behind the block for the delete
   * to take, so the caret lands on a real line and the put opens one rather than appending.
   */
  @Test
  fun `test a line-wise put that stops short of the last line`() {
    val session = Session(noEol)

    session.type("yiw")
    session.type("Vjp")

    assertEquals("one\nseven eight", session.content)
  }

  @Test
  fun `test a line-wise put over a middle line`() {
    val session = Session(noEol)

    session.type("yiw")
    session.type("jVp")

    assertEquals("one two three\none\nseven eight", session.content)
  }

  /**
   * With a trailing newline there is always a line behind the block, so none of this arises. The
   * trailing newline surviving is the property that says the append is not being taken when it
   * should not be.
   */
  @Test
  fun `test a line-wise put over the last lines of a file that ends with a newline`() {
    val session = Session(eol)

    session.type("yiw")
    session.type("jVjp")

    assertEquals("one two three\none\n", session.content)
  }

  /**
   * The whole file, which is the one shape with no line in front of the block either. The delete
   * empties the document, so there is nothing to append to and the put opens a line as usual.
   */
  @Test
  fun `test a line-wise put over the whole file`() {
    val session = Session(noEol)

    session.type("yiw")
    session.type("VGp")

    assertEquals("one\n", session.content)
  }
}
