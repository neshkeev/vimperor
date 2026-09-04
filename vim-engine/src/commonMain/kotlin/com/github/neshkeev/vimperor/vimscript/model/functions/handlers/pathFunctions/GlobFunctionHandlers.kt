/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vimscript.model.functions.handlers.pathFunctions

import com.intellij.vim.annotations.VimscriptFunction
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.directory.WorkingDirectory
import com.maddyhome.idea.vim.path.Glob
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimList
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.functions.BuiltinFunctionHandler
import com.maddyhome.idea.vim.vimscript.model.functions.VariadicFunctionHandler

/**
 * `glob()`, `globpath()` and `glob2regpat()` - finding files by shape rather than by name.
 *
 * The matching itself is [Glob], which is the same two-pointer walk `:vimgrep` already uses to pick
 * its files. Sharing it is not only economy: a config that writes `glob('*.kt')` and one that
 * writes `:vimgrep /x/ *.kt` should be looking at the same set of files, and two implementations of
 * a glob would eventually disagree about `**` or about a dot file.
 *
 * see "h glob()", "h globpath()", "h glob2regpat()"
 */
@VimscriptFunction(name = "glob")
internal class GlobFunctionHandler : BuiltinFunctionHandler<VimDataType>(minArity = 1, maxArity = 4) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimDataType {
    val pattern = arguments.getString(0).value
    val asList = (arguments.getNumberOrNull(2)?.value ?: 0) != 0
    val found = expandGlob(pattern, editor)

    // A string with newlines in it when a list was not asked for, which is Vim's older answer and
    // still the default - a config written before `{list}` existed splits it itself.
    return if (asList) {
      VimList(found.map { VimString(it) as VimDataType }.toMutableList())
    } else {
      VimString(found.joinToString("\n"))
    }
  }
}

/**
 * `globpath({path}, {expr} [, {nosuf} [, {list} [, {alllinks}]]])` - a glob under each of several
 * directories.
 *
 * What it is for is `'runtimepath'`: "find every colour scheme under any of these". The
 * directories are comma-separated because that is how Vim spells a path list everywhere.
 *
 * see "h globpath()"
 */
@VimscriptFunction(name = "globpath")
internal class GlobPathFunctionHandler : VariadicFunctionHandler<VimDataType>(minArity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimDataType {
    val directories = arguments.getString(0).value.split(',').filter { it.isNotEmpty() }
    val pattern = arguments.getString(1).value
    val asList = (arguments.getNumberOrNull(3)?.value ?: 0) != 0

    val found = directories.flatMap { directory ->
      expandGlob(directory.trimEnd('/') + "/" + pattern, editor)
    }.distinct()

    return if (asList) {
      VimList(found.map { VimString(it) as VimDataType }.toMutableList())
    } else {
      VimString(found.joinToString("\n"))
    }
  }
}

/**
 * `glob2regpat({string})` - a glob rewritten as a Vim pattern.
 *
 * The one function of the three that touches no files at all. It exists because a config sometimes
 * wants to match a *name* it already has against a glob, and Vim has no `fnmatch()` - so it turns
 * the glob into a regex and uses `=~` instead.
 *
 * see "h glob2regpat()"
 */
@VimscriptFunction(name = "glob2regpat")
internal class Glob2RegPatFunctionHandler : BuiltinFunctionHandler<VimString>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString = VimString(Glob.toPattern(arguments.getString(0).value))
}

/**
 * A pattern as the shared globber wants it: environment expanded, and relative to `:cd`'s directory.
 *
 * The globbing itself is [Glob], which is the code `:vimgrep` picks its files with. That sharing is
 * the point rather than an economy: a config that writes `glob('*.kt')` and one that writes a
 * `:vimgrep` over the same pattern should be looking at the same files, and two implementations
 * would eventually disagree about a double star or about a dot file.
 */
private fun expandGlob(pattern: String, editor: VimEditor): List<String> {
  val expanded = injector.pathExpansion.expandPath(pattern)
  val directory = WorkingDirectory.current(editor, injector.executionContextManager.getEditorExecutionContext(editor))
  val found = Glob.expand(expanded, directory)
  // A pattern with no wildcard is handed back by `Glob` whether or not the file exists, because
  // `:vimgrep` wants to complain about it. `glob()` wants the opposite: it answers with what is
  // there, and an empty string for what is not.
  return if (Glob.hasWildcard(expanded)) found else found.filter { injector.fileSystem.exists(it) }
}
