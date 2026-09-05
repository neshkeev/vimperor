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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `am`, `aM`, `im` and `ac` on this host, which is `functextobj` and `classtextobj` working.
 *
 * Both extensions ask `injector.psiService` where a function or a class is. IntelliJ answers from
 * its syntax tree; here the answer comes from [DocumentSymbols], the cache of what VS Code's own
 * Outline view knows. [DocumentSymbolsTest] covers the cache - freshness, and reading VS Code's two
 * answer shapes. This covers what is built on top of it: turning a symbol into the four ranges Vim
 * asks for, and the two of those that a `DocumentSymbol` does not carry.
 *
 * The symbols here are handed in rather than asked for, because a language server is the one part
 * of this that is not ours. What is ours is what is done with the answer.
 */
class TextObjectSymbolTest {

  /** Symbols that are simply known, so a test can be about the ranges rather than about waiting. */
  private class FixedSymbols(private val symbols: List<SymbolRange>) : DocumentSymbols {
    override fun of(document: TextDocument): List<SymbolRange> = symbols
    override fun refresh(document: TextDocument) {}
    override fun forget(document: TextDocument) {}
  }

  private class Session(text: String, symbols: List<SymbolRange>, alias: String) {
    val fake = FakeEditor(text)
    val host = VimHost(symbols = FixedSymbols(symbols)).also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
      injector.extensionRegistrator.setOptionByPluginAlias(alias)
    }

    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    val content: String get() = fake.document.content
  }

  private fun functions(text: String) = Session(text, symbolsFor(text), "kana/vim-textobj-function")

  private fun classes(text: String) = Session(text, symbolsFor(text), "kana/vim-textobj-class")

  // ---- the four ranges -------------------------------------------------------------------------

  @Test
  fun `test am is the function without its doc comment`() {
    val session = functions(BRACES)

    session.type("4Gdam")

    assertEquals("class Greeter {\n    /** Greets. */\n}\ntail\n", session.content)
  }

  @Test
  fun `test aM takes the doc comment with it`() {
    val session = functions(BRACES)

    session.type("4GdaM")

    assertEquals("class Greeter {\n}\ntail\n", session.content)
  }

  @Test
  fun `test im is what the braces enclose`() {
    val session = functions(BRACES)

    session.type("4Gdim")

    assertEquals("class Greeter {\n    /** Greets. */\n    fun greet() {}\n}\ntail\n", session.content)
  }

  @Test
  fun `test ac is the class`() {
    val session = classes(BRACES)

    session.type("4Gdac")

    assertEquals("tail\n", session.content)
  }

  /**
   * The indentation rule, which is the second of the two ways a body is found and the one that has
   * to be right for Python. A `def` has no closing brace to match, and its body is exactly the run
   * of lines indented more deeply than the `def` - which is the language's own rule, not an
   * approximation of it.
   */
  @Test
  fun `test im finds an indented body when there are no braces`() {
    val session = functions(INDENTED)

    session.type("2Gdim")

    // The body's two lines go and their trailing newline stays, which is what a charwise inner
    // object does. `im` is charwise here deliberately - see `MethodRangeActionHandler.visualType`.
    assertEquals("def greet():\n\nother()\n", session.content)
  }

  @Test
  fun `test a text object outside any function does nothing`() {
    val session = functions(BRACES)

    session.type("7Gdam")

    assertEquals(BRACES, session.content)
  }

  /**
   * A caret in a nested function has to find the nested one. The symbol list is flat and a method
   * sits inside its class's range, so "the enclosing symbol" has to mean the smallest one - see
   * `VsCodePsiService.enclosing`.
   */
  @Test
  fun `test the innermost symbol wins`() {
    val session = functions(BRACES)

    // Line 4 is inside both the class and the method; only the method is a function, but the same
    // rule is what keeps `ac` off an outer class.
    session.type("4Gdam")

    assertEquals("class Greeter {\n    /** Greets. */\n}\ntail\n", session.content)
  }

  @Test
  fun `test nothing known about the file leaves the keys inert rather than wrong`() {
    val session = Session(BRACES, emptyList(), "kana/vim-textobj-function")

    session.type("4Gdam")

    assertEquals(BRACES, session.content)
  }
}

private const val BRACES =
  "class Greeter {\n" +
    "    /** Greets. */\n" +
    "    fun greet() {\n" +
    "        say()\n" +
    "    }\n" +
    "}\n" +
    "tail\n"

private const val INDENTED =
  "def greet():\n" +
    "    say()\n" +
    "    wave()\n" +
    "\n" +
    "other()\n"

/**
 * The symbols a language server would report for these two files, by offset.
 *
 * Written against the text rather than as line and column pairs so that the expectations stay
 * legible: `range` is what VS Code documents as everything belonging to the symbol including its
 * leading comments, and `selectionRange.start` is the identifier.
 */
private fun symbolsFor(text: String): List<SymbolRange> = when (text) {
  BRACES -> listOf(
    SymbolRange(
      kind = SymbolKind.CLASS,
      start = 0,
      end = text.indexOf("}\ntail") + 1,
      nameStart = text.indexOf("Greeter"),
    ),
    SymbolRange(
      kind = SymbolKind.METHOD,
      start = text.indexOf("/** Greets"),
      end = text.indexOf("    }\n") + 5,
      nameStart = text.indexOf("greet()"),
    ),
  )

  INDENTED -> listOf(
    SymbolRange(
      kind = SymbolKind.FUNCTION,
      start = 0,
      end = text.indexOf("    wave()") + "    wave()".length,
      nameStart = text.indexOf("greet"),
    ),
  )

  else -> emptyList()
}
