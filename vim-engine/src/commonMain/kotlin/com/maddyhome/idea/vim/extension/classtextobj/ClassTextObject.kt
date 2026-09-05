/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.classtextobj

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
 * `ac`, a Vim-style text object for a class definition - the declaration line and the body.
 *
 * ## What it asks the host
 *
 * Where the class begins and ends, which needs to know what a class *is* in the language at hand -
 * `injector.psiService.getClassRange`. IntelliJ answers from its PSI tree; a host with no syntax
 * tree answers null and `ac` then does nothing, which is why this is compiled by both hosts and
 * bundled by only one. See `functextobj`, which is in the same position for the same reason.
 */
@VimPlugin(name = CLASS_TEXT_OBJ)
public fun VimInitApi.init(): Unit = registerClassTextObj()

/** Public because the plugin's extension-point adapter names it too. */
public const val CLASS_TEXT_OBJ: String = "classtextobj"

public fun registerClassTextObj() {
  val owner = MappingOwner.Plugin.get(CLASS_TEXT_OBJ)
  val plug = "<Plug>(textobj-class-ac)"
  putExtensionHandlerMapping(
    MappingMode.XO,
    injector.parser.parseKeys(plug),
    owner,
    ClassTextObjectHandler(),
    false,
  )
  putKeyMappingIfMissing(MappingMode.XO, injector.parser.parseKeys("ac"), owner, injector.parser.parseKeys(plug), true)
}

internal class ClassTextObjectHandler : ExtensionHandler {
  override val isRepeatable: Boolean get() = false

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    val action = ClassRangeActionHandler()
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

internal class ClassRangeActionHandler : TextObjectActionHandler() {
  // vim-textobj-python selects a class linewise (`['V', ...]`), so a class text object is linewise.
  override val visualType: TextObjectVisualType get() = TextObjectVisualType.LINE_WISE

  override fun getRange(
    editor: VimEditor,
    caret: ImmutableVimCaret,
    context: ExecutionContext,
    count: Int,
    rawCount: Int,
  ): TextRange? {
    return injector.psiService.getClassRange(editor, caret.offset)
  }
}
