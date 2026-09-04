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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reconciliation between the engine's synchronous buffer and VS Code's asynchronous document.
 *
 * The engine's write surface is three methods and all of them are expected to have taken effect by
 * the time they return, which VS Code's document cannot promise. Everything about whether this port
 * can work at all sits here, so the tests are about the *shape* of the edit as much as the text:
 * ending up with the right characters is not enough if getting there rewrote the file.
 */
class DocumentBufferTest {

  private fun flushed(buffer: DocumentBuffer): Boolean {
    var result: Boolean? = null
    buffer.flush { result = it }
    return result ?: throw AssertionError("flush never reported a result")
  }

  @Test
  fun `test the engine sees its own edit immediately`() {
    val editor = FakeEditor("foo bar")
    val buffer = DocumentBuffer(editor)
    buffer.replace(0, 3, "baz")

    // The whole point: the engine reads this back on its next line, before VS Code has been told.
    assertEquals("baz bar", buffer.text)
    assertEquals("foo bar", editor.document.content, "the document should not have changed yet")
  }

  @Test
  fun `test flushing writes the change to the document`() {
    val editor = FakeEditor("foo bar")
    val buffer = DocumentBuffer(editor)
    buffer.replace(0, 3, "baz")

    assertTrue(flushed(buffer))
    assertEquals("baz bar", editor.document.content)
  }

  @Test
  fun `test deleting one character replaces one character`() {
    // `x` on a long line. A whole-document rewrite would produce the same text and cost the undo
    // stack, decorations and folding, so the range is the assertion.
    val editor = FakeEditor("the quick brown fox\njumps over the lazy dog\n")
    val buffer = DocumentBuffer(editor)
    buffer.replace(4, 5, "")

    assertTrue(flushed(buffer))
    assertEquals("the uick brown fox\njumps over the lazy dog\n", editor.document.content)
    assertEquals(listOf(RecordedEdit(4, 5, "")), editor.recordedEdits)
  }

  @Test
  fun `test inserting text touches only the insertion point`() {
    val editor = FakeEditor("hello world")
    val buffer = DocumentBuffer(editor)
    buffer.insert(5, ",")

    assertTrue(flushed(buffer))
    assertEquals("hello, world", editor.document.content)
    assertEquals(listOf(RecordedEdit(5, 5, ",")), editor.recordedEdits)
  }

  @Test
  fun `test a run of edits flushes as one`() {
    // Vim commands mutate repeatedly - `dw` deletes, `:s` replaces, macros do both many times over.
    // VS Code resolves every range in an edit callback against the pre-edit document, so the
    // operations cannot be replayed as they happened; one edit over the difference can.
    val editor = FakeEditor("one two three")
    val buffer = DocumentBuffer(editor)
    buffer.replace(0, 3, "ONE")
    buffer.replace(4, 7, "TWO")
    buffer.replace(8, 13, "THREE")

    assertTrue(flushed(buffer))
    assertEquals("ONE TWO THREE", editor.document.content)
    assertEquals(1, editor.recordedEdits.size, "three mutations should reach VS Code as one edit")
  }

  @Test
  fun `test a motion writes nothing`() {
    // Reading and re-flushing must not mark the file dirty, or every `j` is an unsaved change.
    val editor = FakeEditor("foo")
    val buffer = DocumentBuffer(editor)

    assertTrue(flushed(buffer))
    assertEquals(0, editor.recordedEdits.size)
    assertEquals(1, editor.document.version, "an empty edit should not bump the document version")
  }

  @Test
  fun `test edits on distant lines are covered by one range`() {
    // The known limit of doing this in one edit: two carets deleting far apart produce a range
    // spanning everything between them. Recorded so a change in this behaviour is deliberate.
    val editor = FakeEditor("aaa\nbbb\nccc\nddd\n")
    val buffer = DocumentBuffer(editor)
    buffer.replace(12, 13, "")
    buffer.replace(0, 1, "")

    assertTrue(flushed(buffer))
    assertEquals("aa\nbbb\nccc\ndd\n", editor.document.content)
    // One range from inside the first line to inside the last, carrying the untouched lines
    // between them along for the ride.
    assertEquals(listOf(RecordedEdit(2, 13, "\nbbb\nccc\n")), editor.recordedEdits)
  }

  @Test
  fun `test a document that moved underneath is not overwritten`() {
    // Between a mutation and its flush there are two versions of the buffer. Anything else editing
    // the document in that window - the user, a language server, another extension - is editing the
    // one the engine is not looking at, and writing anyway would silently revert it.
    val editor = FakeEditor("foo bar")
    val buffer = DocumentBuffer(editor)
    buffer.replace(0, 3, "baz")

    editor.document.content = "something else entirely"

    assertFalse(flushed(buffer), "flushing over a moved document should report failure")
    assertEquals("something else entirely", editor.document.content)
    assertEquals("something else entirely", buffer.text, "the buffer should take the document's version")
  }

  @Test
  fun `test a refused edit leaves the buffer agreeing with the document`() {
    val editor = FakeEditor("foo bar")
    val buffer = DocumentBuffer(editor)
    editor.refuseEdits = true
    buffer.replace(0, 3, "baz")

    assertFalse(flushed(buffer))
    assertEquals("foo bar", editor.document.content)
    assertEquals("foo bar", buffer.text, "a refused edit must not leave the engine believing it landed")
  }

  @Test
  fun `test reseeding takes whatever the document says`() {
    val editor = FakeEditor("foo")
    val buffer = DocumentBuffer(editor)
    buffer.replace(0, 3, "bar")
    editor.document.content = "typed by the user"

    buffer.reseed()

    assertEquals("typed by the user", buffer.text)
    assertTrue(flushed(buffer))
    assertEquals(0, editor.recordedEdits.size, "reseeding should discard the unflushed change")
  }
}
