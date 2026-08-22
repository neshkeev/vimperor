/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.helper

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The message bundle must render identically on every target, so these run on all of them.
 *
 * The JVM reads a `.properties` resource through `ResourceBundle` and formats with
 * `MessageFormat`; JS uses a generated map and a hand-written subset of the same format. The rows
 * below are chosen for the places those two could disagree, not for coverage.
 */
class EngineMessagesTest {

  @Test
  fun `test a message with no parameters is returned verbatim`() {
    assertEquals("E10: \\ should be followed by /, ? or &", EngineMessageHelper.message("E10"))
  }

  @Test
  fun `test a message with no parameters is never MessageFormat-decoded`() {
    // Both hosts print `can''t`, with the doubled quote intact, because the no-parameter path
    // returns the pattern verbatim and never runs MessageFormat over it. E146 is written with the
    // MessageFormat escape but has no arguments, so nothing ever unescapes it.
    //
    // That is a real defect in the bundle rather than in either host - the user sees `can''t` - and
    // it is asserted here as the current behaviour so that fixing the bundle is a deliberate,
    // visible change rather than something that silently differs between targets.
    assertEquals(
      "E146: Regular expressions can''t be delimited by letters",
      EngineMessageHelper.message("E146"),
    )
  }

  @Test
  fun `test a parameter is substituted`() {
    assertEquals("E486: Pattern not found: foo", EngineMessageHelper.message("E486", "foo"))
  }

  @Test
  fun `test doubled quotes around a word survive substitution`() {
    assertEquals(
      "E357: 'langmap': Matching character missing for a: b",
      EngineMessageHelper.message("E357", "a", "b"),
    )
  }

  @Test
  fun `test a quoted brace is literal text, not a substitution`() {
    // Pre-existing behaviour, reproduced rather than fixed: E354's pattern is
    // `Invalid register name: '{0}'`, and a single quote opens a quoted section in MessageFormat,
    // so the register name never reaches the user. Both hosts must be wrong the same way until
    // someone decides to change the bundle.
    assertEquals("E354: Invalid register name: {0}", EngineMessageHelper.message("E354", "x"))
  }

  @Test
  fun `test a number-formatted parameter`() {
    assertEquals("E684: List index out of range: 3", EngineMessageHelper.message("E684", 3))
  }
}
