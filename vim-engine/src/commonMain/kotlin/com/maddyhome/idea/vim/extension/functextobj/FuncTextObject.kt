/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.functextobj

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.ImmutableVimCaret
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.command.TextObjectVisualType
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.group.visual.vimSetSelection
import com.maddyhome.idea.vim.handler.TextObjectActionHandler
import com.maddyhome.idea.vim.key.MappingOwner
import com.maddyhome.idea.vim.listener.SelectionVimListenerSuppressor
import com.maddyhome.idea.vim.state.mode.Mode
import kotlin.math.max

/**
 * Vim-style text objects for a function or method definition.
 *
 * Inspired by kana/vim-textobj-function: https://github.com/kana/vim-textobj-function
 *
 *  - `am` "a method definition" - signature and body, without the doc comment or the annotations
 *    directly above it.
 *  - `aM` "a Method definition" - the same, with them.
 *  - `im` "inner method definition" - what is between the body's braces.
 *
 * ## What it asks the host
 *
 * Where a function begins and ends, which needs to know what a function *is* in the language at
 * hand - `injector.psiService.getMethodRanges`. IntelliJ answers from its PSI tree; a host with no
 * syntax tree answers null and these text objects then do nothing.
 *
 * That is why this is compiled by both hosts and bundled by only one. The mappings would be there
 * in VS Code and every one of them inert, which is worse than absent - so `VsCodeExtensions.BUNDLED`
 * leaves it out until that host can answer. See `yankring` for the other extension in that position
 * and a different reason for it.
 */
@VimPlugin(name = FUNC_TEXT_OBJ)
public fun VimInitApi.init(): Unit = registerFuncTextObj()

/** Public because the plugin's extension-point adapter names it too. */
public const val FUNC_TEXT_OBJ: String = "functextobj"

public fun registerFuncTextObj() {
  val owner = MappingOwner.Plugin.get(FUNC_TEXT_OBJ)
  for ((keys, plug, kind) in MAPPINGS) {
    putExtensionHandlerMapping(
      MappingMode.XO,
      injector.parser.parseKeys(plug),
      owner,
      FuncTextObjectHandler(kind),
      false,
    )
    putKeyMappingIfMissing(MappingMode.XO, injector.parser.parseKeys(keys), owner, injector.parser.parseKeys(plug), true)
  }
}

private val MAPPINGS = listOf(
  Triple("am", "<Plug>(textobj-function-am)", FuncRange.OUTER_NO_DOC),
  Triple("aM", "<Plug>(textobj-function-aM)", FuncRange.OUTER_WITH_DOC),
  Triple("im", "<Plug>(textobj-function-im)", FuncRange.INNER),
)

internal enum class FuncRange { OUTER_NO_DOC, OUTER_WITH_DOC, INNER }

internal class FuncTextObjectHandler(private val rangeKind: FuncRange) : ExtensionHandler {
  override val isRepeatable: Boolean get() = false

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    val action = MethodRangeActionHandler(rangeKind)
    if (editor.mode is Mode.OP_PENDING) {
      KeyHandler.getInstance().keyHandlerState.commandBuilder.addAction(action)
      return
    }
    val count = max(1, operatorArguments.count0)
    editor.nativeCarets().forEach { caret: VimCaret ->
      val range = action.getRange(editor, caret, context, count, operatorArguments.count0) ?: return@forEach
      applyRange(editor, caret, range)
    }
  }

  private fun applyRange(editor: VimEditor, caret: VimCaret, range: TextRange) {
    SelectionVimListenerSuppressor.lock {
      if (editor.mode is Mode.VISUAL) {
        caret.vimSetSelection(range.startOffset, range.endOffset - 1, true)
      } else {
        caret.moveToInlayAwareOffset(range.startOffset)
      }
    }
  }
}

internal class MethodRangeActionHandler(private val rangeKind: FuncRange) : TextObjectActionHandler() {
  // vim-textobj-function selects a whole method linewise (`['V', ...]`). The inner object keeps IdeaVim's charwise
  // body-span semantics (its range runs from the signature line into the closing-brace line, so promoting it to
  // linewise would swallow those lines).
  override val visualType: TextObjectVisualType
    get() = if (rangeKind == FuncRange.INNER) TextObjectVisualType.CHARACTER_WISE else TextObjectVisualType.LINE_WISE

  override fun getRange(
    editor: VimEditor,
    caret: ImmutableVimCaret,
    context: ExecutionContext,
    count: Int,
    rawCount: Int,
  ): TextRange? {
    val ranges = injector.psiService.getMethodRanges(editor, caret.offset) ?: return null
    return when (rangeKind) {
      FuncRange.OUTER_NO_DOC -> TextRange(ranges.definitionStart, ranges.end)
      FuncRange.OUTER_WITH_DOC -> TextRange(ranges.fullStart, ranges.end)
      FuncRange.INNER -> ranges.body?.let { TextRange(it.first, it.second) }
    }
  }
}
