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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dead keys and input methods, and the four commands they arrive over.
 *
 * Ported from IdeaVim's `ComposedTextDispatcherTest` (VIM-1503), case for case where the cases
 * carry over. What is tested here is the policy in [ComposedText] and the host's half of it; the
 * wiring - which of VS Code's commands reaches which method - is in `Extension.kt`, which no unit
 * test loads, so `activate-in-a-stub-host.js` presses the real commands.
 *
 * The composition is driven the way VS Code drives it, which is the part worth getting right in a
 * test: `compositionStart`, then an update per keystroke whose `replacePrevCharCnt` is **the length
 * of the previous update's own text**, then `compositionEnd` *after* the committed text has already
 * been reported. See [ComposedText] for where each of those comes from in the installed editor.
 */
class ComposedTextTest {

  private fun host() = VimHost().also { it.start() }

  private fun VimHost.keys(editor: FakeEditor, keys: String) = handle(editor, injector.parser.parseKeys(keys))

  private fun session(text: String): Triple<VimHost, FakeEditor, VsCodeEditor> {
    val fake = FakeEditor(text, path = "/test/a.txt")
    val host = host()
    val editor = host.editorFor(fake)
    KeyHandler.getInstance().fullReset(editor)
    return Triple(host, fake, editor)
  }

  /**
   * One composition, as VS Code reports it - and the committed text handed back for the caller to
   * type, because that is what `Extension.kt` does with a [ComposedTextDecision.Commit].
   *
   * [previews] is what the user saw at each step. A dead key `'` then `e` is `["'", "é"]`: the
   * second update replaces one character - the first preview's length - with `é`.
   */
  private fun VimHost.compose(previews: List<String>): ComposedTextDecision {
    compositionStarted()
    var lastLength = 0
    for (preview in previews) {
      // The first update carries a count of zero, which is why VS Code routes it to `type` rather
      // than to `replacePreviousChar` - and why the host has to ask about a `type` at all.
      if (lastLength == 0) typedDuringComposition(preview) else compositionUpdated(preview, lastLength)
      lastLength = preview.length
    }
    return compositionEnded()
  }

  /** Composes and then does with the result what the extension would. */
  private fun VimHost.composeAndCommit(fake: FakeEditor, previews: List<String>) {
    val decision = compose(previews)
    if (decision is ComposedTextDecision.Commit) type(fake, decision.text)
  }

  @Test
  fun `test a dead key does not replace the Visual selection`() {
    val (host, fake, _) = session("'sample text'")
    host.keys(fake, "ll")
    // `vi` is waiting for a text object, and in Vim the dead key's composed `'` must not reach the
    // document - it is half of `vi'`. Inserting it there is what IdeaVim's VIM-1503 reported.
    host.keys(fake, "vi")

    host.composeAndCommit(fake, listOf("'", "'"))

    assertEquals("'sample text'", fake.document.getText(), "the composed text reached the document")
    assertEquals("VISUAL", host.modeName(), "the committed quote did not finish `vi'`")
    val caret = host.editorFor(fake).primaryCaret()
    assertEquals(1, caret.selectionStart, "`vi'` selects inside the quotes")
    assertEquals(12, caret.selectionEnd)
  }

  @Test
  fun `test a dead key used as a Visual command`() {
    val (host, fake, _) = session("'sample text'")
    host.keys(fake, "llllv")

    // `~` as a dead key: composed first, committed second, and it is a Visual command rather than
    // text - so it has to arrive as a key and toggle the case of the selection.
    host.composeAndCommit(fake, listOf("~", "~"))

    assertEquals("'samPle text'", fake.document.getText())
    assertEquals("NORMAL", host.modeName(), "`~` leaves Visual mode")
  }

  @Test
  fun `test a composition is kept out of the document while it is being composed`() {
    val (host, fake, _) = session("hello")

    host.compositionStarted()
    assertIs<ComposedTextDecision.KeepOut>(host.typedDuringComposition("'"))
    assertIs<ComposedTextDecision.KeepOut>(host.compositionUpdated("'~", 1))

    assertEquals("hello", fake.document.getText(), "a preview was drawn into the document")
    assertEquals("NORMAL", host.modeName())
  }

  /**
   * The reported bug, in the shape the trace of it had.
   *
   * A composition in flight while the user works in Visual mode, and then `<Esc>`. VS Code's
   * `_compositionType` opens with `if (!e.isEmpty()) return null`, so nothing could land while the
   * selection was up; collapsing it is what let the commit through. And the count it came with was
   * the composition's own length - twenty-seven in the report - applied as `caret.column - count`,
   * so it took twenty-seven characters of text the composition had never put there.
   */
  @Test
  fun `test a composition that commits after Esc does not eat the line`() {
    val (host, fake, _) = session("the quick brown fox jumps over it")
    host.compositionStarted()
    host.typedDuringComposition("a")
    // The composition grows while Vim is being driven: every one of these keys is a command, so
    // none of this reaches the document, and VS Code goes on counting its own preview anyway.
    host.compositionUpdated("abcdefghijklmnopqrstuvwxyz0", 1)
    host.keys(fake, "wve")
    host.keys(fake, "<Esc>")

    val decision = host.compositionUpdated("", 27)
    assertIs<ComposedTextDecision.KeepOut>(decision, "a 27-character replace was let through")
    host.compositionEnded()

    assertEquals(
      "the quick brown fox jumps over it",
      fake.document.getText(),
      "the composition deleted text it never typed",
    )
  }

  @Test
  fun `test a composition in Insert mode is handed to VS Code`() {
    val (host, fake, _) = session("hello")
    host.keys(fake, "i")

    // Where composed text is meant to go into the document, and where the user has to see it while
    // they compose. Upstream's rule, and the reason this is not simply refused everywhere.
    assertIs<ComposedTextDecision.HandToVsCode>(host.compositionStarted())
    assertIs<ComposedTextDecision.HandToVsCode>(host.typedDuringComposition("'"))
    assertIs<ComposedTextDecision.HandToVsCode>(host.compositionUpdated("é", 1))
    assertIs<ComposedTextDecision.HandToVsCode>(host.compositionEnded())
  }

  /**
   * One decision per composition, held to the end - which is this host's answer to upstream's
   * `if (editor.composedTextRange != null) return false`.
   *
   * A composition that started in Insert mode has a preview on screen. If `<Esc>` in the middle of
   * it switched the rule, the rest of the composition would be swallowed and that preview would be
   * stranded in the document with nothing able to take it down.
   */
  @Test
  fun `test a composition that started in Insert mode stays VS Code's after Esc`() {
    val (host, fake, _) = session("hello")
    host.keys(fake, "i")
    assertIs<ComposedTextDecision.HandToVsCode>(host.compositionStarted())

    host.keys(fake, "<Esc>")
    assertEquals("NORMAL", host.modeName())

    assertIs<ComposedTextDecision.HandToVsCode>(host.compositionUpdated("é", 1))
    assertIs<ComposedTextDecision.HandToVsCode>(host.compositionEnded())
  }

  /** And the other way round, which is the same rule and the one that keeps the buffer safe. */
  @Test
  fun `test a composition that started in Normal mode stays out after i`() {
    val (host, fake, _) = session("hello")
    assertIs<ComposedTextDecision.KeepOut>(host.compositionStarted())

    host.keys(fake, "i")
    assertEquals("INSERT", host.modeName())

    assertIs<ComposedTextDecision.KeepOut>(host.compositionUpdated("é", 0))
    assertIs<ComposedTextDecision.Commit>(host.compositionEnded())
  }

  @Test
  fun `test a type outside a composition is an ordinary key`() {
    val (host, _, _) = session("hello")
    assertNull(host.typedDuringComposition("x"), "a key was mistaken for a composition")
  }

  /**
   * The command line keeps composed text out too, which is the one divergence from upstream.
   *
   * IdeaVim's ex field is a Swing text field and draws the composition itself; this host's prompt is
   * a status bar item and cannot, so the committed text is typed into it when the composition ends.
   */
  @Test
  fun `test a composition at the command line is kept out of the document`() {
    val (host, fake, _) = session("hello")
    host.keys(fake, ":")
    assertEquals("COMMAND", host.modeName())

    assertIs<ComposedTextDecision.KeepOut>(host.compositionStarted())
    val decision = host.compositionEnded()
    assertIs<ComposedTextDecision.KeepOut>(decision, "nothing was composed, so there is nothing to type")
    assertEquals("hello", fake.document.getText())
  }

  /**
   * A composition this host joined in the middle, which is the one case the rule cannot be applied
   * from and so is decided by the mode at the time.
   *
   * It is what fixes the reported bug even when the `compositionStart` was missed - this host
   * activating while an IME was already open - and the residual is named in [ComposedText]: a
   * preview already in the document stays there.
   */
  @Test
  fun `test an update with no composition started is decided by the mode`() {
    val (host, fake, _) = session("hello")

    assertIs<ComposedTextDecision.KeepOut>(host.compositionUpdated("é", 1))
    assertEquals("hello", fake.document.getText())
    assertTrue(host.compositionEnded() is ComposedTextDecision.Commit, "the committed text was dropped")
  }
}
