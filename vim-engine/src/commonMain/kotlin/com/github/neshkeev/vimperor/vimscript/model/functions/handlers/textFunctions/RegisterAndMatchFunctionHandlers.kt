/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vimscript.model.functions.handlers.textFunctions
import com.intellij.vim.annotations.VimscriptFunction
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.TextRange
import com.github.neshkeev.vimperor.match.Matches
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDictionary
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimList
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.datatypes.asVimInt
import com.maddyhome.idea.vim.vimscript.model.functions.BuiltinFunctionHandler

/**
 * `getreg({regname} [, {list}])` - what is in a register.
 *
 * The half of the pair that makes a mapping able to *borrow* a register: yank into it, use it, put
 * it back. Without `setreg()` that is not possible at all, and a mapping that clobbers the user's
 * `"a` is a mapping people stop using.
 *
 * see "h getreg()"
 */
@VimscriptFunction(name = "getreg")
internal class GetRegFunctionHandler : BuiltinFunctionHandler<VimDataType>(minArity = 0, maxArity = 3) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimDataType {
    val name = registerName(arguments.getStringOrNull(0)?.value)
    val asList = (arguments.getNumberOrNull(1)?.value ?: 0) != 0
    val register = injector.registerGroup.getRegister(editor, context, name)
    val text = register?.let { injector.parser.toPrintableString(it.keys) } ?: ""

    // The list form splits on newlines, which is how a config gets at a line-wise register without
    // having to know whether the last line ended with one.
    if (!asList) return VimString(text)
    val lines = text.removeSuffix("\n").split("\n").filter { text.isNotEmpty() }
    return VimList(lines.map { VimString(it) as VimDataType }.toMutableList())
  }
}

/**
 * `getregtype({regname})` - `"v"`, `"V"` or `"<C-v>{width}"`.
 *
 * The type is what makes putting a register back put it back the same way. A blockwise register
 * restored as characterwise is a mangled buffer rather than a wrong colour, which is why this and
 * `setreg()`'s third argument exist at all.
 *
 * see "h getregtype()"
 */
@VimscriptFunction(name = "getregtype")
internal class GetRegTypeFunctionHandler : BuiltinFunctionHandler<VimString>(minArity = 0, maxArity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString {
    val name = registerName(arguments.getStringOrNull(0)?.value)
    val register = injector.registerGroup.getRegister(editor, context, name) ?: return VimString("")
    return VimString(
      when (register.type) {
        SelectionType.LINE_WISE -> "V"
        SelectionType.BLOCK_WISE -> ""
        else -> "v"
      },
    )
  }
}

/**
 * `setreg({regname}, {value} [, {options}])` - the other half.
 *
 * A list value is line-wise by default, which is Vim's rule and the useful one: a config that
 * splits a register, changes a line and sets it back gets its line breaks preserved without saying
 * so. The options string can force it either way - `"V"`, `"v"` or `"b"`.
 *
 * see "h setreg()"
 */
@VimscriptFunction(name = "setreg")
internal class SetRegFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 2, maxArity = 3) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val name = registerName(arguments.getStringOrNull(0)?.value)
    val value = arguments[1]
    val options = arguments.getStringOrNull(2)?.value ?: ""

    val fromList = value is VimList
    val text = when (value) {
      is VimList -> value.values.joinToString("\n") { it.toVimString().value } + "\n"
      else -> value.toVimString().value
    }

    val type = when {
      options.startsWith("V") -> SelectionType.LINE_WISE
      options.startsWith("b") || options.startsWith("") -> SelectionType.BLOCK_WISE
      options.startsWith("v") -> SelectionType.CHARACTER_WISE
      // No option given: a list is line-wise and a string is whatever its last character says,
      // which is how Vim tells `setreg('a', "one\n")` from `setreg('a', "one")`.
      fromList || text.endsWith("\n") -> SelectionType.LINE_WISE
      else -> SelectionType.CHARACTER_WISE
    }

    injector.registerGroup.storeText(editor, context, name, text)
    val stored = injector.registerGroup.getRegister(editor, context, name)
    if (stored != null && stored.type != type) {
      injector.registerGroup.saveRegister(editor, context, name, stored.copy(type = type))
    }
    return 0.asVimInt()
  }
}

/** An empty or missing name is the unnamed register, which is what Vim reads `""` as. */
private fun registerName(name: String?): Char = name?.firstOrNull() ?: '"'

/**
 * The `match*()` functions that *add* a highlight, which are `:match` reached from Vimscript.
 *
 * Vim keeps one table for both and reserves ids 1, 2 and 3 for `:match`, `:2match` and `:3match`;
 * so does this fork, which is why `getmatches()` lists a `:match` alongside everything a plugin
 * added and `clearmatches()` takes them all off together. Two tables would have been easier to
 * write and would have made those two functions lie.
 *
 * see "h matchadd()"
 */
@VimscriptFunction(name = "matchadd")
internal class MatchAddFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 2, maxArity = 5) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = Matches.add(
    editor,
    group = arguments.getString(0).value,
    pattern = arguments.getString(1).value,
    priority = arguments.getNumberOrNull(2)?.value ?: Matches.DEFAULT_PRIORITY,
    wantedId = arguments.getNumberOrNull(3)?.value ?: -1,
  ).asVimInt()
}

/**
 * `matchaddpos({group}, {pos} [, {priority} [, {id} [, {dict}]]])` - the same, by position.
 *
 * It exists for speed, and the speed is real here too: a match made from positions is already a
 * list of ranges, so the repaint after every keystroke has nothing to search for. A plugin that
 * already knows which words it wants lit should not make the engine find them again.
 *
 * Each position is a line, or a line and a column, or a line, a column and a length - Vim's three
 * shapes, and a bare number for "the whole of that line".
 *
 * see "h matchaddpos()"
 */
@VimscriptFunction(name = "matchaddpos")
internal class MatchAddPosFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 2, maxArity = 5) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val list = arguments[1] as? VimList ?: return (-1).asVimInt()
    val ranges = list.values.mapNotNull { rangeOf(it, editor) }
    return Matches.add(
      editor,
      group = arguments.getString(0).value,
      pattern = null,
      positions = ranges,
      priority = arguments.getNumberOrNull(2)?.value ?: Matches.DEFAULT_PRIORITY,
      wantedId = arguments.getNumberOrNull(3)?.value ?: -1,
    ).asVimInt()
  }

  private fun rangeOf(position: VimDataType, editor: VimEditor): TextRange? {
    val parts = when (position) {
      is VimList -> position.values.map { it.toVimNumber().value }
      else -> listOf(position.toVimNumber().value)
    }
    val line = (parts.getOrNull(0) ?: return null) - 1
    if (line < 0 || line >= editor.lineCount()) return null

    val lineStart = editor.getLineStartOffset(line)
    val lineEnd = editor.getLineEndOffset(line)
    // A bare line number lights the whole line, which is what Vim does with the one-element form.
    if (parts.size == 1) return TextRange(lineStart, lineEnd)

    val column = (parts[1] - 1).coerceAtLeast(0)
    val length = parts.getOrNull(2) ?: 1
    val start = (lineStart + column).coerceAtMost(lineEnd)
    return TextRange(start, (start + length).coerceAtMost(lineEnd))
  }
}

/**
 * `matchdelete({id} [, {win}])` - 0, or -1 for an id nothing is using.
 *
 * see "h matchdelete()"
 */
@VimscriptFunction(name = "matchdelete")
internal class MatchDeleteFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 1, maxArity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = if (Matches.delete(editor, arguments.getNumber(0).value)) 0.asVimInt() else (-1).asVimInt()
}

/**
 * `getmatches([{win}])` - everything showing, in the shape `setmatches()` takes back.
 *
 * The pair is how a plugin gets out of the way and puts things back: save, clear, do something
 * noisy, restore. Which is why the dictionary's keys have to be exactly Vim's - a config passes
 * this straight back without looking inside it.
 *
 * see "h getmatches()"
 */
@VimscriptFunction(name = "getmatches")
internal class GetMatchesFunctionHandler : BuiltinFunctionHandler<VimList>(minArity = 0, maxArity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimList = VimList(
    Matches.all(editor).map { match ->
      val entry = LinkedHashMap<VimString, VimDataType>()
      entry[VimString("group")] = VimString(match.group)
      match.pattern?.let { entry[VimString("pattern")] = VimString(it) }
      entry[VimString("priority")] = match.priority.asVimInt()
      entry[VimString("id")] = match.id.asVimInt()
      VimDictionary(entry) as VimDataType
    }.toMutableList(),
  )
}

/**
 * `setmatches({list} [, {win}])` - put back what `getmatches()` handed over.
 *
 * Replaces rather than adds, because that is what restoring means: a plugin that saved three
 * matches and restores them should end with three, not with six.
 *
 * see "h setmatches()"
 */
@VimscriptFunction(name = "setmatches")
internal class SetMatchesFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 1, maxArity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val list = arguments[0] as? VimList ?: return (-1).asVimInt()
    Matches.clearAll(editor)
    for (element in list.values) {
      val entry = element as? VimDictionary ?: return (-1).asVimInt()
      val group = entry["group"]?.toVimString()?.value ?: continue
      val pattern = entry["pattern"]?.toVimString()?.value ?: continue
      Matches.add(
        editor,
        group = group,
        pattern = pattern,
        priority = entry["priority"]?.toVimNumber()?.value ?: Matches.DEFAULT_PRIORITY,
        wantedId = entry["id"]?.toVimNumber()?.value ?: -1,
      )
    }
    return 0.asVimInt()
  }
}

/**
 * `clearmatches([{win}])` - every standing highlight in this window, `:match` included.
 *
 * see "h clearmatches()"
 */
@VimscriptFunction(name = "clearmatches")
internal class ClearMatchesFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 0, maxArity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    Matches.clearAll(editor)
    return 0.asVimInt()
  }
}

/**
 * `matcharg({nr})` - what `:match`, `:2match` or `:3match` is showing, as `[group, pattern]`.
 *
 * An empty list for a number outside 1 to 3, and two empty strings for a channel with nothing on
 * it, which is Vim's way of telling "you asked about nothing" from "nothing is there".
 *
 * see "h matcharg()"
 */
@VimscriptFunction(name = "matcharg")
internal class MatchArgFunctionHandler : BuiltinFunctionHandler<VimList>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimList {
    val channel = arguments.getNumber(0).value
    if (channel !in 1..Matches.RESERVED_IDS) return VimList(mutableListOf())
    val match = Matches.current(editor, channel)
    return VimList(
      mutableListOf(
        VimString(match?.group ?: ""),
        VimString(match?.pattern ?: ""),
      ),
    )
  }
}
