/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.visualmulti

import com.github.neshkeev.vimperor.extension.readCharacter
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.MutableVimEditor
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getText
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.abolish.CaseStyle
import com.maddyhome.idea.vim.extension.multiplecursors.selectRange
import com.maddyhome.idea.vim.extension.multiplecursors.selectedRange
import com.maddyhome.idea.vim.extension.multiplecursors.wordRange
import com.maddyhome.idea.vim.state.mode.inVisualMode

/** The conversions of the vim-visual-multi menu, by key. Most of them come from vim-abolish. */
private val conversions: Map<Char, (String) -> String> = mapOf(
  'u' to String::lowercase,
  'U' to String::uppercase,
  'C' to { text -> text.lowercase().replaceFirstChar(Char::uppercaseChar) },
  't' to CaseStyle.TITLE::recase,
  'c' to CaseStyle.CAMEL::recase,
  'P' to CaseStyle.PASCAL::recase,
  's' to CaseStyle.SNAKE::recase,
  'S' to CaseStyle.UPPER_SNAKE::recase,
  '-' to CaseStyle.KEBAB::recase,
  '.' to CaseStyle.DOT::recase,
  ' ' to CaseStyle.SPACE::recase,
)

/** Asks for a conversion, and converts the text of each region, or the word under each cursor. */
internal class CaseConversionHandler : ExtensionHandler {

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    // The mode has to be read when the key is *pressed*, not when the conversion arrives: the prompt
    // is open in between and nothing says the mode is the same by then.
    val convertsRegions = editor.inVisualMode
    readCharacter(editor, context, label = "Case conversion: ") { key ->
      val convert = conversions[key] ?: return@readCharacter
      injector.application.runWriteAction {
        // From the last one, so that the replacements do not move the ranges still to be converted.
        for (caret in editor.carets().reversed()) {
          val range = (if (convertsRegions) caret.selectedRange else wordRange(editor, caret)) ?: continue
          val converted = convert(editor.getText(range))
          (editor as MutableVimEditor).replaceString(range.startOffset, range.endOffset, converted)
          updateCaret(editor, caret, TextRange(range.startOffset, range.startOffset + converted.length), convertsRegions)
        }
      }
    }
  }

  private fun updateCaret(editor: VimEditor, caret: VimCaret, converted: TextRange, selects: Boolean) {
    if (selects) {
      selectRange(editor, caret, converted)
    } else {
      caret.moveToOffset(converted.startOffset)
    }
  }
}
