/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.key.KeySource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A characterwise Visual selection may include the end-of-line character.
 *
 * `charToNativeSelection` turns Vim's inclusive selection into the one the host draws, and it used
 * to refuse to extend past a line's end - `nativeEnd == lineEnd` meant the inclusive adjustment was
 * dropped. The comment above it asked for the restriction to be removed and said what else would
 * have to go with it, and upstream has now done that (seven commits; ported here).
 *
 * **It is a Vim-compatibility fix rather than a tidy-up**, which is what the removed
 * `@VimBehaviorDiffers` blocks in IdeaVim's own tests say: *"Off by one because IdeaVim does not
 * allow selecting a newline char"* and *"The caret at the same offset as the selection end is an
 * indication that there is an off-by-one error"*. Sixteen more of IdeaVim's tests become harvestable
 * fixtures with the annotations gone, and eighteen existing fixtures changed what they expect.
 *
 * The empty line is where the old rule bit hardest, because there the caret already sits at the
 * line's end: the adjustment was dropped, so `v` selected **nothing at all**.
 */
class VsCodeVisualNewlineTest {

  private class Session(text: String, caretOffset: Int = 0) {
    val fake = FakeEditor(text)
    val editor: VsCodeEditor

    init {
      // Assigned before anything is registered, for the reason `VsCodeBlockVisualTest` records:
      // `register` initialises the editor's local options and the option group reaches for the
      // global `injector` while doing it.
      val vimInjector = VsCodeInjector()
      injector = vimInjector
      vimInjector.register(VsCodeEditor(fake))
      engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      VsCodeCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
      injector.functionService.registerHandlers()

      editor = injector.editorGroup.getEditors().first() as VsCodeEditor
      editor.primaryCaret().moveToOffsetNative(caretOffset)
      KeyHandler.getInstance().fullReset(editor)
    }

    fun type(keys: String) {
      val handler = KeyHandler.getInstance()
      val state = handler.keyHandlerState
      for (stroke in injector.parser.parseKeys(keys)) {
        handler.handleKey(editor, stroke, KeySource.TYPED, VsCodeExecutionContext, state)
      }
      editor.flush()
    }

    val selection: Pair<Int, Int>
      get() = editor.primaryCaret().let { it.selectionStart to it.selectionEnd }
  }

  @Test
  fun `test v on an empty line selects its newline`() {
    // "one\n" is 0..3, so the empty line is offset 4 and its newline is the character at 4.
    val session = Session("one\n\ntwo\n", caretOffset = 4)

    session.type("v")

    assertEquals(4 to 5, session.selection, "`v` on an empty line selected nothing")
  }

  @Test
  fun `test deleting that selection removes the empty line`() {
    val session = Session("one\n\ntwo\n", caretOffset = 4)

    session.type("vd")

    assertEquals("one\ntwo\n", session.fake.document.getText())
  }

  /**
   * And a selection that merely *ends* on a line's last character is unchanged, which is the half
   * worth keeping a test for: the old rule dropped the inclusive adjustment whenever the end was at
   * the line end, and the fix must not drop it anywhere else.
   */
  @Test
  fun `test a selection inside a line still covers the character under the caret`() {
    val session = Session("one two\nthree\n", caretOffset = 0)

    session.type("ve")

    assertEquals(0 to 3, session.selection, "`ve` should cover `one`")
  }
}
