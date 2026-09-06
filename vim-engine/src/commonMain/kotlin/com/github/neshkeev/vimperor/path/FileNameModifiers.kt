/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.path
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.github.neshkeev.vimperor.directory.WorkingDirectory
import com.maddyhome.idea.vim.helper.enumSetOf
import com.maddyhome.idea.vim.regexp.VimRegex
import com.maddyhome.idea.vim.regexp.VimRegexOptions

/**
 * Vim's filename modifiers - the `:p:h:t:r` that turn a path into the part of it you wanted.
 *
 * `expand('%:p:h')` is how every configuration in the world asks for the directory of the current
 * file, and `fnamemodify()` is the same machinery pointed at a string instead. They are one piece
 * of code because they are one feature; Vim documents it once, under `filename-modifiers`, and both
 * functions send you there.
 *
 * Applied left to right and repeatable, which matters: `:h:h` is the grandparent directory, and a
 * reader who assumes each modifier is a flag rather than a step gets that wrong.
 *
 * Relative to *this* fork's current directory, which is the one `:cd` owns rather than the host's
 * project root - see [WorkingDirectory]. That is what makes `:.` and `:~` mean here what they mean
 * in Vim.
 */
object FileNameModifiers {

  /**
   * Applies [modifiers] to [path]. A modifier this does not know ends the run rather than failing,
   * which is Vim's behaviour and is what lets `expand('%:p')` stop cleanly at the end of the string.
   */
  fun apply(path: String, modifiers: String, editor: VimEditor?): String {
    var result = path
    var index = 0
    while (index < modifiers.length) {
      if (modifiers[index] != ':') break
      index++
      if (index >= modifiers.length) break

      when (val modifier = modifiers[index]) {
        // The full path, and the one modifier that has to consult the current directory.
        'p' -> {
          result = absolute(result, editor)
          index++
        }

        // Relative to the home directory, as `~/...`, when it is under one.
        '~' -> {
          val home = homeDirectory()
          val full = absolute(result, editor)
          result = if (home != null && full.startsWith(home)) "~" + full.removePrefix(home) else result
          index++
        }

        // Relative to the current directory, when it is under it. Vim leaves it alone otherwise,
        // rather than building a path full of `..`.
        '.' -> {
          val directory = currentDirectory(editor)
          val full = absolute(result, editor)
          result = if (directory != null && full.startsWith(directory.withTrailingSlash())) {
            full.removePrefix(directory.withTrailingSlash())
          } else {
            result
          }
          index++
        }

        // The head: everything but the last component. Vim keeps the separator off the end and
        // answers `.` for a bare name, because "the directory of foo.txt" is somewhere.
        'h' -> {
          result = head(result)
          index++
        }

        't' -> {
          result = result.substringAfterLast('/')
          index++
        }

        // The root: the name without its extension. Only the *last* one, so `a.tar.gz:r` is
        // `a.tar` - which is why `:r:r` is a thing people write.
        'r' -> {
          val tail = result.substringAfterLast('/')
          val dot = tail.lastIndexOf('.')
          if (dot > 0) result = result.dropLast(tail.length - dot)
          index++
        }

        'e' -> {
          val tail = result.substringAfterLast('/')
          val dot = tail.lastIndexOf('.')
          result = if (dot > 0) tail.substring(dot + 1) else ""
          index++
        }

        // `:S` - escaped for a shell, which is the modifier that makes `:!cmd %:S` safe.
        'S' -> {
          result = shellEscape(result)
          index++
        }

        // `:s?pat?sub?` and `:gs?pat?sub?`. Any character can delimit, as everywhere else in Vim.
        'g', 's' -> {
          val global = modifier == 'g'
          val start = if (global) index + 1 else index
          if (start >= modifiers.length || modifiers[start] != 's') return result
          val consumed = substitute(result, modifiers, start + 1, global) ?: return result
          result = consumed.first
          index = consumed.second
        }

        else -> return result
      }
    }
    return result
  }

  /** The directory a path is in, as Vim's `:h` gives it. */
  fun head(path: String): String = when {
    '/' !in path -> "."
    path.count { it == '/' } == 1 && path.startsWith("/") -> "/"
    else -> path.substringBeforeLast('/')
  }

  /** True for a path that names a place rather than a place relative to somewhere. */
  fun isAbsolute(path: String): Boolean = WorkingDirectory.isAbsolute(path)

  /**
   * `simplify()` - the path with `.` and redundant `..` taken out, without touching the disk.
   *
   * A leading `..` stays, because "the parent of wherever this is" cannot be resolved without
   * knowing where that is, and this function is documented not to look.
   */
  fun simplify(path: String): String {
    if (path.isEmpty()) return path
    val absolute = path.startsWith("/")
    val trailing = path.endsWith("/") && path != "/"

    val parts = mutableListOf<String>()
    for (piece in path.split('/')) {
      when {
        piece.isEmpty() || piece == "." -> {}
        piece == ".." && parts.isNotEmpty() && parts.last() != ".." -> parts.removeAt(parts.size - 1)
        piece == ".." && absolute -> {} // `/..` is `/`
        else -> parts.add(piece)
      }
    }

    val body = parts.joinToString("/")
    return when {
      absolute -> "/" + body + if (trailing && body.isNotEmpty()) "/" else ""
      body.isEmpty() -> "."
      else -> body + if (trailing) "/" else ""
    }
  }

  /**
   * `shellescape()` and the `:S` modifier - a string a shell will take literally.
   *
   * Single quotes, with an embedded quote spelled the only way a POSIX shell allows: close the
   * quoting, escape one quote, open it again. There is no escape *inside* single quotes, which is
   * the whole reason this looks the way it does.
   */
  fun shellEscape(text: String): String = "'" + text.replace("'", "'\\''") + "'"

  /**
   * `fnameescape()` - a filename safe to hand back to an ex command.
   *
   * A different set from [shellEscape] and for a different reader: the characters that mean
   * something to Vim's own command line, not to a shell. A `%` in a filename would otherwise become
   * the current file, and a space would end the argument.
   */
  fun commandEscape(text: String): String = buildString {
    for (character in text) {
      if (character in " \t\n*?[{`$\\%#'\"|!<") append('\\')
      append(character)
    }
  }

  private fun absolute(path: String, editor: VimEditor?): String {
    val expanded = injector.pathExpansion.expandPath(path)
    if (isAbsolute(expanded)) return simplify(expanded)
    val directory = currentDirectory(editor) ?: return simplify(expanded)
    return simplify(directory.withTrailingSlash() + expanded)
  }

  /**
   * The directory `:.` and `:~` are relative to: the one `:cd` owns, or the host's when none is set.
   *
   * The same answer `:pwd` gives, which is the point - a config that prints `expand('%:.')` and one
   * that prints `:pwd` should be talking about the same place.
   */
  private fun currentDirectory(editor: VimEditor?): String? {
    if (editor == null) return null
    val context = injector.executionContextManager.getEditorExecutionContext(editor)
    return WorkingDirectory.current(editor, context)
  }

  private fun homeDirectory(): String? = injector.pathExpansion.expandPath("~")
    .takeIf { it != "~" && it.isNotEmpty() }

  private fun String.withTrailingSlash(): String = if (endsWith("/")) this else "$this/"

  /**
   * `:s?pat?sub?`, returning the new path and where the modifier ended.
   *
   * Null when the modifier is malformed, which ends the run rather than throwing - the caller is
   * `expand()` on a string a user typed, and half a modifier is much more likely to be text that
   * happens to contain a colon.
   */
  private fun substitute(path: String, modifiers: String, start: Int, global: Boolean): Pair<String, Int>? {
    if (start >= modifiers.length) return null
    val delimiter = modifiers[start]
    val middle = modifiers.indexOf(delimiter, start + 1)
    if (middle < 0) return null
    val end = modifiers.indexOf(delimiter, middle + 1)
    if (end < 0) return null

    val pattern = modifiers.substring(start + 1, middle)
    val replacement = modifiers.substring(middle + 1, end)
    val regex = try {
      VimRegex(pattern)
    } catch (e: Throwable) {
      return null
    }

    val matches = regex.findAll(text = path, options = enumSetOf<VimRegexOptions>())
    if (matches.isEmpty()) return path to (end + 1)
    val wanted = if (global) matches else matches.take(1)

    val result = StringBuilder()
    var consumed = 0
    for (match in wanted) {
      if (match.range.startOffset < consumed) continue
      result.append(path, consumed, match.range.startOffset)
      result.append(regex.replacementFor(match, replacement, ""))
      consumed = match.range.endOffset
    }
    result.append(path, consumed, path.length)
    return result.toString() to (end + 1)
  }
}
