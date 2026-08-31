/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.key.MappingOwner
import com.maddyhome.idea.vim.vimscript.model.commands.mapping.ParseMapCommandArguments

/**
 * `:loadkeymap`, which turns a table of `from to` rows into language mappings.
 *
 * Named a host group because Vim's `'keymap'` option loads a keymap *file* by name, and where those
 * files live is a host question. The part that is here is not: parsing the rows and registering
 * `:lmap`s is the same work in every host, and this lived in the IntelliJ module only because that
 * was the only host there was.
 */
abstract class VimKeymapGroupBase : VimKeymapGroup {
  override fun loadKeymap(editor: VimEditor, argument: String) {
    // Vim reads the table out of the file that is being sourced, so there is no table at all when
    // this is typed at the command line.
    if (!injector.vimscriptExecutor.executingFile) {
      throw exExceptionMessage("E105", ":loadkeymap")
    }

    argument.split("\n").filter { isNotWhite(it) }.forEach { line ->
      val parsed = ParseMapCommandArguments.parseKeymapEntry(line)
        ?: throw exExceptionMessage("E474.arg", line)
      injector.keyGroup.putKeyMapping(
        setOf(MappingMode.LANG),
        parsed.fromKeys,
        MappingOwner.IdeaVim.Keymap,
        injector.parser.parseKeys(parsed.secondArgument),
        true,
      )
    }
  }

  /** A row is a row; a blank line is skipped and `"` starts a comment, as everywhere in Vimscript. */
  private fun isNotWhite(string: String): Boolean = string.trim().isNotEmpty() && !string.startsWith("\"")
}
