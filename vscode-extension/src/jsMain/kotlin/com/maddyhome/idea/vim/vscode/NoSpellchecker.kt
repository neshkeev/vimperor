/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.SpellcheckerService
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector

/**
 * The spell checker VS Code does not have, saying so.
 *
 * IntelliJ ships one and exposes exactly the three operations the engine's `zg`, `zw` and `z=` -
 * and now `:spellgood`, `:spellwrong` and `:spellundo` - are made of. **VS Code ships none.** There
 * is no spell checking in the editor at all: the popular extensions bring their own, keep their own
 * dictionaries, and expose them through their own commands rather than through an API another
 * extension can reach.
 *
 * A service rather than the `TODO()` that was here, and the difference is what the reader sees. A
 * `TODO()` reaches the executor as `NotImplementedError` and prints "Not implemented yet :(" with
 * no clue which part was not implemented - it was `zg`'s answer in this host until now. This says
 * what is missing and what to do instead, once per attempt, and it means `:spellgood` in a shared
 * `.ideavimrc` is a line that reports something a reader can act on rather than a line that looks
 * like a bug in Vimperor.
 */
internal object NoSpellchecker : SpellcheckerService {

  override fun addWordToDictionary(word: String, editor: VimEditor) = report(editor)

  override fun removeWordFromDictionary(word: String, editor: VimEditor) = report(editor)

  override fun selectSuggestion(word: String, editor: VimEditor, caret: VimCaret) = report(editor)

  private fun report(editor: VimEditor) {
    injector.messages.showErrorMessage(
      editor,
      "VS Code has no spell checker of its own, so there is no dictionary to change. A spell " +
        "checking extension keeps its own and offers its own commands, which `:action` can run.",
    )
  }
}
