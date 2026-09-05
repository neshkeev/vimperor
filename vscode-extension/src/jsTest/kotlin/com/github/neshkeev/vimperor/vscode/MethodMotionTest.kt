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
 * `[m` `]m` `[M` `]M` and `[s` `]s`, five keys that used to throw.
 *
 * All five were `TODO(...)` in the host's search helper, so pressing one wrote a
 * `NotImplementedError` to the output channel. Two of the three notes explaining why said "needs a
 * language server", which was true when they were written and stopped being true when
 * [DocumentSymbols] arrived: IdeaVim reads IntelliJ's Structure View, and the Outline tree VS Code
 * caches is the same tree.
 *
 * The semantics are `PsiHelper.findMethodOrClass`'s, kept deliberately: every symbol contributes
 * one offset, sorted and deduplicated, and the caret's place in that list plus the count picks the
 * answer with clamping at both ends. That means `]m` walks *structural elements* rather than
 * methods - fields and properties included - which is what IdeaVim does and is looser than the name
 * `[m` carries in Vim.
 */
class MethodMotionTest {

  private class FixedSymbols(private val symbols: List<SymbolRange>) : DocumentSymbols {
    override fun of(document: TextDocument): List<SymbolRange> = symbols
    override fun refresh(document: TextDocument) {}
    override fun forget(document: TextDocument) {}
  }

  private class Session(text: String = SOURCE, symbols: List<SymbolRange> = symbolsFor(text)) {
    val fake = FakeEditor(text)
    val host = VimHost(symbols = FixedSymbols(symbols)).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    val caret: Int get() = host.editorFor(fake).primaryCaret().offset
  }

  /**
   * The caret starts on the class's own start offset, so `]m` moves to the *next* element rather
   * than standing still - which is `PsiHelper`'s rule for an exact hit and Vim's for a motion.
   */
  @Test
  fun `test bracket m goes to the start of the next element`() {
    val session = Session()

    session.type("]m")

    assertEquals(SOURCE.indexOf("/** Greets. */"), session.caret)
  }

  @Test
  fun `test bracket m walks on with a count`() {
    val session = Session()

    session.type("2]m")

    assertEquals(SOURCE.indexOf("/** Greets. */"), session.caret, "the class, then the method")
  }

  @Test
  fun `test bracket m backwards from inside`() {
    val session = Session()

    session.type("4G[m")

    assertEquals(SOURCE.indexOf("/** Greets. */"), session.caret)
  }

  /** `]M` is the element's last character rather than one past it, which is IdeaVim's choice. */
  @Test
  fun `test bracket M goes to the end of an element`() {
    val session = Session()

    session.type("]M")

    assertEquals(SOURCE.indexOf("    }\n") + 4, session.caret, "the method's closing brace")
  }

  @Test
  fun `test it clamps rather than wrapping`() {
    val session = Session()

    session.type("99]m")

    assertEquals(SOURCE.indexOf("/** Greets. */"), session.caret, "the last element, not an error")
  }

  /**
   * The case that matters most, because it is the common one: a language with no symbol provider,
   * or a cache an edit has just invalidated. `-1` is the engine's "no such motion", so the key
   * beeps and the caret stays - which is what it does in Vim in a file with no methods, and is a
   * great deal better than the `NotImplementedError` it used to write.
   */
  @Test
  fun `test nothing known leaves the caret alone`() {
    val session = Session(symbols = emptyList())

    session.type("]m")

    assertEquals(0, session.caret)
  }

  @Test
  fun `test the spelling motions decline rather than throwing`() {
    val session = Session()

    session.type("]s")
    assertEquals(0, session.caret)

    session.type("[s")
    assertEquals(0, session.caret)
  }
}

private const val SOURCE =
  "class Greeter {\n" +
    "    /** Greets. */\n" +
    "    fun greet() {\n" +
    "        say()\n" +
    "    }\n" +
    "}\n"

private fun symbolsFor(text: String): List<SymbolRange> = listOf(
  SymbolRange(
    kind = SymbolKind.CLASS,
    start = 0,
    end = text.indexOf("}\n", text.indexOf("    }\n")) + 1,
    nameStart = text.indexOf("Greeter"),
  ),
  SymbolRange(
    kind = SymbolKind.METHOD,
    start = text.indexOf("/** Greets"),
    end = text.indexOf("    }\n") + 5,
    nameStart = text.indexOf("greet()"),
  ),
)
