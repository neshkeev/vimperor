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
import com.maddyhome.idea.vim.api.MutableVimEditor
import com.maddyhome.idea.vim.api.getText
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.helper.enumSetOf
import com.maddyhome.idea.vim.regexp.VimRegex
import com.maddyhome.idea.vim.regexp.VimRegexOptions
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * `:[range]uniq[!] [i][l][r][u] [/{pattern}/]` - Unix `uniq`, over a range of the buffer.
 *
 * *Adjacent* duplicates, which is the whole difference from `:sort u`: this removes a repeat only
 * when it sits directly under the line it repeats, so a file that is already grouped - a sorted
 * list, a log, a run of identical imports - is deduplicated without being reordered. `:sort u`
 * sorts first and answers a different question.
 *
 * Three flavours, and the middle one is the one to read twice:
 *
 * ```
 * :uniq       keep the first line of every run
 * :uniq u     keep only lines that never repeat at all
 * :uniq!      keep only lines that are immediately followed by a duplicate
 * ```
 *
 * Vim's own wording for the bang is "only keep lines that are immediately followed by a duplicate",
 * and it is taken literally here: in a run of three identical lines the first two are each followed
 * by a duplicate and the third is not, so two survive. `u` and `!` together are `!`, which is
 * Vim's rule too.
 *
 * `{pattern}` changes what is compared rather than which lines are looked at. With `r` the
 * comparison is on the text the pattern *matched*; without it, on the text that comes *after* the
 * match - which is how "ignore the first five characters" is spelled, `:uniq /.\{5}/`.
 *
 * `l`, Vim's collation-locale comparison, is accepted and compares the same way the default does.
 * There is one collation here and it is the platform's; `:sort l` makes the same choice and says so
 * in the same place.
 *
 * see "h :uniq"
 */
@ExCommand(command = "uni[q]")
data class UniqCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.WRITABLE)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    // Vim's default range is the whole file, not the current line: this is a sweep, and a sweep
    // over one line has nothing adjacent to it.
    val bounds = if (range.size() == 0) {
      0..(editor.lineCount() - 1)
    } else {
      getLineRange(editor).let { it.startLine..it.endLine.coerceAtMost(editor.lineCount() - 1) }
    }
    if (bounds.isEmpty()) return ExecutionResult.Success

    val flags = flagsAndPattern()
    val lines = bounds.map { editor.getText(editor.getLineStartOffset(it), editor.getLineEndOffset(it)) }
    val keys = lines.map { comparisonKey(it, flags) }
    val kept = lines.indices.filter { keep(keys, it, flags) }
    if (kept.size == lines.size) return ExecutionResult.Success

    val start = editor.getLineStartOffset(bounds.first)
    val end = (editor.getLineEndOffset(bounds.last) + 1).coerceAtMost(editor.fileSize().toInt())
    // The trailing newline is kept only if the original range had one, so deduplicating the last
    // lines of a file does not silently add or remove the newline at the end of it.
    val hadNewline = end > start && editor.text()[end - 1] == '\n'
    val replacement = kept.joinToString("\n") { lines[it] } + if (hadNewline) "\n" else ""
    (editor as MutableVimEditor).replaceString(start, end, replacement)

    editor.currentCaret().moveToOffset(injector.motion.moveCaretToLineStartSkipLeading(editor, bounds.first))
    return ExecutionResult.Success
  }

  private class Flags(
    val ignoreCase: Boolean,
    val onPattern: Boolean,
    val onlyUnrepeated: Boolean,
    val onlyRepeated: Boolean,
    val pattern: String?,
  )

  private fun flagsAndPattern(): Flags {
    val text = commandArgument.trim()
    // Any non-letter delimits the pattern, which is Vim's rule everywhere it takes one. The flags
    // are all letters, so the first non-letter that is not a space starts the pattern.
    val start = text.indexOfFirst { !it.isLetter() && !it.isWhitespace() }
    val letters = if (start < 0) text else text.take(start)
    val pattern = if (start < 0) {
      null
    } else {
      val delimiter = text[start]
      val closing = text.lastIndexOf(delimiter)
      if (closing > start) text.substring(start + 1, closing) else text.substring(start + 1)
    }

    val bang = modifier == CommandModifier.BANG
    return Flags(
      ignoreCase = 'i' in letters,
      onPattern = 'r' in letters,
      // "If both [!] and [u] are given, [u] is ignored and [!] takes effect."
      onlyUnrepeated = 'u' in letters && !bang,
      onlyRepeated = bang,
      // An empty pattern is Vim's "the last search pattern", which the search group already holds.
      pattern = pattern?.ifEmpty { injector.searchGroup.lastSearchPattern },
    )
  }

  /** What this line is compared on, which the pattern changes and the line itself otherwise is. */
  private fun comparisonKey(line: String, flags: Flags): String {
    val pattern = flags.pattern
    val compared = if (pattern.isNullOrEmpty()) {
      line
    } else {
      val match = matchIn(line, pattern)
      when {
        match == null -> line
        flags.onPattern -> line.substring(match.first, match.second)
        else -> line.substring(match.second)
      }
    }
    return if (flags.ignoreCase) compared.lowercase() else compared
  }

  /**
   * Where [pattern] matches in [line], as offsets into the line.
   *
   * `'ignorecase'` applies and `'smartcase'` does not, which is Vim's rule for this command
   * specifically - a pattern typed to skip a prefix is not a search and should not change meaning
   * because it happens to contain a capital.
   */
  private fun matchIn(line: String, pattern: String): Pair<Int, Int>? {
    val options = enumSetOf<VimRegexOptions>()
    if (injector.globalOptions().ignorecase) options.add(VimRegexOptions.IGNORE_CASE)
    return try {
      VimRegex(pattern).findAll(text = line, options = options).firstOrNull()
        ?.let { it.range.startOffset to it.range.endOffset }
    } catch (e: Throwable) {
      null
    }
  }

  private fun keep(keys: List<String>, at: Int, flags: Flags): Boolean {
    val repeatsAbove = at > 0 && keys[at - 1] == keys[at]
    val repeatsBelow = at < keys.size - 1 && keys[at + 1] == keys[at]
    return when {
      flags.onlyRepeated -> repeatsBelow
      flags.onlyUnrepeated -> !repeatsAbove && !repeatsBelow
      else -> !repeatsAbove
    }
  }
}
