/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.helper.enumSetOf
import com.maddyhome.idea.vim.quickfix.QuickfixEntry
import com.maddyhome.idea.vim.regexp.VimRegex
import com.maddyhome.idea.vim.regexp.VimRegexException
import com.maddyhome.idea.vim.regexp.VimRegexOptions
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * `:vimgrep` and its three relatives - Vim's own search across files, into the quickfix list.
 *
 * The other half of the quickfix family. `:grep` runs `'grepprg'` and parses whatever it printed;
 * this searches the files itself, with Vim's regular expressions, and therefore takes the same
 * pattern the `/` prompt takes - which is the whole reason Vim has both. A pattern with `\<`, `\zs`
 * or a `\%(` group in it works here and does not work in `grep`.
 *
 * The files are read through [com.maddyhome.idea.vim.api.VimFileSystem] rather than asked of the
 * host, so an unsaved buffer is *not* searched - the file on disk is. That is Vim's behaviour too
 * for a file that is not loaded, and it is the honest one here: neither host will hand over the
 * text of a file it has not opened.
 *
 * A double star descends and a single star does not, which is Vim's wildcard rule and the only
 * interesting part of the globbing. See [expandGlob].
 */
sealed class VimGrepCommandBase(
  range: Range,
  modifier: CommandModifier,
  argument: String,
  private val isLocation: Boolean,
  private val adds: Boolean,
) : Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_REQUIRED, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val parsed = parseArguments(commandArgument.trim()) ?: throw exExceptionMessage("E471")
    if (parsed.files.isEmpty()) throw exExceptionMessage("E471")

    val regex = try {
      VimRegex(parsed.pattern)
    } catch (e: VimRegexException) {
      injector.messages.showErrorMessage(editor, e.message)
      return ExecutionResult.Error
    }

    val options = enumSetOf<VimRegexOptions>()
    if (injector.globalOptions().smartcase) options.add(VimRegexOptions.SMART_CASE)
    if (injector.globalOptions().ignorecase) options.add(VimRegexOptions.IGNORE_CASE)

    val files = parsed.files.flatMap { expandGlob(it, injector.file.getWorkingDirectory(context)) }
    val entries = mutableListOf<QuickfixEntry>()
    for (path in files) {
      val text = try {
        injector.fileSystem.readText(path)
      } catch (e: Throwable) {
        continue
      }
      entries += matchesIn(regex, path, text, options, parsed.everyMatch)
    }

    // Vim's `E480: No match`, which is a different thing from an empty list: `:vimgrep` that found
    // nothing leaves the list it did not fill alone.
    if (entries.isEmpty()) throw exExceptionMessage("E480")

    val list = com.maddyhome.idea.vim.quickfix.Quickfix.listFor(editor, isLocation)
    if (adds) list.add(entries) else list.replaceWith(entries)

    if (parsed.dontJump) return ExecutionResult.Success
    return jumpToFirst(editor, context, list.at(list.index))
  }

  /**
   * Every match in one file, as quickfix entries.
   *
   * Without `g` Vim records the first match on each line and moves to the next line, which is what
   * keeps a common word from filling the list with one entry per occurrence. With `g` it records
   * every one.
   */
  private fun matchesIn(
    regex: VimRegex,
    path: String,
    text: String,
    options: MutableSet<VimRegexOptions>,
    everyMatch: Boolean,
  ): List<QuickfixEntry> {
    val normalised = text.replace("\r\n", "\n")
    val lines = normalised.split("\n")

    val entries = mutableListOf<QuickfixEntry>()
    var lineStart = 0
    for ((number, line) in lines.withIndex()) {
      val found = regex.findAll(line, 0, line.length, options)
      for (match in found) {
        entries += QuickfixEntry(
          path = path,
          line = number + 1,
          column = match.range.startOffset + 1,
          text = line.trim(),
        )
        if (!everyMatch) break
      }
      lineStart += line.length + 1
    }
    return entries
  }

  private fun jumpToFirst(
    editor: VimEditor,
    context: ExecutionContext,
    entry: QuickfixEntry?,
  ): ExecutionResult {
    if (entry?.path == null) return ExecutionResult.Success
    val failure = injector.file.openFile(entry.path!!, context)
    if (failure != null) {
      injector.messages.showErrorMessage(editor, failure)
      return ExecutionResult.Error
    }
    val target = injector.editorGroup.getFocusedEditor() ?: editor
    val line = (entry.line - 1).coerceIn(0, (target.lineCount() - 1).coerceAtLeast(0))
    target.currentCaret().moveToBufferPosition(
      com.maddyhome.idea.vim.api.BufferPosition(line, (entry.column - 1).coerceAtLeast(0)),
    )
    injector.scroll.scrollCaretIntoView(target)
    return ExecutionResult.Success
  }

  /** What `:vimgrep` was given: a pattern, its flags, and the files to look in. */
  internal data class Arguments(
    val pattern: String,
    val everyMatch: Boolean,
    val dontJump: Boolean,
    val files: List<String>,
  )

  internal companion object {

    /**
     * `/{pattern}/[g][j] {file} ...`, or Vim's bare form.
     *
     * The delimiter is whatever non-identifier character the argument starts with, so `#foo#` and
     * `/foo/` are the same command with different quoting - which matters because a pattern
     * containing a slash is exactly when you reach for the other one. Without a delimiter Vim reads
     * the pattern up to the first space and takes no flags, which is the form people actually type.
     */
    fun parseArguments(argument: String): Arguments? {
      if (argument.isEmpty()) return null

      val delimiter = argument.first()
      if (delimiter.isLetterOrDigit() || delimiter == '\\' || delimiter == '"' || delimiter == '|') {
        val cut = argument.indexOf(' ')
        if (cut < 0) return null
        return Arguments(argument.substring(0, cut), false, false, splitFiles(argument.substring(cut + 1)))
      }

      // An escaped delimiter is part of the pattern, which is the only reason this is a loop.
      var index = 1
      val pattern = StringBuilder()
      while (index < argument.length && argument[index] != delimiter) {
        if (argument[index] == '\\' && index + 1 < argument.length) {
          pattern.append(argument[index])
          index++
        }
        pattern.append(argument[index])
        index++
      }
      if (index >= argument.length) return null

      index++
      var everyMatch = false
      var dontJump = false
      while (index < argument.length && argument[index] != ' ') {
        when (argument[index]) {
          'g' -> everyMatch = true
          'j' -> dontJump = true
          else -> return null
        }
        index++
      }

      return Arguments(pattern.toString(), everyMatch, dontJump, splitFiles(argument.substring(index)))
    }

    private fun splitFiles(text: String): List<String> =
      text.split(" ", "\t").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * A file pattern, as the list of files it names.
     *
     * Vim's rule, and the one thing worth getting right: a single star matches inside one path
     * segment and a double star matches across them, so `src/` + star + `.kt` is one directory and
     * a double star in the middle is a whole tree. A pattern with no wildcard in it is the file
     * itself, existing or not - `:vimgrep /x/ nofile` should complain about the file rather than
     * silently search nothing.
     */
    fun expandGlob(pattern: String, workingDirectory: String?): List<String> {
      val absolute = when {
        pattern.startsWith("/") || pattern.startsWith("~") -> pattern
        pattern.length > 2 && pattern[1] == ':' -> pattern
        workingDirectory != null -> "${workingDirectory.trimEnd('/', '\\')}/$pattern"
        else -> pattern
      }

      if ('*' !in absolute && '?' !in absolute) return listOf(absolute)

      val segments = absolute.split("/").filter { it.isNotEmpty() }
      val root = if (absolute.startsWith("/")) "" else "."
      return walk(root, segments, 0).sorted()
    }

    private fun walk(at: String, segments: List<String>, index: Int): List<String> {
      if (index >= segments.size) return if (injector.fileSystem.exists(at)) listOf(at) else emptyList()

      val segment = segments[index]

      // A double star matches here and at every depth below here, which is why it recurses on the
      // same segment index as well as on the next one.
      if (segment == "**") {
        val here = walk(at, segments, index + 1)
        val deeper = injector.fileSystem.listDirectory(at)
          .map { "$at/$it" }
          .filter { injector.fileSystem.isDirectory(it) }
          .flatMap { walk(it, segments, index) }
        return here + deeper
      }

      if ('*' !in segment && '?' !in segment) return walk("$at/$segment", segments, index + 1)

      return injector.fileSystem.listDirectory(at)
        .filter { matchesSegment(it, segment) }
        .flatMap { walk("$at/$it", segments, index + 1) }
    }

    /** One path segment against one wildcard segment; a star stops at the separator by never seeing one. */
    fun matchesSegment(name: String, pattern: String): Boolean {
      // The classic two-pointer glob, which handles `*` without backtracking into exponential time.
      var nameAt = 0
      var patternAt = 0
      var starAt = -1
      var nameAtStar = 0

      while (nameAt < name.length) {
        when {
          patternAt < pattern.length && (pattern[patternAt] == '?' || pattern[patternAt] == name[nameAt]) -> {
            nameAt++
            patternAt++
          }

          patternAt < pattern.length && pattern[patternAt] == '*' -> {
            starAt = patternAt
            nameAtStar = nameAt
            patternAt++
          }

          starAt >= 0 -> {
            patternAt = starAt + 1
            nameAtStar++
            nameAt = nameAtStar
          }

          else -> return false
        }
      }
      while (patternAt < pattern.length && pattern[patternAt] == '*') patternAt++
      return patternAt == pattern.length
    }
  }
}

/** see "h :vimgrep" */
@ExCommand(command = "vim[grep]")
data class VimGrepCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  VimGrepCommandBase(range, modifier, argument, isLocation = false, adds = false)

/** see "h :vimgrepadd" */
@ExCommand(command = "vimgrepa[dd]")
data class VimGrepAddCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  VimGrepCommandBase(range, modifier, argument, isLocation = false, adds = true)

/** see "h :lvimgrep" */
@ExCommand(command = "lv[imgrep]")
data class LocationVimGrepCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  VimGrepCommandBase(range, modifier, argument, isLocation = true, adds = false)

/** see "h :lvimgrepadd" */
@ExCommand(command = "lvimgrepa[dd]")
data class LocationVimGrepAddCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  VimGrepCommandBase(range, modifier, argument, isLocation = true, adds = true)
