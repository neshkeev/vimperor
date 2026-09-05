/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.extension.commentary

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.ImmutableVimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.command.TextObjectVisualType
import com.maddyhome.idea.vim.common.CommandAliasHandler
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.ex.ranges.toTextRange
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtensionFacade
import com.maddyhome.idea.vim.extension.VimExtensionFacade.addCommand
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.extension.exportOperatorFunction
import com.maddyhome.idea.vim.handler.TextObjectActionHandler
import com.maddyhome.idea.vim.key.MappingOwner
import com.maddyhome.idea.vim.key.OperatorFunction
import com.maddyhome.idea.vim.state.mode.SelectionType

/**
 * `gc{motion}`, `gcc`, `gcu` and `:Commentary`, after tpope's `vim-commentary`.
 *
 * What kept it in the plugin was one function, and it was not the mappings or the text object: it
 * was toggling the comment itself, which needs to know that a comment is `//` here and `#` there.
 * That is [com.maddyhome.idea.vim.api.VimCommentService] now, and both hosts already had the
 * capability - IntelliJ in a `Commenter` reached over RPC, VS Code in the language configuration
 * behind `editor.action.commentLine`.
 *
 * `dgc`, the text object over a run of comment lines, is the one part that is not portable: it asks
 * `injector.psiService.getCommentBlockRange`, and a host with no syntax tree answers `null`. So on
 * VS Code `gc` comments and uncomments and `dgc` does nothing, which is the honest outcome rather
 * than a guess from the file extension.
 */
@VimPlugin(name = COMMENTARY)
public fun VimInitApi.init(): Unit = registerCommentary(this)

/** Public because the plugin's extension-point adapter names them too. */
public const val COMMENTARY: String = "commentary"
public const val COMMENTARY_COMMAND: String = "Commentary"

private const val OPERATOR_FUNC = "CommentaryOperatorFunc"

public object Util {
  /**
   * Toggles the comment over [range], and the only thing this file does that the engine cannot.
   *
   * [resetCaret] is false for `:Commentary`, which leaves the caret where the range put it, and
   * true for `gc`, which moves it to the start of what it commented.
   */
  public fun doCommentary(
    editor: VimEditor,
    context: ExecutionContext,
    range: TextRange,
    selectionType: SelectionType,
    resetCaret: Boolean = true,
  ): Boolean {
    val caretOffset = if (resetCaret) range.startOffset else null
    if (selectionType !== SelectionType.LINE_WISE) {
      return injector.commentService.toggleBlockComment(editor, context, range, caretOffset)
    }
    val startLine = editor.offsetToBufferPosition(range.startOffset).line
    var endLine = editor.offsetToBufferPosition(range.endOffset).line
    // Adjust endLine if the range ends at the start of a line (don't include that line)
    if (endLine > startLine && editor.getLineStartOffset(endLine) == range.endOffset) {
      endLine--
    }
    return injector.commentService.toggleLineComment(editor, context, startLine, endLine, caretOffset)
  }
}

/** Everything the extension registers. `initApi` is named because the mappings scope needs it. */
public fun registerCommentary(initApi: VimInitApi) {
  val owner = MappingOwner.Plugin.get(COMMENTARY)
  val plugCommentaryKeys = injector.parser.parseKeys("<Plug>Commentary")
  val plugCommentaryLineKeys = injector.parser.parseKeys("<Plug>CommentaryLine")

  // <Plug>Commentary in Normal and Visual mode: set up operator pending motion
  initApi.mappings {
    nnoremap("<Plug>Commentary") {
      commands().setOperatorFunction(OPERATOR_FUNC)
      normal("g@")
    }
    xnoremap("<Plug>Commentary") {
      commands().setOperatorFunction(OPERATOR_FUNC)
      normal("g@")
    }
  }

  putExtensionHandlerMapping(MappingMode.O, plugCommentaryKeys, owner, CommentaryMappingHandler(), false)
  putKeyMappingIfMissing(MappingMode.N, plugCommentaryLineKeys, owner, injector.parser.parseKeys("gc_"), true)

  putKeyMappingIfMissing(MappingMode.NXO, injector.parser.parseKeys("gc"), owner, plugCommentaryKeys, true)
  putKeyMappingIfMissing(MappingMode.N, injector.parser.parseKeys("gcc"), owner, plugCommentaryLineKeys, true)
  putKeyMappingIfMissing(
    MappingMode.N,
    injector.parser.parseKeys("gcu"),
    owner,
    injector.parser.parseKeys("<Plug>Commentary<Plug>Commentary"),
    true,
  )

  // Previous versions of IdeaVim used different mappings to Vim's Commentary. Make sure everything works if someone
  // is still using the old mapping
  putKeyMapping(MappingMode.N, injector.parser.parseKeys("<Plug>(CommentMotion)"), owner, plugCommentaryKeys, true)
  putKeyMapping(MappingMode.XO, injector.parser.parseKeys("<Plug>(CommentMotionV)"), owner, plugCommentaryKeys, true)
  putKeyMapping(MappingMode.N, injector.parser.parseKeys("<Plug>(CommentLine)"), owner, plugCommentaryLineKeys, true)

  addCommand(COMMENTARY_COMMAND, CommentaryCommandAliasHandler())

  VimExtensionFacade.exportOperatorFunction(OPERATOR_FUNC, CommentaryOperatorFunction())
  }

private class CommentaryOperatorFunction : OperatorFunction {
  // todo make it multicaret
  override fun apply(editor: VimEditor, context: ExecutionContext, selectionType: SelectionType?): Boolean {
  val range = injector.markService.getChangeMarks(editor.primaryCaret()) ?: return false
  return Util.doCommentary(editor, context, range, selectionType ?: SelectionType.CHARACTER_WISE, true)
  }
}

private class CommentaryMappingHandler : ExtensionHandler {
  override val isRepeatable = true

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    val keyState = KeyHandler.getInstance().keyHandlerState
    keyState.commandBuilder.addAction(CommentaryTextObjectMotionHandler)
  }
}

/**
 * The text object handler that provides the motion in e.g. `dgc`
 *
 * Delegates to [VimPsiService.getCommentBlockRange][com.maddyhome.idea.vim.api.VimPsiService.getCommentBlockRange]
 * which uses PSI on the backend to detect contiguous comment lines.
 */
private object CommentaryTextObjectMotionHandler : TextObjectActionHandler() {
  override val visualType: TextObjectVisualType = TextObjectVisualType.LINE_WISE

  override fun getRange(
    editor: VimEditor,
    caret: ImmutableVimCaret,
    context: ExecutionContext,
    count: Int,
    rawCount: Int,
  ): TextRange? {
    return injector.psiService.getCommentBlockRange(editor, caret.getBufferPosition().line)
  }
}

/**
 * The handler for the `Commentary` user defined command
 *
 * Used like `:1,3Commentary` or `g/fun/Commentary`
 */
private class CommentaryCommandAliasHandler : CommandAliasHandler {
  override fun execute(command: String, range: Range, editor: VimEditor, context: ExecutionContext) {
    Util.doCommentary(
      editor,
      context,
      range.getLineRange(editor, editor.primaryCaret()).toTextRange(editor),
      SelectionType.LINE_WISE,
      false
    )
  }
}
