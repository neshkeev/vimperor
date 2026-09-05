/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.extension.surround

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.endsWithNewLine
import com.maddyhome.idea.vim.api.getLeadingCharacterOffset
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.setChangeMarks
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.diagnostic.VimLogger
import com.maddyhome.idea.vim.diagnostic.vimLogger
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtensionFacade
import com.maddyhome.idea.vim.extension.VimExtensionFacade.executeNormalWithoutMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.getRegisterForCaret
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.extension.VimExtensionFacade.setRegisterForCaret
import com.maddyhome.idea.vim.extension.exportOperatorFunction
import com.maddyhome.idea.vim.extension.readCharacter
import com.maddyhome.idea.vim.extension.readKeys
import com.maddyhome.idea.vim.group.findBlockRange
import com.maddyhome.idea.vim.helper.exitVisualMode
import com.maddyhome.idea.vim.key.MappingOwner
import com.maddyhome.idea.vim.key.OperatorFunction
import com.maddyhome.idea.vim.key.VimKeyCodes
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.options.helpers.ClipboardOptionHelper
import com.maddyhome.idea.vim.put.PutData
import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.state.mode.selectionType

/**
 * Port of vim-surround.
 *
 * See https://github.com/tpope/vim-surround
 *
 * @author dhleong
 * @author vlan
 */
/**
 * Port of [vim-surround](https://github.com/tpope/vim-surround): `ys{motion}{char}` wraps,
 * `cs{from}{to}` changes what wraps, `ds{char}` unwraps, `S` in visual.
 *
 * @author dhleong
 * @author vlan
 *
 * ## Why this one was last
 *
 * It is the only extension of the 26 that has to *ask* for something before it can act, and it asks
 * twice: a character for the pair, and for `<`, `t`, `f` and `F` a whole string. Both were blocking
 * reads - `injector.keyGroup.getChar` and `VimExtensionFacade.inputString` - and neither can block
 * on a runtime with one thread.
 *
 * Both have non-blocking counterparts that the engine already had and both hosts already implement:
 * [readCharacter], over modal input, and `injector.commandLine.readInputAndProcess`, which is what
 * `input()` should have been using all along. So every handler here is now written inside out: it
 * asks, returns, and does its work in a callback one or more keystrokes later.
 *
 * The one place that shows is [Operator.apply]. Its `Boolean` says whether the operator succeeded,
 * and it now has to answer before it knows - so it answers `false` only for what it can still
 * decide synchronously (no range to operate on) and `true` otherwise. A pair the user asks for that
 * does not exist quietly does nothing, where it used to abort the operator.
 */
@VimPlugin(name = SURROUND)
public fun VimInitApi.init(): Unit = registerSurround()

/** Public because the plugin's extension-point adapter names it too. */
public const val SURROUND: String = "surround"

private const val NO_MAPPINGS = "surround_no_mappings"

public fun registerSurround() {
  val owner = MappingOwner.Plugin.get(SURROUND)
  putExtensionHandlerMapping(
    MappingMode.N,
    injector.parser.parseKeys("<Plug>YSurround"),
    owner,
    YSurroundHandler(),
    false
  )
  putExtensionHandlerMapping(
    MappingMode.N,
    injector.parser.parseKeys("<Plug>Yssurround"),
    owner,
    YSSurroundHandler(),
    false
  )
  putExtensionHandlerMapping(
    MappingMode.N,
    injector.parser.parseKeys("<Plug>CSurround"),
    owner,
    CSurroundHandler(),
    false
  )
  putExtensionHandlerMapping(
    MappingMode.N,
    injector.parser.parseKeys("<Plug>DSurround"),
    owner,
    DSurroundHandler(),
    false
  )
  putExtensionHandlerMapping(
    MappingMode.XO,
    injector.parser.parseKeys("<Plug>VSurround"),
    owner,
    VSurroundHandler(),
    false
  )

  val noMappings =
    injector.variableService.getGlobalVariableValue(NO_MAPPINGS)?.toVimNumber()?.booleanValue ?: false
  if (!noMappings) {
    putKeyMappingIfMissing(
      MappingMode.N,
      injector.parser.parseKeys("ys"),
      owner,
      injector.parser.parseKeys("<Plug>YSurround"),
      true
    )
    putKeyMappingIfMissing(
      MappingMode.N,
      injector.parser.parseKeys("yss"),
      owner,
      injector.parser.parseKeys("<Plug>Yssurround"),
      true
    )
    putKeyMappingIfMissing(
      MappingMode.N,
      injector.parser.parseKeys("cs"),
      owner,
      injector.parser.parseKeys("<Plug>CSurround"),
      true
    )
    putKeyMappingIfMissing(
      MappingMode.N,
      injector.parser.parseKeys("ds"),
      owner,
      injector.parser.parseKeys("<Plug>DSurround"),
      true
    )
    putKeyMappingIfMissing(
      MappingMode.XO,
      injector.parser.parseKeys("S"),
      owner,
      injector.parser.parseKeys("<Plug>VSurround"),
      true
    )
  }

  VimExtensionFacade.exportOperatorFunction(OPERATOR_FUNC, Operator())
}

private class YSurroundHandler : ExtensionHandler {
  override val isRepeatable = true

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    injector.globalOptions().operatorfunc = OPERATOR_FUNC
    executeNormalWithoutMapping(injector.parser.parseKeys("g@"), editor)
  }
}

private class YSSurroundHandler : ExtensionHandler {
  override val isRepeatable = true

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    readCharacter(editor, context) { c ->
      getOrInputPair(c, editor, context) { pair ->
        if (pair != null) surroundLines(editor, pair)
      }
    }
  }

  private fun surroundLines(editor: VimEditor, pair: SurroundPair) {
    editor.forEachCaret {
      val line = it.getBufferPosition().line
      val lineStartOffset = editor.getLeadingCharacterOffset(line)
      val lineEndOffset = editor.getLineEndOffset(line)
      val lastNonWhiteSpaceOffset = getLastNonWhitespaceCharacterOffset(editor.text(), lineStartOffset, lineEndOffset)
      if (lastNonWhiteSpaceOffset != null) {
        val range = TextRange(lineStartOffset, lastNonWhiteSpaceOffset + 1)
        performSurround(pair, range, it)
      }
    }
    // Jump back to start
    if (editor.mode !is Mode.NORMAL) {
      editor.mode = Mode.NORMAL()
    }
    executeNormalWithoutMapping(injector.parser.parseKeys("`["), editor)
  }

  private fun getLastNonWhitespaceCharacterOffset(chars: CharSequence, startOffset: Int, endOffset: Int): Int? {
    var i = endOffset - 1
    while (i >= startOffset) {
      if (!chars[i].isWhitespace()) return i
      --i
    }
    return null
  }
}

private class VSurroundHandler : ExtensionHandler {
  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    val selectionStart = editor.primaryCaret().selectionStart
    // Leaving visual mode has to wait for the surround, which now happens in a callback - so it is
    // handed to the operator rather than done on the next line, where it would run first.
    val leaveVisual = {
      injector.application.runWriteAction {
        editor.exitVisualMode()
        editor.primaryCaret().moveToOffset(selectionStart)

        // Reset the key handler so that the command trie is updated for the new mode (Normal)
        // TODO: This should probably be handled by ToHandlerMapping.execute
        KeyHandler.getInstance().reset(editor)
      }
    }
    // NB: Operator ignores SelectionType anyway
    Operator(leaveVisual).apply(editor, context, editor.mode.selectionType)
  }
}

private class CSurroundHandler : ExtensionHandler {
  override val isRepeatable = true

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
  readCharacter(editor, context) { charFrom ->
    readCharacter(editor, context) { charTo ->
      getOrInputPair(charTo, editor, context) { newSurround ->
        if (newSurround != null) change(editor, context, charFrom, newSurround)
      }
    }
  }
  }

  companion object {
    fun change(editor: VimEditor, context: ExecutionContext, charFrom: Char, newSurround: SurroundPair?) {
      // Save old register values for carets
      val surroundings = editor.sortedCarets()
        .map {
          val oldValue: List<VimKeyStroke>? = getRegisterForCaret(editor, context, REGISTER, it)
          setRegisterForCaret(editor, context, REGISTER, it, null)
          SurroundingInfo(editor, context, it, null, oldValue, false)
        }

      // Delete surrounding's content
      perform("di" + pick(charFrom), editor)

      // Add info about surrounding's inner text and location
      surroundings.forEach {
        // Delete surrounding chars if necessary
        val currentSurrounding = getCurrentSurrounding(it.caret, pick(charFrom))
        if (currentSurrounding != null) {
          it.caret.moveToOffset(currentSurrounding.startOffset)
          injector.application.runWriteAction {
            editor.deleteString(currentSurrounding)
          }
        }

        val registerValue = getRegisterForCaret(editor, context, REGISTER, it.caret)
        val innerValue = if (registerValue.isNullOrEmpty()) emptyList() else registerValue
        it.innerText = innerValue

        // Valid surroundings are only those that:
        // - are validly wrapping with surround characters (i.e. parenthesis, brackets, tags, quotes, etc.);
        // - or have non-empty inner text (e.g. when we are surrounding words: `csw"`)
        if (currentSurrounding != null || innerValue.isNotEmpty()) {
          it.isValidSurrounding = true
        }
      }

      surroundings
        .filter { it.isValidSurrounding } // we do nothing with carets that are not inside the surrounding
        .map { surrounding ->
          val innerValue = injector.parser.toPrintableString(surrounding.innerText!!)
          val text = newSurround?.let {
            val trimmedValue = if (newSurround.shouldTrim) innerValue.trim() else innerValue
            it.first + trimmedValue + it.second
          } ?: innerValue
          val textData =
            PutData.TextData(null, injector.clipboardManager.dumbCopiedText(text), SelectionType.CHARACTER_WISE)
          val putData =
            PutData(textData, null, 1, insertTextBeforeCaret = true, rawIndent = true, caretAfterInsertedText = false)

          surrounding.caret to putData
        }.forEach {
          injector.put.putTextForCaret(editor, it.first, context, it.second)
        }

      surroundings.forEach {
        it.restoreRegister()
      }

      executeNormalWithoutMapping(injector.parser.parseKeys("`["), editor)
    }

    private fun perform(sequence: String, editor: VimEditor) {
      ClipboardOptionHelper.IdeaputDisabler()
        .use { executeNormalWithoutMapping(injector.parser.parseKeys("\"" + REGISTER + sequence), editor) }
    }

    private fun pick(charFrom: Char) = when (charFrom) {
      'a' -> '>'
      'r' -> ']'
      else -> charFrom
    }

    private fun getCurrentSurrounding(caret: VimCaret, char: Char): TextRange? {
      val editor = caret.editor
      val searchHelper = injector.searchHelper
      return when (char) {
        't' -> searchHelper.findBlockTagRange(editor, caret, 1, true)
        '(', ')', 'b' -> findBlockRange(editor, caret, '(', 1, true)
        '[', ']' -> findBlockRange(editor, caret, '[', 1, true)
        '{', '}', 'B' -> findBlockRange(editor, caret, '{', 1, true)
        '<', '>' -> findBlockRange(editor, caret, '<', 1, true)
        '`', '\'', '"' -> {
          val caretOffset = caret.offset
          val text = editor.text()
          if (text.getOrNull(caretOffset - 1) == char && text.getOrNull(caretOffset) == char) {
            TextRange(caretOffset - 1, caretOffset + 1)
          } else {
            searchHelper.findBlockQuoteInLineRange(editor, caret, char, true)
          }
        }

        'p' -> searchHelper.findParagraphRange(editor, caret, 1, true)
        's' -> searchHelper.findSentenceRange(editor, caret, 1, true)
        else -> null
      }
    }
  }
  }

private data class SurroundingInfo(
  val editor: VimEditor,
  val context: ExecutionContext,
  val caret: VimCaret,
  var innerText: List<VimKeyStroke>?,
  val oldRegisterContent: List<VimKeyStroke>?,
  var isValidSurrounding: Boolean,
) {
  fun restoreRegister() {
    setRegisterForCaret(editor, context, REGISTER, caret, oldRegisterContent)
  }
}

private class DSurroundHandler : ExtensionHandler {
  override val isRepeatable = true

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
  // Deleting surround is just changing the surrounding to "nothing"
  readCharacter(editor, context) { charFrom ->
    LOG.debug("DSurroundHandler: charFrom = $charFrom")
    CSurroundHandler.change(editor, context, charFrom, null)
  }
  }
}

private class Operator(private val onSurrounded: () -> Unit = {}) : OperatorFunction {
  /**
   * Answers before it knows, which is the price of a character that arrives later.
   *
   * The range is still decided here, synchronously, and is the one thing that can still say
   * "no" - so `false` continues to mean "there was nothing to operate on". A pair the user then
   * asks for that does not exist used to abort the operator too, and now quietly does nothing.
   */
  override fun apply(editor: VimEditor, context: ExecutionContext, selectionType: SelectionType?): Boolean {
    // XXX: Will it work with line-wise or block-wise selections?
    val range = getSurroundRange(editor.currentCaret()) ?: return false
    readCharacter(editor, context) { c ->
      getOrInputPair(c, editor, context) { pair ->
        if (pair != null) {
          performSurround(pair, range, editor.currentCaret(), selectionType == SelectionType.LINE_WISE)
          // Jump back to start
          executeNormalWithoutMapping(injector.parser.parseKeys("`["), editor)
          onSurrounded()
        }
      }
    }
    return true
  }

  private fun getSurroundRange(caret: VimCaret): TextRange? {
    val editor = caret.editor
    if (editor.mode is Mode.CMD_LINE) {
      editor.mode = editor.mode.returnTo
    }
    return when (editor.mode) {
      is Mode.NORMAL -> injector.markService.getChangeMarks(caret)
      is Mode.VISUAL -> caret.run { TextRange(selectionStart, selectionEnd) }
      else -> null
    }
  }
}

private val LOG: VimLogger by lazy { vimLogger<Operator>() }

private const val REGISTER = '"'

private const val OPERATOR_FUNC = "SurroundOperatorFunc"

private val tagNameAndAttributesCapturePattern = Regex("(\\S+)([^>]*)>")

private data class SurroundPair(val first: String, val second: String, val shouldTrim: Boolean)

private val SURROUND_PAIRS = mapOf(
  'b' to SurroundPair("(", ")", false),
  '(' to SurroundPair("( ", " )", false),
  ')' to SurroundPair("(", ")", true),
  'B' to SurroundPair("{", "}", false),
  '{' to SurroundPair("{ ", " }", false),
  '}' to SurroundPair("{", "}", true),
  'r' to SurroundPair("[", "]", false),
  '[' to SurroundPair("[ ", " ]", false),
  ']' to SurroundPair("[", "]", true),
  'a' to SurroundPair("<", ">", false),
  '>' to SurroundPair("<", ">", false),
  's' to SurroundPair(" ", "", false),
)

private fun getSurroundPair(c: Char): SurroundPair? = if (c in SURROUND_PAIRS) {
  SURROUND_PAIRS[c]
} else if (!c.isLetter()) {
  val s = c.toString()
  SurroundPair(s, s, false)
} else {
  null
}

/**
 * The pair to wrap with, over as many keystrokes as it takes.
 *
 * One character for `"` or `)`; `<` or `t` then a tag name up to `>`; `f` or `F` then a function
 * name up to Enter. All three arrive through a single [readKeys] session, so the decision of "have
 * I got it all yet" is [pairInputComplete] - a pure question about the keys typed so far - and the
 * building of the pair is [pairFrom]. Keeping the two apart is what lets the prompt close before
 * anything is done with the answer.
 *
 * [onPair] is given null when what the user asked for is not a pair, and is not called at all if
 * they pressed `<Esc>`.
 */
private fun getOrInputPair(
  c: Char,
  editor: VimEditor,
  context: ExecutionContext,
  onPair: (SurroundPair?) -> Unit,
) {
  if (!c.opensLongInput()) {
    onPair(getSurroundPair(c))
    return
  }
  readKeys(
    editor,
    context,
    label = if (c == 'f' || c == 'F') "function: " else "<",
    isComplete = { keys -> pairInputComplete(c, keys) },
    onInput = { keys -> onPair(pairFrom(c, keys)) },
  )
}

/** `<` and `t` want a tag name, `f` and `F` a function name. Everything else is the pair itself. */
private fun Char.opensLongInput(): Boolean = this == '<' || this == 't' || this == 'f' || this == 'F'

private fun pairInputComplete(opener: Char, keys: List<VimKeyStroke>): Boolean {
  val last = keys.lastOrNull() ?: return false
  return if (opener == 'f' || opener == 'F') {
    last.keyCode == VimKeyCodes.VK_ENTER
  } else {
    last.keyChar == '>'
  }
}

private fun pairFrom(opener: Char, keys: List<VimKeyStroke>): SurroundPair? {
  val typed = keys.mapNotNull { it.keyChar.takeIf { c -> c != VimKeyCodes.CHAR_UNDEFINED } }.joinToString("")
  return if (opener == 'f' || opener == 'F') {
    val name = typed.trimEnd('\n', '\r')
    if (name.isEmpty()) {
      null
    } else if (opener == 'F') {
      SurroundPair("$name( ", " )", false)
    } else {
      SurroundPair("$name(", ")", false)
    }
  } else {
    val match = tagNameAndAttributesCapturePattern.find(typed) ?: return null
    val tagName = match.groupValues[1]
    val tagAttributes = match.groupValues[2]
    SurroundPair("<$tagName$tagAttributes>", "</$tagName>", false)
  }
}

private fun performSurround(pair: SurroundPair, range: TextRange, caret: VimCaret, tagsOnNewLines: Boolean = false) {
  val editor = caret.editor
  val change = injector.changeGroup
  val leftSurround = pair.first + if (tagsOnNewLines) "\n" else ""

  val isEOF = range.endOffset == editor.text().length
  val hasNewLine = editor.endsWithNewLine()
  val rightSurround = if (tagsOnNewLines) {
    if (isEOF && !hasNewLine) {
      "\n" + pair.second
    } else {
      pair.second + "\n"
    }
  } else {
    pair.second
  }

  change.insertText(editor, caret, range.startOffset, leftSurround)
  change.insertText(editor, caret, range.endOffset + leftSurround.length, rightSurround)
  injector.markService.setChangeMarks(
    caret,
    TextRange(range.startOffset, range.endOffset + leftSurround.length + rightSurround.length)
  )
}
