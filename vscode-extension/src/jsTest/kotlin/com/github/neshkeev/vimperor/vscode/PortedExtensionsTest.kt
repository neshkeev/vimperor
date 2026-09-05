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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The extensions ported after the first, tested for what they do rather than that they loaded.
 *
 * Each one moved from `src/main/java`, where it was a `VimExtension` on an IntelliJ extension
 * point, to `vim-engine/src/commonMain`, where it is a `@VimPlugin` function that both hosts
 * compile. The plugin keeps a two-line adapter so `set <name>` still works in IntelliJ; when the
 * plugin goes, so does the adapter, and the extension stays.
 *
 * `ReplaceWithRegisterTest` covers the first one separately because it is also the file that proves
 * the loader itself.
 */
class PortedExtensionsTest {

  private class Session(text: String, extension: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      injector.extensionLoader.enableExtension(
        ExtensionBean(extension, VsCodeExtensions.PLUGIN_ID, "init", ""),
      )
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    val content: String get() = fake.document.content
    val caret: Int get() = host.editorFor(fake).primaryCaret().offset
  }

  // ---- vim-paragraph-motion --------------------------------------------------------------------

  /**
   * The whole reason the extension exists: Vim's `}` stops only at a line with *nothing* on it, so
   * a line holding two spaces is not a boundary and the motion sails past it.
   */
  @Test
  fun `test the paragraph motion stops at a line of only whitespace`() {
    val session = Session("one\n  \ntwo\n\nthree\n", "vim-paragraph-motion")

    session.type("}")

    assertEquals(4, session.caret, "on the whitespace-only line, which plain `}` would skip")
  }

  @Test
  fun `test it goes backwards too`() {
    val session = Session("one\n  \ntwo\n", "vim-paragraph-motion")
    session.type("}")
    session.type("}")

    session.type("{")

    assertEquals(4, session.caret)
  }

  /** A count reaches an extension the same way it reaches a built-in, through `v:count1`. */
  @Test
  fun `test a count moves that many paragraphs`() {
    val session = Session("one\n  \ntwo\n  \nthree\n", "vim-paragraph-motion")

    session.type("2}")

    // "one\n" is 0..3, "  \n" is 4..6, "two\n" is 7..10, so the second whitespace-only line starts
    // at 11. IdeaVim's own ParagraphMotionTest fixes the landing point as the *start* of that line.
    assertEquals(11, session.caret, "the start of the second whitespace-only line, not the first")
  }

  /**
   * `d}` deletes `one` and leaves its newline, which looks wrong and is Vim's own rule: an
   * exclusive motion whose end lands in column 1 has that end pulled back to the end of the
   * previous line, and becomes inclusive (`:h exclusive`). So the motion stops after `one` rather
   * than after `one\n`. Asserted because it is the kind of thing a later change would "fix".
   */
  @Test
  fun `test it composes with an operator`() {
    val session = Session("one\n  \ntwo\n", "vim-paragraph-motion")

    session.type("d}")

    assertEquals("\n  \ntwo\n", session.content)
  }

  // ---- textobj-entire --------------------------------------------------------------------------

  @Test
  fun `test ae is the whole buffer`() {
    val session = Session("  one\ntwo  \n", "textobj-entire")

    session.type("dae")

    assertEquals("", session.content)
  }

  /**
   * `ie` is the same minus the surrounding whitespace, which is the half that is not `ggVG`.
   */
  @Test
  fun `test ie leaves the surrounding whitespace behind`() {
    val session = Session("\n\n  one two  \n\n", "textobj-entire")

    session.type("die")

    assertEquals("\n\n    \n\n", session.content, "the leading and trailing blanks stay")
  }

  @Test
  fun `test it composes with an operator other than delete`() {
    val session = Session("one two\n", "textobj-entire")

    session.type("gUie")

    assertEquals("ONE TWO\n", session.content)
  }

  // ---- mini-ai ---------------------------------------------------------------------------------

  /**
   * The difference from Vim's own `ci(`, and the reason people install this: Vim needs the caret
   * already inside the parentheses, and this searches forward for them.
   */
  @Test
  fun `test ci paren works from outside the parentheses`() {
    val session = Session("call(one)\n", "mini-ai")

    session.type("di(")

    assertEquals("call()\n", session.content)
  }

  @Test
  fun `test the around form takes the delimiters with it`() {
    val session = Session("call(one)\n", "mini-ai")

    session.type("da(")

    assertEquals("call\n", session.content)
  }

  @Test
  fun `test it finds quotes the same way`() {
    val session = Session("say \"hello\" now\n", "mini-ai")

    session.type("di\"")

    assertEquals("say \"\" now\n", session.content)
  }

  @Test
  fun `test yanking the whole buffer puts it in the register`() {
    val session = Session("one\ntwo\n", "textobj-entire")

    session.type("yae")
    session.type("Gp")

    assertEquals("one\ntwo\none\ntwo\n", session.content)
  }
}
