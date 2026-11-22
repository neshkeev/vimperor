/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.lion

import com.github.neshkeev.vimperor.extension.readKeys
import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimMotionGroupBase
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtensionFacade
import com.maddyhome.idea.vim.extension.VimExtensionFacade.executeNormalWithoutMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.extension.exportOperatorFunction
import com.maddyhome.idea.vim.group.visual.VimSelection
import com.maddyhome.idea.vim.group.visual.VisualOperation
import com.maddyhome.idea.vim.helper.inRepeatMode
import com.maddyhome.idea.vim.helper.noneOfEnum
import com.maddyhome.idea.vim.key.MappingOwner
import com.maddyhome.idea.vim.key.OperatorFunction
import com.maddyhome.idea.vim.key.VimKeyCodes
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.regexp.VimRegexException
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.state.mode.inVisualMode
import com.maddyhome.idea.vim.vimscript.model.VimPluginContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.expressions.Scope
import com.maddyhome.idea.vim.vimscript.model.expressions.VariableExpression

/**
 * Port of [vim-lion](https://github.com/tommcdo/vim-lion): `gl` and `gL` align text to a character.
 *
 * `[count]gl{motion}{char}` adds spaces before each `{char}` and `[count]gL{motion}{char}` adds
 * spaces after it, so that the characters (or the text after them) line up. `{char}` can be
 * preceded by a number to skip occurrences, `/` prompts for a pattern and a space aligns words.
 *
 * The character used to be a blocking `inputKeyStroke`, which a runtime with one thread cannot
 * answer. It is one [readKeys] session instead, and the `/` pattern stays in that same session:
 * opening a second prompt for it would lose the first character of the pattern. `.` replays the
 * keys the session recorded.
 *
 * A tab's width is `'tabstop'`. IdeaVim reads the editor's tab size, which is the same number on
 * this host: `'tabstop'` is what the editor draws a tab as.
 *
 * `g:lion_create_maps`, `g:lion_map_right` and `g:lion_map_left` are read when the extension is
 * enabled. `g:lion_prompt` and `b:lion_squeeze_spaces` / `g:lion_squeeze_spaces` are read when an
 * alignment runs. The buffer value wins even when it is off.
 */
@VimPlugin(name = LION)
public fun VimInitApi.init(): Unit = registerLion()

/** Public because `:set` and `:Plug` name it. */
public const val LION: String = "lion"

public fun registerLion() {
  val owner = MappingOwner.Plugin.get(LION)
  for (direction in LionDirection.entries) {
    putExtensionHandlerMapping(
      MappingMode.N,
      injector.parser.parseKeys(direction.plug),
      owner,
      LionOperatorHandler(direction),
      false,
    )
    putExtensionHandlerMapping(
      MappingMode.X,
      injector.parser.parseKeys(direction.visualPlug),
      owner,
      LionVisualHandler(direction),
      false,
    )
    VimExtensionFacade.exportOperatorFunction(direction.operatorFunction, LionOperatorFunction(direction))
  }

  if (globalVariable(CREATE_MAPS_VARIABLE)?.toVimNumber()?.booleanValue == false) return
  for (direction in LionDirection.entries) {
    val keys = globalVariable(direction.mapVariable)?.toVimString()?.value ?: direction.defaultKeys
    if (keys.isEmpty()) continue
    putKeyMappingIfMissing(
      MappingMode.N,
      injector.parser.parseKeys(keys),
      owner,
      injector.parser.parseKeys(direction.plug),
      true,
    )
    putKeyMappingIfMissing(
      MappingMode.X,
      injector.parser.parseKeys(keys),
      owner,
      injector.parser.parseKeys(direction.visualPlug),
      true,
    )
  }
}

/** Starts `g@`, which calls [LionOperatorFunction] with the range of the following motion. */
private class LionOperatorHandler(private val direction: LionDirection) : ExtensionHandler {
  override val isRepeatable = true

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    LionCount.update(editor, operatorArguments)
    injector.globalOptions().operatorfunc = direction.operatorFunction
    // The count belongs to the alignment, it must not reach the motion of g@
    KeyHandler.getInstance().keyHandlerState.commandBuilder.resetCount()
    executeNormalWithoutMapping(injector.parser.parseKeys("g@"), editor)
  }
}

private class LionVisualHandler(private val direction: LionDirection) : ExtensionHandler {
  override val isRepeatable = true

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    LionCount.update(editor, operatorArguments)
    KeyHandler.getInstance().keyHandlerState.commandBuilder.resetCount()

    val caret = editor.primaryCaret()
    val inVisualMode = editor.inVisualMode
    if (inVisualMode) {
      // Lets a dot-repeat apply the alignment to a selection of the same size at the caret
      caret.vimLastVisualOperatorRange = VisualOperation.getRange(editor, caret, noneOfEnum())
    } else if (!editor.inRepeatMode) {
      return
    }

    val toLineEnd = if (inVisualMode) {
      caret.vimLastColumn >= VimMotionGroupBase.LAST_COLUMN
    } else {
      (caret.vimLastVisualOperatorRange?.columns ?: 0) >= VimMotionGroupBase.LAST_COLUMN
    }
    val selection = editor.collectSelections()?.get(caret) ?: return
    val range = selection.toLionRange(editor, toLineEnd)

    if (inVisualMode) {
      executeNormalWithoutMapping(injector.parser.parseKeys("<Esc>"), editor)
    }
    // Saved after leaving Visual, before the character arrives. The callback restores this column
    // rather than wherever an insertion in front of the caret left it.
    val restore = caret.getBufferPosition()
    align(editor, context, direction, range, restore)
  }

  private fun VimSelection.toLionRange(editor: VimEditor, toLineEnd: Boolean): LionRange {
    val start = editor.offsetToBufferPosition(minOf(vimStart, vimEnd))
    val end = editor.offsetToBufferPosition(maxOf(vimStart, vimEnd))
    return when (type) {
      SelectionType.LINE_WISE -> LionRange.lineWise(start.line, end.line)
      SelectionType.CHARACTER_WISE -> LionRange.characterWise(start, end)
      SelectionType.BLOCK_WISE -> {
        val startColumn = minOf(start.column, end.column)
        val endColumn = if (toLineEnd) null else maxOf(start.column, end.column)
        LionRange.blockWise(start.line, end.line, startColumn, endColumn)
      }
    }
  }
}

private class LionOperatorFunction(private val direction: LionDirection) : OperatorFunction {
  override fun apply(editor: VimEditor, context: ExecutionContext, selectionType: SelectionType?): Boolean {
    val caret = editor.primaryCaret()
    val marks = injector.markService.getChangeMarks(caret) ?: return false
    val start = editor.offsetToBufferPosition(marks.startOffset)
    val end = editor.offsetToBufferPosition((marks.endOffset - 1).coerceAtLeast(marks.startOffset))
    val range = when (selectionType ?: SelectionType.CHARACTER_WISE) {
      SelectionType.LINE_WISE -> LionRange.lineWise(start.line, end.line)
      SelectionType.CHARACTER_WISE -> LionRange.characterWise(start, end)
      SelectionType.BLOCK_WISE -> LionRange.blockWise(
        start.line,
        end.line,
        minOf(start.column, end.column),
        maxOf(start.column, end.column),
      )
    }
    // An operator leaves the caret at the start of the text it worked on. The character that says
    // what to align to arrives later, so the column is remembered now and put back after the edit.
    caret.moveToOffset(marks.startOffset)
    align(editor, context, direction, range, caret.getBufferPosition())
    return true
  }
}

/**
 * `gl` is [RIGHT]: spaces go before the alignment character. `gL` is [LEFT]: spaces go after it, before the text that
 * follows.
 */
private enum class LionDirection(
  val plug: String,
  val visualPlug: String,
  val operatorFunction: String,
  val mapVariable: String,
  val defaultKeys: String,
) {
  RIGHT("<Plug>LionRight", "<Plug>VLionRight", "LionAlignRight", "lion_map_right", "gl"),
  LEFT("<Plug>LionLeft", "<Plug>VLionLeft", "LionAlignLeft", "lion_map_left", "gL"),
}

/**
 * The count of the last alignment.
 *
 * A dot-repeat calls the handler again with the count given to `.`, so the original count is kept unless `.` has one.
 */
private object LionCount {
  var value = 0
    private set

  fun update(editor: VimEditor, operatorArguments: OperatorArguments) {
    if (!editor.inRepeatMode || operatorArguments.count0 > 0) {
      value = operatorArguments.count0
    }
  }
}

/**
 * What to align to, read from the user after the motion: `{char}`, optionally preceded by the number of the first
 * occurrence to align.
 */
private class LionInput(val pattern: String, val isRegex: Boolean, val skip: Int) {
  fun toPattern(direction: LionDirection): LionPattern {
    val matchEnd = direction == LionDirection.LEFT
    return if (isRegex) RegexLionPattern(pattern, matchEnd) else LiteralLionPattern(pattern, matchEnd)
  }
}

private fun align(
  editor: VimEditor,
  context: ExecutionContext,
  direction: LionDirection,
  range: LionRange,
  restore: BufferPosition,
) {
  readLionInput(editor, context) { input ->
    if (input == null) return@readLionInput
    val pattern = try {
      input.toPattern(direction)
    } catch (_: VimRegexException) {
      injector.messages.indicateError()
      return@readLionInput
    }

    val caret = editor.primaryCaret()
    if (isSqueezeSpacesEnabled(editor, context)) {
      for (line in range.lines) {
        val text = editor.getLineText(line)
        val squeezed = LionAligner.squeezeSpaces(text)
        if (squeezed != text) {
          val lineStart = editor.getLineStartOffset(line)
          injector.changeGroup.replaceText(editor, caret, lineStart, lineStart + text.length, squeezed)
        }
      }
    }

    LionAligner(editor, range, pattern, direction == LionDirection.LEFT, tabSize(editor))
      .align(LionCount.value, input.skip)

    val lineLength = editor.getLineText(restore.line).length
    caret.moveToBufferPosition(
      BufferPosition(restore.line, restore.column.coerceAtMost((lineLength - 1).coerceAtLeast(0))),
    )
  }
}

/**
 * `{char}`, a run of digits and then `{char}`, or `/` and a pattern finished with Enter.
 *
 * One session on purpose. The pattern used to be a second prompt opened once `/` had been read,
 * and a prompt opened from the callback of the one that just closed loses the next key.
 */
private fun readLionInput(
  editor: VimEditor,
  context: ExecutionContext,
  onInput: (LionInput?) -> Unit,
) {
  readKeys(
    editor,
    context,
    isComplete = ::lionInputComplete,
    onProgress = { keys ->
      val slash = patternSlash(keys) ?: return@readKeys
      val prompt = globalVariable(PROMPT_VARIABLE)?.toVimString()?.value ?: DEFAULT_PROMPT
      injector.messages.showStatusBarMessage(editor, prompt + patternText(keys.drop(slash + 1)))
    },
    onCancel = { injector.messages.clearStatusBarMessage() },
    onInput = { keys ->
      injector.messages.clearStatusBarMessage()
      onInput(lionInputFrom(keys))
    },
  )
}

/** True once the keys are a finished alignment specifier. A key with no character cancels. */
private fun lionInputComplete(keys: List<VimKeyStroke>): Boolean {
  if (keys.isEmpty()) return false
  var index = 0
  if (lionDigit(keys[0], first = true)) {
    while (index < keys.size && lionDigit(keys[index], first = index == 0)) index++
    if (index >= keys.size) return false
    if (keys[index].isEnter()) return true
  }
  if (index >= keys.size) return false
  if (keys[index].keyChar == '/') return keys.drop(index + 1).any { it.isEnter() }
  return true
}

/** The alignment specifier those keys name, or null when the key was not a character. */
private fun lionInputFrom(keys: List<VimKeyStroke>): LionInput? {
  if (keys.isEmpty()) return null
  var index = 0
  var skip = 0
  if (lionDigit(keys[0], first = true)) {
    val digits = StringBuilder()
    while (index < keys.size && lionDigit(keys[index], first = index == 0)) {
      digits.append(keys[index].keyChar)
      index++
    }
    if (index >= keys.size) return null
    // A number on its own is the text to align to
    if (keys[index].isEnter()) return LionInput(digits.toString(), isRegex = false, skip = 0)
    skip = (digits.toString().toIntOrNull() ?: return null) - 1
  }
  if (index >= keys.size) return null
  val key = keys[index]
  if (key.keyChar == VimKeyCodes.CHAR_UNDEFINED) return null
  return when (val char = key.keyChar) {
    '/' -> {
      val text = patternText(keys.drop(index + 1).dropLastWhile { it.isEnter() })
      if (text.isEmpty()) LionInput("/", isRegex = false, skip) else LionInput(text, isRegex = true, skip)
    }
    // Aligns the first space after each word
    ' ' -> LionInput("\\S\\zs\\s", isRegex = true, skip)
    else -> LionInput(char.toString(), isRegex = false, skip)
  }
}

/** Where the `/` that starts a pattern sits, once the leading digits have been passed. */
private fun patternSlash(keys: List<VimKeyStroke>): Int? {
  var index = 0
  if (keys.isNotEmpty() && lionDigit(keys[0], first = true)) {
    while (index < keys.size && lionDigit(keys[index], first = index == 0)) index++
  }
  return index.takeIf { it < keys.size && keys[it].keyChar == '/' }
}

/** The typed keys as text, with backspace taking a character off rather than adding one. */
private fun patternText(keys: List<VimKeyStroke>): String {
  val text = StringBuilder()
  for (key in keys) {
    when {
      key.keyCode == VimKeyCodes.VK_BACK_SPACE -> if (text.isNotEmpty()) text.setLength(text.length - 1)
      key.keyChar != VimKeyCodes.CHAR_UNDEFINED -> text.append(key.keyChar)
    }
  }
  return text.toString()
}

/** The first digit is `1`-`9`. A leading `0` is the character to align to, not a count. */
private fun lionDigit(key: VimKeyStroke, first: Boolean): Boolean {
  val char = key.keyChar
  return if (first) char in '1'..'9' else char in '0'..'9'
}

private fun VimKeyStroke.isEnter(): Boolean =
  keyCode == VimKeyCodes.VK_ENTER || keyChar == '\n' || keyChar == '\r'

/** How many columns a tab draws as. `'tabstop'`, or Vim's 8 where the option is not registered. */
private fun tabSize(editor: VimEditor): Int =
  injector.optionGroup.getOption("tabstop")
    ?.let { (injector.optionGroup.getOptionValue(it, OptionAccessScope.EFFECTIVE(editor)) as? VimInt)?.value }
    ?.takeIf { it > 0 }
    ?: 8

/** The buffer variable wins over the global one, even when it is off. */
private fun isSqueezeSpacesEnabled(editor: VimEditor, context: ExecutionContext): Boolean {
  val value = injector.variableService.getNullableVariableValue(
    VariableExpression(Scope.BUFFER_VARIABLE, SQUEEZE_SPACES_VARIABLE),
    editor,
    context,
    VimPluginContext,
  ) ?: globalVariable(SQUEEZE_SPACES_VARIABLE)
  return value?.toVimNumber()?.booleanValue ?: false
}

private fun globalVariable(name: String): VimDataType? = injector.variableService.getGlobalVariableValue(name)

private const val CREATE_MAPS_VARIABLE = "lion_create_maps"
private const val SQUEEZE_SPACES_VARIABLE = "lion_squeeze_spaces"
private const val PROMPT_VARIABLE = "lion_prompt"
private const val DEFAULT_PROMPT = "Pattern [/]: "
