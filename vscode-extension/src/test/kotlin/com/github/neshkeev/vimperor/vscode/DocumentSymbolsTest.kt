/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The symbol cache: what it holds, when it asks, and when it declines.
 *
 * The whole point of the cache is that a text object is asked its range during a keystroke and
 * VS Code answers over a promise, so the answer has to already be there. That makes freshness the
 * thing worth testing - what is held belongs to a document version, and an edit makes every offset
 * in it wrong.
 *
 * [Settle.NOW] is why these can be written as ordinary synchronous tests. Everything here except
 * the wait is plain logic, and putting a timer in the middle would have hidden it behind an event
 * loop for no gain.
 */
class DocumentSymbolsTest {

  /** A provider that answers whatever the test last set, counting how often it was asked. */
  private class Provider(var answer: dynamic = symbolTree()) {
    var asked: Int = 0
    val ask: (Uri, (dynamic) -> Unit) -> Unit = { _, onAnswer ->
      asked++
      onAnswer(answer)
    }
  }

  private fun cacheOver(provider: Provider) =
    VsCodeDocumentSymbols(ask = provider.ask, settleMillis = 0, settle = Settle.NOW)

  @Test
  fun `test nothing is known until something has asked`() {
    val provider = Provider()
    val cache = VsCodeDocumentSymbols(
      ask = provider.ask,
      settleMillis = 0,
      // Nothing runs the settle, so the request is pending for ever - which is the state a real
      // window is in for the instant between an edit and the language server answering.
      settle = Settle { _, _ -> ({}) },
    )
    val document = FakeDocument("class A {}\n")

    assertNull(cache.of(document))
  }

  @Test
  fun `test asking once fills the cache`() {
    val provider = Provider()
    val cache = cacheOver(provider)
    val document = FakeDocument("class A {}\n")

    assertNull(cache.of(document), "the first ask starts a request and has nothing yet")
    assertEquals(1, provider.asked)

    val symbols = cache.of(document)
    assertTrue(symbols != null && symbols.isNotEmpty())
    assertEquals(1, provider.asked, "a fresh cache is not asked again")
  }

  @Test
  fun `test an edit makes what is held stale`() {
    val provider = Provider()
    val cache = cacheOver(provider)
    val document = FakeDocument("class A {}\n")

    cache.of(document)
    assertTrue(cache.of(document) != null)

    document.version = document.version + 1
    assertNull(cache.of(document), "offsets from before the edit are worse than no answer")
    assertEquals(2, provider.asked)
  }

  @Test
  fun `test an answer for a version that has gone is dropped`() {
    val document = FakeDocument("class A {}\n")
    var deliver: ((dynamic) -> Unit)? = null
    val cache = VsCodeDocumentSymbols(
      ask = { _, onAnswer -> deliver = onAnswer },
      settleMillis = 0,
      settle = Settle.NOW,
    )

    cache.of(document)
    // The user typed while the language server was thinking.
    document.version = document.version + 1
    deliver!!(symbolTree())

    assertNull(cache.of(document))
  }

  @Test
  fun `test a closed document is forgotten`() {
    val provider = Provider()
    val cache = cacheOver(provider)
    val document = FakeDocument("class A {}\n")

    cache.of(document)
    assertTrue(cache.of(document) != null)

    cache.forget(document)
    assertNull(cache.of(document))
  }

  @Test
  fun `test a provider that answers nothing is not asked twice for the same version`() {
    val provider = Provider(answer = null)
    val cache = cacheOver(provider)
    val document = FakeDocument("plain text\n")

    assertEquals(emptyList(), cache.of(document) ?: cache.of(document))
    assertEquals(1, provider.asked, "a language with no symbol provider is asked once, not per key")
  }

  // ---- reading VS Code's two answer shapes ------------------------------------------------------

  @Test
  fun `test a DocumentSymbol tree is flattened parents first`() {
    val provider = Provider()
    val cache = cacheOver(provider)
    val document = FakeDocument(SOURCE)

    cache.of(document)
    val symbols = cache.of(document)!!

    assertEquals(listOf(SymbolKind.CLASS, SymbolKind.METHOD), symbols.map { it.kind })
    assertEquals(0, symbols[0].start)
    assertEquals(SOURCE.indexOf("A"), symbols[0].nameStart)
    // Where `fun` is, not where its line is: VS Code reports the symbol from its first character.
    assertEquals(SOURCE.indexOf("fun greet"), symbols[1].start)
  }

  /**
   * `SymbolInformation` is what an older provider returns: flat, with the range under `location`
   * and no `selectionRange` at all. It has to be read, and what it costs is `am`'s distinction
   * from `aM` - the name offset is the range's own start, so a doc comment cannot be told from a
   * signature. Everything else works.
   */
  @Test
  fun `test a SymbolInformation list is read too`() {
    val provider = Provider(answer = symbolInformation())
    val cache = cacheOver(provider)
    val document = FakeDocument(SOURCE)

    cache.of(document)
    val symbols = cache.of(document)!!

    assertEquals(1, symbols.size)
    assertEquals(SymbolKind.CLASS, symbols[0].kind)
    assertEquals(symbols[0].start, symbols[0].nameStart)
  }
}

private const val SOURCE = "class A {\n  fun greet() {\n    say()\n  }\n}\n"

/** What `vscode.executeDocumentSymbolProvider` returns for [SOURCE] from a modern provider. */
private fun symbolTree(): dynamic = js(
  """[{
    kind: 4,
    range: { start: { line: 0, character: 0 }, end: { line: 4, character: 1 } },
    selectionRange: { start: { line: 0, character: 6 }, end: { line: 0, character: 7 } },
    children: [{
      kind: 5,
      range: { start: { line: 1, character: 2 }, end: { line: 3, character: 3 } },
      selectionRange: { start: { line: 1, character: 6 }, end: { line: 1, character: 11 } },
      children: []
    }]
  }]"""
)

/** The same file, from a provider that predates `DocumentSymbol`. */
private fun symbolInformation(): dynamic = js(
  """[{
    kind: 4,
    name: 'A',
    location: {
      range: { start: { line: 0, character: 0 }, end: { line: 4, character: 1 } }
    }
  }]"""
)
