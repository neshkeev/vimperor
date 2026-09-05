/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.exchange

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.intellij.vim.api.models.HighlightId
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getOffset
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.setChangeMarks
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtensionFacade
import com.maddyhome.idea.vim.extension.VimExtensionFacade.executeNormalWithoutMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.getRegister
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.extension.VimExtensionFacade.setRegister
import com.maddyhome.idea.vim.extension.exportOperatorFunction
import com.maddyhome.idea.vim.key.MappingOwner
import com.maddyhome.idea.vim.key.OperatorFunction
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.mark.Mark
import com.maddyhome.idea.vim.mark.VimMarkConstants
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.state.mode.selectionType

/**
 * This emulation misses:
 *  - `:ExchangeClear` command
 *  - `g:exchange_no_mappings` variable
 *  - `g:exchange_indent` variable (?)
 *  - Default mappings should not be applied if there is a mapping defined in `~/.ideavimrc`.
 *      This functionality requires rewriting of IdeaVim initialization, so that plugins would be
 *        loaded after `~/.ideavimrc` is executed (as vim works). But the `if no bindings` can be added even now.
 *        It just won't work if the binding is defined after `set exchange`.
 */

@VimPlugin(name = EXCHANGE)
public fun VimInitApi.init(): Unit = registerExchange()

/** Public because the plugin's extension-point adapter names them too. */
public const val EXCHANGE: String = "exchange"

public fun registerExchange() {
  val owner = MappingOwner.Plugin.get(EXCHANGE)
  putExtensionHandlerMapping(
    MappingMode.N,
    injector.parser.parseKeys(EXCHANGE_CMD),
    owner,
    ExchangeHandler(false),
    false
  )
  putExtensionHandlerMapping(MappingMode.X, injector.parser.parseKeys(EXCHANGE_CMD), owner, VExchangeHandler(), false)
  putExtensionHandlerMapping(
    MappingMode.N,
    injector.parser.parseKeys(EXCHANGE_CLEAR_CMD),
    owner,
    ExchangeClearHandler(),
    false
  )
  putExtensionHandlerMapping(
    MappingMode.N,
    injector.parser.parseKeys(EXCHANGE_LINE_CMD),
    owner,
    ExchangeHandler(true),
    false
  )

  putKeyMappingIfMissing(
    MappingMode.N,
    injector.parser.parseKeys("cx"),
    owner,
    injector.parser.parseKeys(EXCHANGE_CMD),
    true
  )
  putKeyMappingIfMissing(
    MappingMode.X,
    injector.parser.parseKeys("X"),
    owner,
    injector.parser.parseKeys(EXCHANGE_CMD),
    true
  )
  putKeyMappingIfMissing(
    MappingMode.N,
    injector.parser.parseKeys("cxc"),
    owner,
    injector.parser.parseKeys(EXCHANGE_CLEAR_CMD),
    true
  )
  putKeyMappingIfMissing(
    MappingMode.N,
    injector.parser.parseKeys("cxx"),
    owner,
    injector.parser.parseKeys(EXCHANGE_LINE_CMD),
    true
  )

  VimExtensionFacade.exportOperatorFunction(OPERATOR_FUNC, Operator())
}

/** Drops every pending exchange, for `set noexchange`. Highlights go with them. */
public fun disposeExchange() {
  pending.keys.toList().forEach { key -> pending[key]?.let { clearExchange(it.editor, key) } }
  pending.clear()
}

/**
 * The exchange waiting for its partner, per buffer.
 *
 * tpope's own plugin keeps it in `b:exchange`, and IdeaVim kept it in the editor's user data - both
 * per buffer, so an exchange started in one file cannot pair with a region in another. Neither is
 * available here: the engine has no per-editor storage, and `IjVimEditor` deliberately throws from
 * `equals` so it cannot be a map key. The path is what both hosts agree on.
 *
 * A file with no path - a scratch buffer - shares the one empty-string slot, and an entry for a file
 * that was closed with an exchange still pending outlives it, where IntelliJ's user data died with
 * the editor. Both are bounded by "exchanges started and never finished", and `disposeExchange`
 * clears the lot.
 */
private val pending: MutableMap<String, Exchange> = mutableMapOf()

private fun keyOf(editor: VimEditor): String = editor.getPath() ?: ""

/**
 * The highlight on the region waiting for its partner, or null if nothing is pending.
 *
 * Exists for the plugin's own tests, which used to reach the `RangeHighlighter` through the
 * editor's user data and cannot any more. The state is the extension's; where it lives is not
 * something a caller should have to know.
 */
public fun pendingExchangeHighlight(editor: VimEditor): HighlightId? =
  pending[keyOf(editor)]?.getHighlighter()

internal fun clearExchange(editor: VimEditor, key: String = keyOf(editor)) {
  pending.remove(key)?.getHighlighter()?.let { injector.highlightingService.removeHighlighter(editor, it) }
}

private const val EXCHANGE_CMD = "<Plug>(Exchange)"
private const val EXCHANGE_CLEAR_CMD = "<Plug>(ExchangeClear)"
private const val EXCHANGE_LINE_CMD = "<Plug>(ExchangeLine)"
private const val OPERATOR_FUNC = "ExchangeOperatorFunc"

private class ExchangeHandler(private val isLine: Boolean) : ExtensionHandler {
  override val isRepeatable = true

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    injector.globalOptions().operatorfunc = OPERATOR_FUNC
    executeNormalWithoutMapping(injector.parser.parseKeys(if (isLine) "g@_" else "g@"), editor)
  }
}

private class ExchangeClearHandler : ExtensionHandler {
  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    clearExchange(editor)
  }
}

private class VExchangeHandler : ExtensionHandler {
  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    val mode = editor.mode
    // Leave visual mode to create selection marks
    executeNormalWithoutMapping(injector.parser.parseKeys("<Esc>"), editor)
    Operator(true).apply(editor, context, mode.selectionType ?: SelectionType.CHARACTER_WISE)
  }
}

private class Operator(private val isVisual: Boolean = false) : OperatorFunction {
  fun VimEditor.getMarkOffset(mark: Mark) = getOffset(mark.line, mark.col)
  fun SelectionType.getString() = when (this) {
    SelectionType.CHARACTER_WISE -> "v"
    SelectionType.LINE_WISE -> "V"
    SelectionType.BLOCK_WISE -> "\\<C-V>"
  }

  override fun apply(editor: VimEditor, context: ExecutionContext, selectionType: SelectionType?): Boolean {
    /**
     * The region waiting for its partner, lit in whatever this editor lights a search match in.
     *
     * IdeaVim asked for `LINES_IN_RANGE` on a linewise exchange, which paints the full width of
     * the line rather than stopping at its last character. `VimHighlightingService` has no such
     * distinction - a highlight is a range - so a linewise mark now ends where the text does.
     * Visible, and the alternative was a host concept in the interface for one extension's
     * cosmetics.
     */
    fun highlightExchange(ex: Exchange): HighlightId {
      val isVisualLine = ex.type == SelectionType.LINE_WISE
      val endAdj = if (!isVisualLine) 1 else 0
      return injector.highlightingService.addSearchHighlighter(
        editor,
        editor.getMarkOffset(ex.start),
        (editor.getMarkOffset(ex.end) + endAdj).coerceAtMost(editor.fileSize().toInt()),
        null,
        null,
      )
    }

    val currentExchange = getExchange(editor, isVisual, selectionType ?: SelectionType.CHARACTER_WISE)
    val exchange1 = pending[keyOf(editor)]
    if (exchange1 == null) {
      currentExchange.setHighlighter(highlightExchange(currentExchange))
      pending[keyOf(editor)] = currentExchange
      return true
    } else {
      val cmp = compareExchanges(exchange1, currentExchange)
      var reverse = false
      var expand = false
      val (ex1, ex2) = when (cmp) {
        ExchangeCompareResult.OVERLAP -> return false
        ExchangeCompareResult.OUTER -> {
          reverse = true
          expand = true
          Pair(currentExchange, exchange1)
        }

        ExchangeCompareResult.INNER -> {
          expand = true
          Pair(exchange1, currentExchange)
        }

        ExchangeCompareResult.GT -> {
          reverse = true
          Pair(currentExchange, exchange1)
        }

        ExchangeCompareResult.LT -> {
          Pair(exchange1, currentExchange)
        }
      }
      exchange(editor, ex1, ex2, reverse, expand)
      clearExchange(editor)
      return true
    }
  }

  // todo make it multicaret
  private fun exchange(editor: VimEditor, ex1: Exchange, ex2: Exchange, reverse: Boolean, expand: Boolean) {
    fun pasteExchange(sourceExchange: Exchange, targetExchange: Exchange) {
      injector.markService.setChangeMarks(
        editor.primaryCaret(),
        TextRange(editor.getMarkOffset(targetExchange.start), editor.getMarkOffset(targetExchange.end) + 1),
      )
      // do this instead of direct text manipulation to set change marks
      setRegister('z', injector.parser.stringToKeys(sourceExchange.text), sourceExchange.type)
      executeNormalWithoutMapping(injector.parser.stringToKeys("`[${targetExchange.type.getString()}`]\"zp"), editor)
    }

    fun fixCursor(ex1: Exchange, ex2: Exchange, reverse: Boolean) {
      val primaryCaret = editor.primaryCaret()
      if (reverse) {
        primaryCaret.moveToInlayAwareOffset(editor.getMarkOffset(ex1.start))
      } else {
        if (ex1.start.line == ex2.start.line) {
          val horizontalOffset = ex1.end.col - ex2.end.col
          primaryCaret.moveToInlayAwareOffset(editor.getOffset(ex1.start.line, ex1.start.col - horizontalOffset))
        } else if (ex1.end.line - ex1.start.line != ex2.end.line - ex2.start.line) {
          val verticalOffset = ex1.end.line - ex2.end.line
          primaryCaret.moveToInlayAwareOffset(editor.getOffset(ex1.start.line - verticalOffset, ex1.start.col))
        }
      }
    }

    val zRegText = getRegister(editor, 'z')
    val unnRegText = getRegister(editor, '"')
    val startRegText = getRegister(editor, '*')
    val plusRegText = getRegister(editor, '+')

    // TODO handle:
    // 	" Compare using =~ because "'==' != 0" returns 0
    // 	let indent = s:get_setting('exchange_indent', 1) !~ 0 && a:x.type ==# 'V' && a:y.type ==# 'V'
    pasteExchange(ex1, ex2)
    if (!expand) {
      pasteExchange(ex2, ex1)
    }
    // TODO: handle: if ident
    if (!expand) {
      fixCursor(ex1, ex2, reverse)
    }
    setRegister('z', zRegText)
    setRegister('"', unnRegText)
    setRegister('*', startRegText)
    setRegister('+', plusRegText)
  }

  private fun compareExchanges(x: Exchange, y: Exchange): ExchangeCompareResult {
    fun intersects(x: Exchange, y: Exchange) =
      x.end.line < y.start.line ||
        x.start.line > y.end.line ||
        x.end.col < y.start.col ||
        x.start.col > y.end.col

    fun comparePos(x: Mark, y: Mark): Int =
      if (x.line == y.line) {
        x.col - y.col
      } else {
        x.line - y.line
      }

    return if (x.type == SelectionType.BLOCK_WISE && y.type == SelectionType.BLOCK_WISE) {
      when {
        intersects(x, y) -> {
          ExchangeCompareResult.OVERLAP
        }

        x.start.col <= y.start.col -> {
          ExchangeCompareResult.LT
        }

        else -> {
          ExchangeCompareResult.GT
        }
      }
    } else if (comparePos(x.start, y.start) <= 0 && comparePos(x.end, y.end) >= 0) {
      ExchangeCompareResult.OUTER
    } else if (comparePos(y.start, x.start) <= 0 && comparePos(y.end, x.end) >= 0) {
      ExchangeCompareResult.INNER
    } else if (comparePos(x.start, y.end) <= 0 && comparePos(y.start, x.end) <= 0 ||
      comparePos(y.start, x.end) <= 0 && comparePos(x.start, y.end) <= 0
    ) {
      ExchangeCompareResult.OVERLAP
    } else {
      val cmp = comparePos(x.start, y.start)
      when {
        cmp == 0 -> ExchangeCompareResult.OVERLAP
        cmp < 0 -> ExchangeCompareResult.LT
        else -> ExchangeCompareResult.GT
      }
    }
  }

  enum class ExchangeCompareResult {
    OVERLAP,
    OUTER,
    INNER,
    LT,
    GT,
  }

  private fun getExchange(editor: VimEditor, isVisual: Boolean, selectionType: SelectionType): Exchange {
    // TODO: improve VimKeyStroke list to sting conversion
    fun getRegisterText(reg: Char): String = getRegister(editor, reg)?.map { it.keyChar }?.joinToString("") ?: ""
    fun getMarks(isVisual: Boolean): Pair<Mark, Mark> {
      val (startMark, endMark) =
        if (isVisual) {
          Pair(VimMarkConstants.MARK_VISUAL_START, VimMarkConstants.MARK_VISUAL_END)
        } else {
          Pair(VimMarkConstants.MARK_CHANGE_START, VimMarkConstants.MARK_CHANGE_END)
        }
      val marks = injector.markService
      // todo make it multicaret
      return Pair(
        marks.getMark(editor.primaryCaret(), startMark)!!,
        marks.getMark(editor.primaryCaret(), endMark)!!
      )
    }

    val unnRegText = getRegister(editor, '"')
    val starRegText = getRegister(editor, '*')
    val plusRegText = getRegister(editor, '+')

    val (selectionStart, selectionEnd) = getMarks(isVisual)
    if (isVisual) {
      executeNormalWithoutMapping(injector.parser.parseKeys("gvy"), editor)
      // TODO: handle
      // if &selection ==# 'exclusive' && start != end
      // 			let end.column -= len(matchstr(@@, '\_.$'))
    } else {
      when (selectionType) {
        SelectionType.LINE_WISE -> executeNormalWithoutMapping(injector.parser.stringToKeys("`[V`]y"), editor)
        SelectionType.BLOCK_WISE -> executeNormalWithoutMapping(
          injector.parser.stringToKeys("""`[<C-V>`]y"""),
          editor
        )

        SelectionType.CHARACTER_WISE -> executeNormalWithoutMapping(injector.parser.stringToKeys("`[v`]y"), editor)
      }
    }

    val text = getRegisterText('"')

    setRegister('"', unnRegText)
    setRegister('*', starRegText)
    setRegister('+', plusRegText)

    return if (selectionStart.offset(editor) <= selectionEnd.offset(editor)) {
      Exchange(editor, selectionType, selectionStart, selectionEnd, text)
    } else {
      Exchange(editor, selectionType, selectionEnd, selectionStart, text)
    }
  }
  }

// End mark has always greater of eq offset than start mark
internal class Exchange(
  val editor: VimEditor,
  val type: SelectionType,
  val start: Mark,
  val end: Mark,
  val text: String,
) {
  private var myHighlighter: HighlightId? = null
  fun setHighlighter(highlighter: HighlightId) {
  myHighlighter = highlighter
  }

  fun getHighlighter(): HighlightId? = myHighlighter
}
