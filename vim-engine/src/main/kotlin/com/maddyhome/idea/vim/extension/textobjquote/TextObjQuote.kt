/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.textobjquote

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.maddyhome.idea.vim.KeyHandler.Companion.getInstance
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.ImmutableVimCaret
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.command.TextObjectVisualType
import com.maddyhome.idea.vim.common.CommandAliasHandler
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtensionFacade.addCommand
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.group.visual.vimSetSelection
import com.maddyhome.idea.vim.handler.TextObjectActionHandler
import com.maddyhome.idea.vim.key.MappingOwner
import com.maddyhome.idea.vim.listener.SelectionVimListenerSuppressor
import com.maddyhome.idea.vim.state.mode.Mode.OP_PENDING
import com.maddyhome.idea.vim.state.mode.Mode.VISUAL
import com.maddyhome.idea.vim.vimscript.model.VimPluginContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.expressions.Scope
import com.maddyhome.idea.vim.vimscript.model.expressions.VariableExpression
import kotlin.math.max

/**
 * `iq` / `aq` and `iQ` / `aQ`, after preservim/vim-textobj-quote.
 *
 * Curly quotes are not `i"` and `a"`. `iq` takes the text inside `“”` and `iQ` the text inside
 * `‘’`. A closing `’` followed by a letter or a digit is an apostrophe — `don’t`, `’70s` — and is
 * not the end of the quote. Nested quotes of the same kind are skipped. The keys and the quote
 * characters are `g:textobj#quote#doubleMotion`, `singleMotion`, `doubleDefault` and
 * `singleDefault`. The motion keys are read when the extension is enabled; the quote characters
 * are read when the object runs.
 *
 * `:Educate` makes `"` and `'` typed in Insert and Replace mode into those quotes. It is off until
 * asked, per buffer, because turning it on for every buffer would rewrite the quotes in code.
 * `g:textobj#quote#educate` turns it on where a buffer has not chosen. `:NoEducate` and
 * `:ToggleEducate` are the other two.
 */
@VimPlugin(name = TEXT_OBJ_QUOTE)
public fun VimInitApi.init(): Unit = registerTextObjQuote()

/** Public because `:set` and `:Plug` name it. */
public const val TEXT_OBJ_QUOTE: String = "textobj-quote"

private val EDUCATE_COMMANDS: List<String> = listOf("Educate", "NoEducate", "ToggleEducate")

public fun registerTextObjQuote() {
  val owner = MappingOwner.Plugin.get(TEXT_OBJ_QUOTE)
  for (kind in QuoteKind.entries) {
    val motion = globalString(kind.motionVariable) ?: kind.defaultMotion
    for (isInner in listOf(true, false)) {
      val plug = injector.parser.parseKeys(kind.plug(isInner))
      putExtensionHandlerMapping(MappingMode.XO, plug, owner, QuoteHandler(kind, isInner), false)
      val keys = injector.parser.parseKeys((if (isInner) "i" else "a") + motion)
      putKeyMappingIfMissing(MappingMode.XO, keys, owner, plug, true)
    }
  }

  addCommand("Educate", EducateCommandHandler { true })
  addCommand("NoEducate", EducateCommandHandler { false })
  addCommand("ToggleEducate", EducateCommandHandler { !it })
  for (kind in QuoteKind.entries) {
    val key = injector.parser.parseKeys(kind.straightQuote.toString())
    putExtensionHandlerMapping(MappingMode.I, key, owner, EducateHandler(kind), false)
  }
}

/**
 * The three commands, which the loader cannot drop on its own: an alias is held by name, and
 * disabling an extension removes mappings by owner.
 */
public fun disposeTextObjQuote() {
  EDUCATE_COMMANDS.forEach(injector.commandGroup::removeAlias)
}

private enum class QuoteKind(
  private val plugName: String,
  val motionVariable: String,
  val defaultMotion: String,
  private val pairVariable: String,
  private val defaultPair: String,
  val straightQuote: Char,
) {
  DOUBLE("d", "textobj#quote#doubleMotion", "q", "textobj#quote#doubleDefault", "“”", '"'),
  SINGLE("s", "textobj#quote#singleMotion", "Q", "textobj#quote#singleDefault", "‘’", '\''),
  ;

  val other: QuoteKind
    get() = if (this == DOUBLE) SINGLE else DOUBLE

  fun plug(isInner: Boolean): String = "<Plug>(textobj-quote-select-$plugName-${if (isInner) "i" else "a"})"

  fun quotes(): String = globalString(pairVariable)?.takeIf { it.length == 2 } ?: defaultPair
}

private class QuoteHandler(private val kind: QuoteKind, private val isInner: Boolean) : ExtensionHandler {
  override val isRepeatable: Boolean
    get() = false

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    val keyHandlerState = getInstance().keyHandlerState
    val quotes = kind.quotes()
    val textObjectHandler = QuoteTextObjectHandler(
      isInner = isInner,
      startQuote = quotes[0],
      endQuote = quotes[1],
      endQuoteMayBeApostrophe = quotes[1] == '’',
    )
    if (editor.mode is OP_PENDING) {
      keyHandlerState.commandBuilder.addAction(textObjectHandler)
      return
    }

    val count0 = operatorArguments.count0
    editor.nativeCarets().forEach { caret: VimCaret ->
      val range = textObjectHandler.getRange(editor, caret, context, max(1, count0), count0) ?: return@forEach
      SelectionVimListenerSuppressor.lock {
        if (editor.mode is VISUAL) {
          caret.vimSetSelection(range.startOffset, range.endOffset - 1, true)
          return@forEach
        }
        caret.moveToInlayAwareOffset(range.startOffset)
      }
    }
  }
}

private class QuoteTextObjectHandler(
  private val isInner: Boolean,
  private val startQuote: Char,
  private val endQuote: Char,
  private val endQuoteMayBeApostrophe: Boolean = false,
) : TextObjectActionHandler() {
  override fun getRange(
    editor: VimEditor,
    caret: ImmutableVimCaret,
    context: ExecutionContext,
    count: Int,
    rawCount: Int,
  ): TextRange? {
    val offset = caret.offset
    val prevQuote = findPrevQuote(offset, editor) ?: return null
    val nextQuote = findNextQuote(offset, editor) ?: return null

    val outerOffset = if (isInner) 0 else 1
    return TextRange(prevQuote - outerOffset, nextQuote + outerOffset)
  }

  private fun findNextQuote(offset: Int, editor: VimEditor): Int? {
    return find(offset, editor, startQuote, endQuote, offset until editor.text().length)
  }

  private fun findPrevQuote(offset: Int, editor: VimEditor): Int? {
    return find(offset, editor, endQuote, startQuote, offset downTo 0)?.plus(1)
  }

  private fun find(
    offset: Int,
    editor: VimEditor,
    oppositeChar: Char,
    lookupChar: Char,
    range: IntProgression,
  ): Int? {
    val editorText = editor.text()
    if (editorText.isEmpty()) return null
    var foundCounter = 0
    for (i in range) {
      if (i == editorText.length) continue
      val ch = editorText[i]
      // Only the default closing single quote. A pair the user configured is whatever they said
      // it was, so `‘x` is the end of that quote and not an apostrophe.
      if (ch == endQuote && endQuoteMayBeApostrophe && i < editorText.length - 1 && editorText[i + 1].isLetterOrDigit()) {
        continue
      }
      if (ch == lookupChar) foundCounter++
      if (ch == oppositeChar && i != offset) foundCounter--
      if (foundCounter == 1) return i
    }
    return null
  }

  override val visualType: TextObjectVisualType
    get() = TextObjectVisualType.CHARACTER_WISE

  override val supportsLinewiseDeletePromotion: Boolean = false
}

private const val EDUCATE_BUFFER_VARIABLE = "textobj_quote_educate_mapped"
private const val EDUCATE_GLOBAL_VARIABLE = "textobj#quote#educate"

private fun isEducating(editor: VimEditor, context: ExecutionContext): Boolean {
  val value = injector.variableService.getNullableVariableValue(
    VariableExpression(Scope.BUFFER_VARIABLE, EDUCATE_BUFFER_VARIABLE),
    editor,
    context,
    VimPluginContext,
  ) ?: injector.variableService.getGlobalVariableValue(EDUCATE_GLOBAL_VARIABLE)
  return value?.toVimNumber()?.booleanValue ?: false
}

private class EducateCommandHandler(private val newState: (Boolean) -> Boolean) : CommandAliasHandler {
  override fun execute(command: String, range: Range, editor: VimEditor, context: ExecutionContext) {
    val educate = newState(isEducating(editor, context))
    injector.variableService.storeVariable(
      VariableExpression(Scope.BUFFER_VARIABLE, EDUCATE_BUFFER_VARIABLE),
      if (educate) VimInt.ONE else VimInt.ZERO,
      editor,
      context,
      VimPluginContext,
    )
  }
}

private class EducateHandler(private val kind: QuoteKind) : ExtensionHandler {
  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    if (!isEducating(editor, context)) {
      // The quote is typed as itself. This host inserts the character, which is what it does for
      // every other key: the editor's own auto-closing pair does not run.
      injector.changeGroup.type(editor, context, kind.straightQuote)
      return
    }

    val quotes = kind.quotes()
    val text = editor.text()
    val quotesByCaret = editor.carets().associateWith { caret ->
      val before = caret.offset - 1
      val charBefore = if (before < 0) null else text.getOrNull(before)
      if (opensQuote(charBefore)) quotes[0] else quotes[1]
    }
    val quote = quotesByCaret.values.distinct().singleOrNull()
    if (quote != null) {
      injector.changeGroup.type(editor, context, quote)
      return
    }
    quotesByCaret.forEach { (caret, caretQuote) ->
      injector.changeGroup.insertText(editor, caret, caretQuote.toString())
    }
  }

  private fun opensQuote(charBeforeCaret: Char?): Boolean =
    charBeforeCaret == null || charBeforeCaret.isWhitespace() || charBeforeCaret in OPENING_CHARS ||
      charBeforeCaret in kind.other.straightQuote.toString() + kind.other.quotes()[0]

  companion object {
    private const val OPENING_CHARS = "[({&"
  }
}

private fun globalString(name: String): String? =
  (injector.variableService.getGlobalVariableValue(name) as? VimString)?.value
