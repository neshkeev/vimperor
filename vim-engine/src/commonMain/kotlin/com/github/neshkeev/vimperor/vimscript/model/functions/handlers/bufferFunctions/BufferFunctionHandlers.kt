/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vimscript.model.functions.handlers.bufferFunctions

import com.intellij.vim.annotations.VimscriptFunction
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimBuffer
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDictionary
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimList
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.datatypes.asVimInt
import com.maddyhome.idea.vim.vimscript.model.functions.BuiltinFunctionHandler

/**
 * The buffer functions, over the one list this fork has.
 *
 * Vim keeps a buffer for every file it has ever opened, numbered once and for the life of the
 * session. Neither host here has that: IntelliJ has files open in a project and VS Code has tabs in
 * a window, and both lists *shrink* when something is closed. So a buffer number here is a position
 * in the open list, and it moves.
 *
 * That is worth stating rather than hiding, because it is the one way a config written for Vim can
 * be surprised: `bufnr('%')` saved before a tab closes may name a different file afterwards. What
 * it is not is a reason to leave the functions out. `bufname('%')` and `bufnr('%')` are the two
 * that appear in real configuration, both are about *this* buffer, and neither can go stale.
 *
 * The same decision `:ls`, `:buffer` and the argument-list commands already made - `:ls` numbers
 * what the host has open - and it is made here in the same way so the numbers agree.
 */
@VimscriptFunction(name = "bufname")
internal class BufNameFunctionHandler : BuiltinFunctionHandler<VimString>(minArity = 0, maxArity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString {
    val wanted = arguments.getOrNull(0)
    if (wanted == null || isCurrent(wanted)) return VimString(editor.getPath() ?: "")
    return VimString(bufferFor(wanted, editor, context)?.displayPath ?: "")
  }
}

/**
 * `bufnr([{buf} [, {create}]])` - the number of a buffer, or -1.
 *
 * `{create}` asks Vim to make a buffer that does not exist yet. There is nothing to make here - a
 * buffer is a file the host has open - so it is accepted and changes nothing, and the answer stays
 * -1 rather than becoming a number nothing is behind.
 *
 * see "h bufnr()"
 */
@VimscriptFunction(name = "bufnr")
internal class BufNrFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 0, maxArity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val buffers = injector.file.getBuffers(context)
    val wanted = arguments.getOrNull(0)
    if (wanted == null || isCurrent(wanted)) {
      val index = buffers.indexOfFirst { it.isCurrent }
      return if (index < 0) (-1).asVimInt() else (index + 1).asVimInt()
    }
    val index = indexOf(wanted, buffers)
    return if (index < 0) (-1).asVimInt() else (index + 1).asVimInt()
  }
}

/**
 * `bufexists({buf})` - whether the host has it open.
 *
 * In Vim this is "does a buffer exist", which includes files that are listed but not loaded. Here
 * there is one list and it holds what is open, so this and `buflisted()` and `bufloaded()` are the
 * same question. They are all three registered rather than one of them, because a config that
 * guards on `bufloaded()` should not stop at `E117`.
 *
 * see "h bufexists()"
 */
@VimscriptFunction(name = "bufexists")
internal class BufExistsFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = exists(arguments[0], editor, context)
}

/** see "h buflisted()" - the same question here. See [BufExistsFunctionHandler]. */
@VimscriptFunction(name = "buflisted")
internal class BufListedFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = exists(arguments[0], editor, context)
}

/** see "h bufloaded()" - the same question here. See [BufExistsFunctionHandler]. */
@VimscriptFunction(name = "bufloaded")
internal class BufLoadedFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = exists(arguments[0], editor, context)
}

/**
 * `getbufinfo([{buf}])` - the buffer list as dictionaries.
 *
 * The keys are the ones a config actually reads - `bufnr`, `name`, `changed`, `listed`, `loaded`
 * and `lnum`. Vim's full dictionary carries window ids, signs and variables, none of which this
 * fork has to put in it; a missing key is a config that checks for it, and an invented one is a
 * config that trusts it.
 *
 * see "h getbufinfo()"
 */
@VimscriptFunction(name = "getbufinfo")
internal class GetBufInfoFunctionHandler : BuiltinFunctionHandler<VimList>(minArity = 0, maxArity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimList {
    val buffers = injector.file.getBuffers(context)
    val wanted = arguments.getOrNull(0)
    val chosen = if (wanted == null || wanted is VimDictionary) {
      buffers.withIndex().toList()
    } else {
      val index = if (isCurrent(wanted)) buffers.indexOfFirst { it.isCurrent } else indexOf(wanted, buffers)
      if (index < 0) emptyList() else listOf(IndexedValue(index, buffers[index]))
    }

    return VimList(
      chosen.map { (index, buffer) ->
        val entry = LinkedHashMap<VimString, VimDataType>()
        entry[VimString("bufnr")] = (index + 1).asVimInt()
        entry[VimString("name")] = VimString(buffer.displayPath)
        entry[VimString("changed")] = (if (buffer.isModified) 1 else 0).asVimInt()
        entry[VimString("listed")] = 1.asVimInt()
        entry[VimString("loaded")] = 1.asVimInt()
        entry[VimString("lnum")] = buffer.line.asVimInt()
        VimDictionary(entry) as VimDataType
      }.toMutableList(),
    )
  }
}

/**
 * `winnr()`, `winbufnr()`, `tabpagenr()` and the window sizes.
 *
 * Every one of these is about a *window*, and neither host lets an extension enumerate its splits.
 * What they can all answer is "the one you are in", which is 1, and that is what a config almost
 * always asks for: `winnr()` in a status line, `tabpagenr()` in a tab line. The functions exist so
 * that such a line renders rather than failing, and the number is honest for the case that matters.
 *
 * `winnr('$')` - how many windows there are - is answered as 1 for the same reason, and it is the
 * one that can be wrong on screen. A config that loops over windows will visit one.
 *
 * see "h winnr()", "h tabpagenr()"
 */
@VimscriptFunction(name = "winnr")
internal class WinNrFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 0, maxArity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = 1.asVimInt()
}

/** see "h winbufnr()" - the buffer in a window, which here is the one on screen. */
@VimscriptFunction(name = "winbufnr")
internal class WinBufNrFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 0, maxArity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val index = injector.file.getBuffers(context).indexOfFirst { it.isCurrent }
    return if (index < 0) (-1).asVimInt() else (index + 1).asVimInt()
  }
}

/** see "h tabpagenr()" */
@VimscriptFunction(name = "tabpagenr")
internal class TabPageNrFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 0, maxArity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = 1.asVimInt()
}

/**
 * `winheight()` and `winwidth()` - the size of the window, in lines and columns.
 *
 * Answered from the editor rather than invented: the height is how many lines are on screen, which
 * the scrolling machinery already has to know, and the width is `'columns'`. A status line that
 * pads itself to the width gets a number that means something.
 *
 * see "h winheight()", "h winwidth()"
 */
@VimscriptFunction(name = "winheight")
internal class WinHeightFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 0, maxArity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val top = injector.engineEditorHelper.getVisualLineAtTopOfScreen(editor)
    val bottom = injector.engineEditorHelper.getVisualLineAtBottomOfScreen(editor)
    return (bottom - top + 1).coerceAtLeast(1).asVimInt()
  }
}

/** see "h winwidth()" */
@VimscriptFunction(name = "winwidth")
internal class WinWidthFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 0, maxArity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = injector.engineEditorHelper.getApproximateScreenWidth(editor).asVimInt()
}

/** Vim's `%`, `""` and `0`, which all mean "the one on screen". */
private fun isCurrent(value: VimDataType): Boolean {
  val text = value.toVimString().value
  return text == "%" || text.isEmpty() || (value is VimInt && value.value == 0)
}

private fun exists(value: VimDataType, editor: VimEditor, context: ExecutionContext): VimInt {
  if (isCurrent(value)) return 1.asVimInt()
  return (indexOf(value, injector.file.getBuffers(context)) >= 0).let { if (it) 1 else 0 }.asVimInt()
}

private fun bufferFor(value: VimDataType, editor: VimEditor, context: ExecutionContext): VimBuffer? {
  val buffers = injector.file.getBuffers(context)
  val index = indexOf(value, buffers)
  return if (index < 0) null else buffers[index]
}

/**
 * Vim's buffer argument: a number, or a name matched on any part of it.
 *
 * The name rule is `:buffer`'s, deliberately - a config that says `bufnr('main')` and one that says
 * `:buffer main` should find the same file, and both of them mean "the one whose name contains
 * this".
 */
private fun indexOf(value: VimDataType, buffers: List<VimBuffer>): Int {
  if (value is VimInt) return if (value.value in 1..buffers.size) value.value - 1 else -1
  val text = value.toVimString().value
  text.toIntOrNull()?.let { return if (it in 1..buffers.size) it - 1 else -1 }
  if (text == "#") return buffers.indexOfFirst { it.isAlternate }
  return buffers.indexOfFirst { it.name.contains(text) || it.displayPath.contains(text) }
}
