/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vimscript.model.functions.handlers.cursorFunctions
import com.intellij.vim.annotations.VimscriptFunction
import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.MutableVimEditor
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.lineLength
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimList
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.datatypes.asVimInt
import com.maddyhome.idea.vim.vimscript.model.functions.BuiltinFunctionHandler
import com.maddyhome.idea.vim.vimscript.model.functions.handlers.cursorFunctions.variableToPosition

/**
 * Vim's position family - the functions that read and write where the caret is.
 *
 * They come in a pair of pairs. `getpos()`/`setpos()` speak in *lists*, which is what makes them
 * the ones a plugin uses: save the cursor, move around, put it back, without ever writing down what
 * a position is made of. `line()`/`col()` and `cursor()` speak in numbers, which is what makes them
 * the ones a person writes by hand.
 *
 * The list is Vim's four-element shape - buffer, line, column, offset - and it has to be exact,
 * because the whole point is that `setpos('.', getpos('.'))` is a no-op in code that never looks
 * inside it. The buffer number is 0 for "wherever this is", which is what this fork can honestly
 * say: it has no buffer numbers of its own, and 0 is the value Vim uses for the current one.
 *
 * `variableToPosition` is shared with `line()` and `col()` - see the file next to this one - so
 * `getpos("'a")` and `line("'a")` cannot disagree about what a mark is.
 */
@VimscriptFunction(name = "getpos")
internal class GetPosFunctionHandler : BuiltinFunctionHandler<VimList>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimList {
    val position = variableToPosition(editor, arguments[0], true)
      ?: return VimList(mutableListOf(VimInt.ZERO, VimInt.ZERO, VimInt.ZERO, VimInt.ZERO))
    return VimList(mutableListOf(VimInt.ZERO, position.first, position.second, VimInt.ZERO))
  }
}

/**
 * `getcurpos()` - `getpos('.')`, plus the column the caret is *trying* to be in.
 *
 * That fifth element is the whole reason the function exists. Moving down a line from column 40
 * into a line of ten characters puts the caret at column 10 and remembers 40, so that the next
 * move down goes back out - and a plugin that restores a position without it leaves the caret
 * looking right and behaving wrong on the very next keystroke.
 *
 * see "h getcurpos()"
 */
@VimscriptFunction(name = "getcurpos")
internal class GetCurPosFunctionHandler : BuiltinFunctionHandler<VimList>(minArity = 0, maxArity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimList {
    val caret = editor.currentCaret()
    val at = caret.getBufferPosition()
    val wanted = caret.vimLastColumn
    return VimList(
      mutableListOf(
        VimInt.ZERO,
        (at.line + 1).asVimInt(),
        (at.column + 1).asVimInt(),
        VimInt.ZERO,
        (wanted + 1).asVimInt(),
      ),
    )
  }
}

/**
 * `setpos({expr}, {list})` - the other half of `getpos()`.
 *
 * `.` moves the caret and `'x` sets a mark, which are the two things a plugin restores. Vim also
 * accepts the visual marks and the buffer's last-insert mark; those are marks here too, so they
 * arrive by the same route.
 *
 * -1 for a list that is not a position, which is Vim's answer and matters more than it looks: a
 * plugin restoring a position it saved before the buffer shrank should be told, not moved somewhere
 * arbitrary.
 *
 * see "h setpos()"
 */
@VimscriptFunction(name = "setpos")
internal class SetPosFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val name = arguments.getString(0).value
    val list = arguments[1] as? VimList ?: return (-1).asVimInt()
    if (list.values.size < 3) return (-1).asVimInt()

    val line = (list.values[1].toVimNumber().value - 1).coerceAtLeast(0)
    if (line >= editor.lineCount()) return (-1).asVimInt()
    val column = (list.values[2].toVimNumber().value - 1).coerceIn(0, editor.lineLength(line))
    val offset = editor.bufferPositionToOffset(BufferPosition(line, column))

    return when {
      name == "." -> {
        editor.currentCaret().moveToOffset(offset)
        0.asVimInt()
      }

      name.length >= 2 && name[0] == '\'' -> {
        if (injector.markService.setMark(editor.primaryCaret(), name[1], offset)) 0.asVimInt() else (-1).asVimInt()
      }

      else -> (-1).asVimInt()
    }
  }
}

/**
 * `cursor({lnum}, {col} [, {off}])` or `cursor({list})` - move the caret, by number.
 *
 * The same job as `setpos('.', ...)` and the reason both exist is that this one is written by
 * hand: `cursor(1, 1)` is a line somebody types, and `setpos('.', saved)` is a line a plugin
 * generates. A line or column of zero means "leave that one alone", which is Vim's rule and is what
 * makes `cursor(0, 5)` a column move.
 *
 * see "h cursor()"
 */
@VimscriptFunction(name = "cursor")
internal class CursorFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 1, maxArity = 3) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val here = editor.currentCaret().getBufferPosition()
    val first = arguments[0]

    val (wantedLine, wantedColumn) = if (first is VimList) {
      if (first.values.size < 2) return (-1).asVimInt()
      first.values[0].toVimNumber().value to first.values[1].toVimNumber().value
    } else {
      if (arguments.size < 2) return (-1).asVimInt()
      first.toVimNumber().value to arguments.getNumber(1).value
    }

    val line = (if (wantedLine == 0) here.line + 1 else wantedLine) - 1
    val column = (if (wantedColumn == 0) here.column + 1 else wantedColumn) - 1
    if (line < 0 || line >= editor.lineCount()) return (-1).asVimInt()

    editor.currentCaret().moveToBufferPosition(
      BufferPosition(line, column.coerceIn(0, editor.lineLength(line))),
    )
    return 0.asVimInt()
  }
}

/**
 * `virtcol({expr})` - the *screen* column, which is not the character column when there are tabs.
 *
 * A line of three tabs is three characters and twenty-four columns, and the difference is the whole
 * reason this function exists: a status line or an alignment plugin is asking about the screen.
 *
 * see "h virtcol()"
 */
@VimscriptFunction(name = "virtcol")
internal class VirtColFunctionHandler : BuiltinFunctionHandler<VimDataType>(minArity = 1, maxArity = 3) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimDataType {
    val position = variableToPosition(editor, arguments[0], false) ?: return VimInt.ZERO
    val line = position.first.value - 1
    if (line < 0 || line >= editor.lineCount()) return VimInt.ZERO

    val text = editor.getLineText(line)
    val upTo = (position.second.value - 1).coerceIn(0, text.length)
    val column = screenColumn(text.take(upTo), tabStop(editor))

    // The list form asks for the start and end of the character under the caret, which differ for a
    // tab and for a double-width glyph - the two cases where one character is more than one column.
    val asList = (arguments.getNumberOrNull(1)?.value ?: 0) != 0
    if (!asList) return (column + 1).asVimInt()
    val width = if (upTo < text.length) screenColumn(text.take(upTo + 1), tabStop(editor)) - column else 1
    return VimList(mutableListOf((column + 1).asVimInt(), (column + width).asVimInt()))
  }

  private fun tabStop(editor: VimEditor): Int = injector.optionGroup.getOption("tabstop")
    ?.let { (injector.optionGroup.getOptionValue(it, OptionAccessScope.EFFECTIVE(editor)) as? VimInt)?.value }
    ?.takeIf { it > 0 } ?: 8

  /** How many columns [text] takes, with a tab advancing to the next stop rather than by one. */
  private fun screenColumn(text: String, tabStop: Int): Int {
    var column = 0
    for (character in text) {
      column = if (character == '\t') column + tabStop - (column % tabStop) else column + 1
    }
    return column
  }
}

/**
 * `charcol({expr})` - `col()`, counted in characters rather than bytes.
 *
 * The pair with `col()` for the same reason `strchars()` pairs with `strlen()`: a config that walks
 * a line of Cyrillic with `col()` walks it two bytes at a time and lands between characters.
 *
 * see "h charcol()"
 */
@VimscriptFunction(name = "charcol")
internal class CharColFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 1, maxArity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val position = variableToPosition(editor, arguments[0], false) ?: return VimInt.ZERO
    val line = position.first.value - 1
    if (line < 0 || line >= editor.lineCount()) return VimInt.ZERO
    val text = editor.getLineText(line)
    val upTo = (position.second.value - 1).coerceIn(0, text.length)
    return (text.take(upTo).count { !it.isLowSurrogate() } + 1).asVimInt()
  }
}

/**
 * `indent({lnum})` - the indent of a line in screen columns, with tabs expanded.
 *
 * In columns rather than characters, which is what makes it usable at all: a file indented with
 * tabs and one indented with spaces should compare equal, and they do only if a tab counts for
 * `'tabstop'`.
 *
 * see "h indent()"
 */
@VimscriptFunction(name = "indent")
internal class IndentFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val line = (lineNumberOf(editor, arguments[0]) ?: return (-1).asVimInt()) - 1
    if (line < 0 || line >= editor.lineCount()) return (-1).asVimInt()

    val tabStop = injector.optionGroup.getOption("tabstop")
      ?.let { (injector.optionGroup.getOptionValue(it, OptionAccessScope.EFFECTIVE(editor)) as? VimInt)?.value }
      ?.takeIf { it > 0 } ?: 8

    var column = 0
    for (character in editor.getLineText(line)) {
      when (character) {
        ' ' -> column++
        '\t' -> column += tabStop - (column % tabStop)
        else -> return column.asVimInt()
      }
    }
    return column.asVimInt()
  }
}

/**
 * `byteidx({expr}, {nr})` and `charidx({string}, {idx})` - the two directions of the same question.
 *
 * They exist because Vim's string functions count bytes and its cursor functions count characters,
 * so a config that has one and needs the other has to convert. -1 past the end, which is how the
 * caller finds out rather than getting a plausible wrong answer.
 *
 * see "h byteidx()", "h charidx()"
 */
@VimscriptFunction(name = "byteidx")
internal class ByteIdxFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 2, maxArity = 3) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = byteIndexOfCharacter(arguments.getString(0).value, arguments.getNumber(1).value).asVimInt()
}

/** `byteidxcomp()` - the same, with combining characters counted separately. See "h byteidxcomp()". */
@VimscriptFunction(name = "byteidxcomp")
internal class ByteIdxCompFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 2, maxArity = 3) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = byteIndexOfCharacter(arguments.getString(0).value, arguments.getNumber(1).value).asVimInt()
}

/** `charidx({string}, {idx} [, {countcc} [, {utf16}]])` - the other direction. */
@VimscriptFunction(name = "charidx")
internal class CharIdxFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 2, maxArity = 4) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val text = arguments.getString(0).value
    val wanted = arguments.getNumber(1).value
    if (wanted < 0) return (-1).asVimInt()

    var bytes = 0
    var characters = 0
    var index = 0
    while (index < text.length) {
      if (bytes >= wanted) return characters.asVimInt()
      val width = if (text[index].isHighSurrogate() && index + 1 < text.length) 2 else 1
      bytes += utf8Width(text, index, width)
      index += width
      characters++
    }
    return if (bytes >= wanted) characters.asVimInt() else (-1).asVimInt()
  }
}

private fun byteIndexOfCharacter(text: String, wanted: Int): Int {
  if (wanted < 0) return -1
  var bytes = 0
  var characters = 0
  var index = 0
  while (index < text.length) {
    if (characters >= wanted) return bytes
    val width = if (text[index].isHighSurrogate() && index + 1 < text.length) 2 else 1
    bytes += utf8Width(text, index, width)
    index += width
    characters++
  }
  return if (characters >= wanted) bytes else -1
}

private fun utf8Width(text: String, index: Int, width: Int): Int {
  if (width == 2) return 4
  val code = text[index].code
  return when {
    code < 0x80 -> 1
    code < 0x800 -> 2
    else -> 3
  }
}

/**
 * `setline({lnum}, {text})` - replace a line, or several from a list.
 *
 * Writing to the buffer without going through a register, a motion or a mode, which is what makes
 * it the function a formatter or a code-generator reaches for. 1 on failure and 0 on success, which
 * is Vim's convention here and the opposite way round from most of the language.
 *
 * see "h setline()"
 */
@VimscriptFunction(name = "setline")
internal class SetLineFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val first = (lineNumberOf(editor, arguments[0]) ?: return 1.asVimInt()) - 1
    if (first < 0) return 1.asVimInt()

    val value = arguments[1]
    val lines = when (value) {
      is VimList -> value.values.map { it.toVimString().value }
      else -> listOf(value.toVimString().value)
    }

    for ((offsetFromFirst, text) in lines.withIndex()) {
      val line = first + offsetFromFirst
      // Vim stops at the end of the buffer rather than growing it - `append()` is the function that
      // grows it, and keeping the two apart is what stops a wrong line number adding text.
      if (line >= editor.lineCount()) return 1.asVimInt()
      (editor as MutableVimEditor).replaceString(editor.getLineStartOffset(line), editor.getLineEndOffset(line), text)
    }
    return 0.asVimInt()
  }
}

/**
 * `append({lnum}, {text})` - put lines *after* a line, growing the buffer.
 *
 * Line zero is "before the first line", which is Vim's rule for an address everywhere it takes one
 * and is how a config prepends to a file.
 *
 * see "h append()"
 */
@VimscriptFunction(name = "append")
internal class AppendFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val after = arguments.getNumber(0).value
    if (after < 0 || after > editor.lineCount()) return 1.asVimInt()

    val value = arguments[1]
    val lines = when (value) {
      is VimList -> value.values.map { it.toVimString().value }
      else -> listOf(value.toVimString().value)
    }
    if (lines.isEmpty()) return 0.asVimInt()

    val offset = when {
      after == 0 -> 0
      after >= editor.lineCount() -> editor.fileSize().toInt()
      else -> editor.getLineStartOffset(after)
    }
    val block = lines.joinToString("\n", postfix = "\n")
    // At the very end of a file whose last line has no newline, the text needs one in front of it
    // or it joins that line instead of following it.
    val needsLeading = offset == editor.fileSize().toInt() && editor.fileSize() > 0 &&
      editor.text().lastOrNull() != '\n'
    (editor as MutableVimEditor).insertText(editor.currentCaret(), offset, if (needsLeading) "\n$block" else block)
    return 0.asVimInt()
  }
}

/**
 * `getbufline({buf}, {lnum} [, {end}])` - lines out of a buffer.
 *
 * Only the buffer on screen: this fork has no buffer numbers of its own, so a name or a number that
 * is not this editor's answers with an empty list, which is what Vim does for a buffer that is not
 * loaded. `getline()` is the function for the common case and this one is here so that a config
 * written with it does not stop.
 *
 * see "h getbufline()"
 */
@VimscriptFunction(name = "getbufline")
internal class GetBufLineFunctionHandler : BuiltinFunctionHandler<VimList>(minArity = 2, maxArity = 3) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimList {
    val buffer = arguments[0].toVimString().value
    val isThisOne = buffer == "%" || buffer == "" || buffer == "0" ||
      editor.getPath()?.endsWith(buffer) == true
    if (!isThisOne) return VimList(mutableListOf())

    val first = (lineNumberOf(editor, arguments[1]) ?: 0) - 1
    val last = arguments.getOrNull(2)?.let { (lineNumberOf(editor, it) ?: 0) - 1 } ?: first
    if (first < 0 || first >= editor.lineCount()) return VimList(mutableListOf())

    val lines = (first..last.coerceIn(first, editor.lineCount() - 1)).map {
      VimString(editor.getLineText(it)) as VimDataType
    }
    return VimList(lines.toMutableList())
  }
}

/**
 * A line number argument, which is a number or one of Vim's line expressions.
 *
 * `setline(2, ...)`, `setline('.', ...)` and `setline('$', ...)` are all legal, and the first is by
 * far the most common - so a plain number is taken at its word and anything else goes to
 * `variableToPosition`, which is where `line()` and `col()` already answer the same question.
 */
private fun lineNumberOf(editor: VimEditor, value: VimDataType): Int? {
  if (value is VimInt) return value.value
  value.toVimString().value.toIntOrNull()?.let { return it }
  return variableToPosition(editor, value, true)?.first?.value
}
