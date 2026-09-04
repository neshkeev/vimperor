/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The command ids, and the one thing about them that can be tested here.
 *
 * Not whether they are right - that is the whole problem. VS Code publishes no list of command ids,
 * so nothing offline can say whether `workbench.action.focusBelowGroup` exists, and a test asserting
 * that string only proves the test and the code were written by the same hand. The answer is at
 * activation, against `getCommands`, and the stub host checks the reporting end to end.
 *
 * What is ordinary code, and is tested like ordinary code, is the comparison itself: that a command
 * VS Code does not have is reported and one it does have is not.
 */
class VsCodeCommandIdTest {

  @Test
  fun `test a VS Code with every command is reported as missing nothing`() {
    assertEquals(emptyList(), VsCodeCommands.missingFrom(VsCodeCommands.all))
  }

  @Test
  fun `test a command this VS Code does not have is reported`() {
    val available = VsCodeCommands.all - VsCodeCommands.SAVE
    assertEquals(listOf(VsCodeCommands.SAVE), VsCodeCommands.missingFrom(available))
  }

  @Test
  fun `test a VS Code with nothing is reported as missing everything`() {
    assertEquals(VsCodeCommands.all, VsCodeCommands.missingFrom(emptyList()))
  }

  /**
   * The list is what the activation check checks, so a duplicate would report one id twice and a
   * short list would report nothing at all about the ids it left out.
   */
  @Test
  fun `test the registry lists each command once`() {
    assertEquals(VsCodeCommands.all.size, VsCodeCommands.all.distinct().size)
    assertTrue(VsCodeCommands.all.size > 40, "the command list looks too short")
  }

  /** The families, which are built rather than declared and so are not covered by the build check. */
  @Test
  fun `test the numbered commands are in the list`() {
    assertContains(VsCodeCommands.all, VsCodeCommands.openEditorAtIndex(0))
    assertContains(VsCodeCommands.all, VsCodeCommands.openEditorAtIndex(8))
    assertContains(VsCodeCommands.all, VsCodeCommands.focusEditorGroup(1)!!)
    assertContains(VsCodeCommands.all, VsCodeCommands.focusEditorGroup(8)!!)
    assertEquals(null, VsCodeCommands.focusEditorGroup(9), "VS Code numbers eight groups and no more")
  }
}
