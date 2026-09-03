/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.functions.handlers.pathFunctions

import com.intellij.vim.annotations.VimscriptFunction
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getText
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.directory.WorkingDirectory
import com.maddyhome.idea.vim.path.FileNameModifiers
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimList
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.datatypes.asVimInt
import com.maddyhome.idea.vim.vimscript.model.functions.BuiltinFunctionHandler

/**
 * `expand({string} [, {nosuf} [, {list}]])` - the function a `~/.vimrc` cannot be written without.
 *
 * `expand('%:p:h')` is how every configuration in the world asks for the directory of the file you
 * are looking at, and until now it was `E117: Unknown function` in both hosts.
 *
 * Three things happen, in Vim's order. A special item - `%` for this file, `#` for the alternate,
 * `<cword>` for the word under the caret - is replaced by what it stands for. Then the environment
 * and `~` are expanded. Then the `:p:h:t:r` modifiers are applied, by [FileNameModifiers], which is
 * the same code `fnamemodify()` uses because it is the same feature.
 *
 * What is not here is the parts of Vim's `expand()` that belong to a Vim: `<sfile>` and `<slnum>`
 * need a script stack that reports where a line came from, and `<afile>`, `<abuf>` and `<amatch>`
 * are only defined inside an autocommand. They come back as themselves rather than as an empty
 * string, so a config that prints one gets something it can recognise.
 *
 * see "h expand()"
 */
@VimscriptFunction(name = "expand")
internal class ExpandFunctionHandler : BuiltinFunctionHandler<VimDataType>(minArity = 1, maxArity = 3) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimDataType {
    val text = arguments.getString(0).value
    val asList = (arguments.getNumberOrNull(2)?.value ?: 0) != 0

    val (subject, modifiers) = split(text)
    val replaced = specialItem(subject, editor, context) ?: injector.pathExpansion.expandPath(subject)
    val result = FileNameModifiers.apply(replaced, modifiers, editor)

    // The list form exists for the wildcard cases, where one pattern can name several files. This
    // one never expands a wildcard, so the list has one element - which is still the right shape.
    return if (asList) VimList(mutableListOf(VimString(result))) else VimString(result)
  }

  /**
   * Splits `%:p:h` into its subject and its modifiers.
   *
   * The colon has to be found without cutting a Windows drive letter or a `<cword>` in half, so the
   * search starts after any `<...>` item and skips a colon that has a letter on both sides of it.
   */
  private fun split(text: String): Pair<String, String> {
    val afterAngle = if (text.startsWith("<")) text.indexOf('>') + 1 else 0
    var index = afterAngle.coerceAtLeast(0)
    while (index < text.length) {
      if (text[index] == ':' && !(index == 1 && text.length > 2 && text[0].isLetter())) {
        return text.substring(0, index) to text.substring(index)
      }
      index++
    }
    return text to ""
  }

  /** Vim's `%`, `#`, `<cfile>` and friends, or null when [subject] is an ordinary path. */
  private fun specialItem(subject: String, editor: VimEditor, context: ExecutionContext): String? = when (subject) {
    "%" -> editor.getPath() ?: ""
    "#" -> alternateFile(context) ?: ""
    "<cword>" -> wordUnderCaret(editor, big = false)
    "<cWORD>" -> wordUnderCaret(editor, big = true)
    // `<cfile>` is the filename under the caret, which is the same span as a WORD in every case
    // this fork can tell apart - there is no `'isfname'` here to widen or narrow it.
    "<cfile>" -> wordUnderCaret(editor, big = true)
    else -> null
  }

  private fun alternateFile(context: ExecutionContext): String? =
    injector.file.getBuffers(context).firstOrNull { it.isAlternate }?.displayPath

  private fun wordUnderCaret(editor: VimEditor, big: Boolean): String {
    val range = injector.searchHelper.findWordAtOrFollowingCursor(editor, editor.currentCaret().offset, big)
      ?: return ""
    return editor.getText(range)
  }
}

/**
 * `fnamemodify({fname}, {mods})` - the modifiers, applied to a string you already have.
 *
 * see "h fnamemodify()"
 */
@VimscriptFunction(name = "fnamemodify")
internal class FnameModifyFunctionHandler : BuiltinFunctionHandler<VimString>(arity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString = VimString(
    FileNameModifiers.apply(arguments.getString(0).value, arguments.getString(1).value, editor),
  )
}

/**
 * `fnameescape({string})` - a filename safe to put back into an ex command.
 *
 * The pair with `shellescape()` and the reason both exist: they escape for different readers.
 * `execute('edit ' . fnameescape(f))` is the idiom, and without it a file with a space in its name
 * becomes two arguments and one with a `%` in it becomes the file you were already editing.
 *
 * see "h fnameescape()"
 */
@VimscriptFunction(name = "fnameescape")
internal class FnameEscapeFunctionHandler : BuiltinFunctionHandler<VimString>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString = VimString(FileNameModifiers.commandEscape(arguments.getString(0).value))
}

/**
 * `shellescape({string} [, {special}])` - a string a shell will take literally.
 *
 * see "h shellescape()"
 */
@VimscriptFunction(name = "shellescape")
internal class ShellEscapeFunctionHandler : BuiltinFunctionHandler<VimString>(minArity = 1, maxArity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString = VimString(FileNameModifiers.shellEscape(arguments.getString(0).value))
}

/**
 * `simplify({filename})` - the path with `.` and `..` taken out, without touching the disk.
 *
 * The difference from `resolve()` is exactly that: this one is arithmetic on a string and that one
 * asks the filesystem. `simplify('a/../b')` is `b` whether or not `a` exists.
 *
 * see "h simplify()"
 */
@VimscriptFunction(name = "simplify")
internal class SimplifyFunctionHandler : BuiltinFunctionHandler<VimString>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString = VimString(FileNameModifiers.simplify(arguments.getString(0).value))
}

/**
 * `resolve({filename})` - the path with symbolic links followed.
 *
 * Neither host's file service can read a link, so this simplifies and stops, which is what Vim's
 * own documentation says happens on a system without them: "on systems where this is not possible,
 * the name is returned unchanged". A path with no links in it is its own resolution, which is the
 * usual case; a path with one is answered less usefully rather than wrongly.
 *
 * see "h resolve()"
 */
@VimscriptFunction(name = "resolve")
internal class ResolveFunctionHandler : BuiltinFunctionHandler<VimString>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString = VimString(FileNameModifiers.simplify(arguments.getString(0).value))
}

/**
 * `pathshorten({path} [, {len}])` - every directory cut to its first character.
 *
 * What it is for is a status line: `/home/user/projects/vim/src/main.c` becomes `/h/u/p/v/s/main.c`
 * and still tells you where you are. The last component is left whole, which is the point.
 *
 * see "h pathshorten()"
 */
@VimscriptFunction(name = "pathshorten")
internal class PathShortenFunctionHandler : BuiltinFunctionHandler<VimString>(minArity = 1, maxArity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString {
    val length = (arguments.getNumberOrNull(1)?.value ?: 1).coerceAtLeast(1)
    val parts = arguments.getString(0).value.split('/')
    if (parts.size <= 1) return VimString(parts.joinToString("/"))
    return VimString(
      parts.mapIndexed { index, piece ->
        // A leading empty piece is the root slash and a hidden file keeps its dot, because `.git`
        // shortened to `.` would name the current directory instead.
        when {
          index == parts.size - 1 -> piece
          piece.startsWith(".") -> piece.take(length + 1)
          else -> piece.take(length)
        }
      }.joinToString("/"),
    )
  }
}

/**
 * `isabsolutepath({path})` - whether the path names a place or a place relative to somewhere.
 *
 * see "h isabsolutepath()"
 */
@VimscriptFunction(name = "isabsolutepath")
internal class IsAbsolutePathFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = FileNameModifiers.isAbsolute(arguments.getString(0).value).asVimInt()
}

/**
 * `getcwd([{winnr} [, {tabnr}]])` - the current directory, which is the one `:cd` owns.
 *
 * The same answer `:pwd` gives, and the reason both exist is that one is for a person and one is
 * for a script. Vim's window and tab arguments name a window's local directory; `:lcd` here is
 * per-editor, so a window number cannot be resolved and the arguments are accepted and ignored.
 *
 * see "h getcwd()"
 */
@VimscriptFunction(name = "getcwd")
internal class GetCwdFunctionHandler : BuiltinFunctionHandler<VimString>(minArity = 0, maxArity = 2) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString = VimString(WorkingDirectory.current(editor, context) ?: "")
}

/**
 * `filereadable({file})` - whether the file is there.
 *
 * A directory is not readable, which is Vim's answer and is what makes the pair with
 * `isdirectory()` useful: a config asks one to find a file and the other to find a place.
 *
 * see "h filereadable()"
 */
@VimscriptFunction(name = "filereadable")
internal class FileReadableFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val path = resolvePath(arguments.getString(0).value, editor)
    return (injector.fileSystem.exists(path) && !injector.fileSystem.isDirectory(path)).asVimInt()
  }
}

/**
 * `filewritable({file})` - 1 for a writable file, 2 for a writable directory, 0 for neither.
 *
 * Neither host's file service reports permissions, so an existing file is taken to be writable.
 * The 1-or-2 distinction is the part configs actually use - it is how `filewritable()` is told
 * apart from `filereadable()` at all - and that part is exact.
 *
 * see "h filewritable()"
 */
@VimscriptFunction(name = "filewritable")
internal class FileWritableFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val path = resolvePath(arguments.getString(0).value, editor)
    return when {
      injector.fileSystem.isDirectory(path) -> 2.asVimInt()
      injector.fileSystem.exists(path) -> 1.asVimInt()
      else -> 0.asVimInt()
    }
  }
}

/**
 * `isdirectory({directory})` - whether the name is a directory.
 *
 * see "h isdirectory()"
 */
@VimscriptFunction(name = "isdirectory")
internal class IsDirectoryFunctionHandler : BuiltinFunctionHandler<VimInt>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt = injector.fileSystem.isDirectory(resolvePath(arguments.getString(0).value, editor)).asVimInt()
}

/**
 * `getftype({fname})` - `"file"`, `"dir"` or `""`.
 *
 * Vim also answers `"link"`, `"bdev"`, `"cdev"`, `"socket"` and `"fifo"`. Nothing here can see any
 * of those: a file service that reads and writes text has no way to ask what kind of node a name
 * is, and the two that matter to a configuration are the two that are answered.
 *
 * see "h getftype()"
 */
@VimscriptFunction(name = "getftype")
internal class GetFTypeFunctionHandler : BuiltinFunctionHandler<VimString>(arity = 1) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString {
    val path = resolvePath(arguments.getString(0).value, editor)
    return VimString(
      when {
        injector.fileSystem.isDirectory(path) -> "dir"
        injector.fileSystem.exists(path) -> "file"
        else -> ""
      },
    )
  }
}

/**
 * `readfile({fname} [, {type} [, {max}]])` - the file as a list of lines.
 *
 * -1 for [max] means the *last* that many lines, which is how a config reads the end of a log. An
 * unreadable file is an empty list rather than an error, which is Vim's behaviour and is what lets
 * `readfile(f)` be written without a `filereadable(f)` in front of it.
 *
 * see "h readfile()"
 */
@VimscriptFunction(name = "readfile")
internal class ReadFileFunctionHandler : BuiltinFunctionHandler<VimList>(minArity = 1, maxArity = 3) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimList {
    val path = resolvePath(arguments.getString(0).value, editor)
    val text = try {
      injector.fileSystem.readText(path)
    } catch (e: Throwable) {
      return VimList(mutableListOf())
    }

    // A trailing newline ends the last line rather than starting an empty one, which is what makes
    // `len(readfile(f))` the number of lines people expect.
    var lines = text.split("\n")
    if (lines.isNotEmpty() && lines.last().isEmpty()) lines = lines.dropLast(1)

    val max = arguments.getNumberOrNull(2)?.value
    val wanted = when {
      max == null || max == 0 -> lines
      max > 0 -> lines.take(max)
      else -> lines.takeLast(-max)
    }
    return VimList(wanted.map { VimString(it.removeSuffix("\r")) as VimDataType }.toMutableList())
  }
}

/**
 * `writefile({object}, {fname} [, {flags}])` - a list of lines, written out.
 *
 * `a` in the flags appends, which needs the old content read back first: the file service replaces
 * rather than appends, and reading once is the only way to say "and then this".
 *
 * Zero on success and -1 on failure, which is Vim's convention and the opposite way round from
 * everything else here.
 *
 * see "h writefile()"
 */
@VimscriptFunction(name = "writefile")
internal class WriteFileFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 2, maxArity = 3) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val list = arguments[0] as? VimList ?: return (-1).asVimInt()
    val path = resolvePath(arguments.getString(1).value, editor)
    val flags = arguments.getStringOrNull(2)?.value ?: ""

    val body = list.values.joinToString("\n") { it.toVimString().value }
    // `b` asks for no final newline; without it every line ends with one, the last included.
    val text = if ('b' in flags) body else body + "\n"
    val existing = if ('a' in flags) {
      try {
        injector.fileSystem.readText(path)
      } catch (e: Throwable) {
        ""
      }
    } else {
      ""
    }

    return if (injector.fileSystem.writeText(path, existing + text) == null) 0.asVimInt() else (-1).asVimInt()
  }
}

/**
 * A path as the filesystem should see it: expanded, and relative to the directory `:cd` owns.
 *
 * Every function in this file that touches a file goes through here, so that `filereadable('x')`
 * after a `:cd` asks about the same file `:edit x` would open.
 */
private fun resolvePath(path: String, editor: VimEditor): String =
  WorkingDirectory.resolve(injector.pathExpansion.expandPath(path), editor)
