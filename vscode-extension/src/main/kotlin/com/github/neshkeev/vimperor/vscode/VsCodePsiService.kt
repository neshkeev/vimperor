/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.MethodRanges
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimPsiService
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.group.getDoubleQuotesRangeNoPSI
import com.maddyhome.idea.vim.group.getSingleQuotesRangeNoPSI

/**
 * What this host can say about the shape of a file: something about strings, nothing about
 * comments, and - since a symbol cache arrived - where the functions and classes are.
 *
 * The engine asks these questions while scanning for brackets - `%`, `di(`, `ci{`, every block text
 * object. A bracket written inside a string or a comment is text rather than a bracket, and a scan
 * that counts it lands on the wrong pair. IdeaVim answers from IntelliJ's syntax tree, which knows
 * what a string is in the language at hand. So it named the interface after PSI and made the whole
 * thing IntelliJ's problem.
 *
 * VS Code has the same knowledge and will not part with it synchronously: an extension can ask a
 * language server for semantic tokens, but only over a promise, and these questions are asked once
 * per character in the middle of a keystroke. So this answers from the text itself.
 *
 * For strings that is less of a compromise than it sounds, because the engine has a text scan for
 * exactly this question: `getSingleQuotesRangeNoPSI` and its double-quoted twin walk the line from
 * its start tracking whether they are inside a string, and answer null when the offset is not in
 * one. IdeaVim calls the same two and then confirms the answer against PSI; without a syntax tree
 * there is nothing to confirm with, which is also what IdeaVim does for a plain-text file. It gets
 * `f("(", x)` right, which is the case that matters, and it will be wrong about an apostrophe in
 * prose, as Vim itself is.
 *
 * **It is deliberately not `findBlockQuoteInLineRange`, which is what this used to use.** That one
 * implements `i"`, and `i"` answers a different question: with the cursor *between* two strings on
 * a line, Vim's `i"` selects from the previous quote to the next one - across the gap, which is not
 * a string at all. IdeaVim's own `testDeleteDoubleQuotedStringBetweenEvenNumberOfQuotes` asserts
 * that, so it is Vim's behaviour and not a bug. Asking it "is this offset inside a string" gets a
 * yes for every bracket between two strings, so a bracket scan skipped those brackets: `%` on the
 * `(` of a line above
 * `select x->>'a', y->>'b' from f('c.csv', n=['d'])`
 * stopped at that line's own `)`, because the `(` before `'c.csv'` had been read as quoted and
 * never counted.
 *
 * For comments there is no equivalent: a comment is a `//` in one language, a `#` in another and a
 * `%` in a third, and guessing from the file extension would be a table of lies that grows forever.
 * Answering `null` is the same answer Vim gives with syntax off - brackets inside comments are
 * counted - and it is an honest one. It also means `getCommentBlockRange` stays unimplemented, so
 * `dgc` - Commentary's text object over a run of comment lines - does nothing here while the rest
 * of Commentary works.
 *
 * ## Functions and classes, which are answered
 *
 * [getMethodRanges] and [getClassRange] are a different case, and the difference is that VS Code
 * *will* say. `vscode.executeDocumentSymbolProvider` knows where every function and class in the
 * file begins and ends - it is what the Outline view draws - and the only problem was that it says
 * so over a promise. [DocumentSymbols] solves that by asking ahead of time rather than during the
 * keystroke, so these two are answered from a cache and are as good as the language server is.
 *
 * What the symbols do not carry is a body range, and the two rules below are the whole of what is
 * inferred rather than known. They are rules about text, and they are written down as such.
 */
internal class VsCodePsiService(private val symbols: DocumentSymbols = DocumentSymbols.None) : VimPsiService {

  override fun getCommentAtPos(editor: VimEditor, pos: Int): Pair<TextRange, Pair<String, String>?>? = null

  // `isInner` here is not `i"` versus `a"`: it decides whether the quote characters themselves count
  // as part of the range, not whether trailing whitespace does. Both of these take it through.

  override fun getDoubleQuotedString(editor: VimEditor, pos: Int, isInner: Boolean): TextRange? =
    getDoubleQuotesRangeNoPSI(editor.text(), pos, isInner)

  override fun getSingleQuotedString(editor: VimEditor, pos: Int, isInner: Boolean): TextRange? =
    getSingleQuotesRangeNoPSI(editor.text(), pos, isInner)
  // ---- Where a function or a class is, from the symbol cache. See [DocumentSymbols].

  override fun getMethodRanges(editor: VimEditor, offset: Int): MethodRanges? {
    val symbol = enclosing(editor, offset, SymbolKind.FUNCTION_LIKE) ?: return null
    val text = editor.text()
    val definitionStart = startOfLineAt(text, symbol.nameStart).coerceAtLeast(symbol.start)
    return MethodRanges(
      fullStart = symbol.start,
      definitionStart = definitionStart,
      end = symbol.end,
      body = bodyOf(text, definitionStart, symbol.end),
    )
  }

  override fun getClassRange(editor: VimEditor, offset: Int): TextRange? {
    val symbol = enclosing(editor, offset, SymbolKind.CLASS_LIKE) ?: return null
    return TextRange(symbol.start, symbol.end)
  }

  /**
   * The smallest symbol of one of [kinds] that contains [offset].
   *
   * Smallest rather than first, because the list is flat and a method's symbol sits inside its
   * class's: `ac` on a line inside a nested class has to find the nested one, and `am` inside a
   * local function has to find the local one. Both are the innermost match.
   */
  private fun enclosing(editor: VimEditor, offset: Int, kinds: Set<Int>): SymbolRange? {
    val document = (editor as? VsCodeEditor)?.nativeEditor?.document ?: return null
    val all = symbols.of(document) ?: return null
    return all
      .filter { it.kind in kinds && offset >= it.start && offset < it.end }
      .minByOrNull { it.end - it.start }
  }

  private fun startOfLineAt(text: CharSequence, offset: Int): Int {
    val at = offset.coerceIn(0, text.length)
    for (index in at - 1 downTo 0) {
      if (text[index] == '\n') return index + 1
    }
    return 0
  }

  /**
   * What `im` selects: the inside of the function's body.
   *
   * A `DocumentSymbol` does not carry one - it has the symbol's whole range and the range of its
   * name, and nothing between - so this is inferred from the text, by two rules that are each the
   * actual rule of the languages they apply to rather than a guess about them.
   *
   * **Braces.** If the last non-blank character of the symbol is `}`, the body is what its opening
   * brace encloses, found by counting depth backwards from the close. Counting rather than parsing
   * means a brace inside a string literal is miscounted; that is the same exposure the engine's own
   * `di{` has on this host and for the same reason, and it is a great deal better than nothing.
   *
   * **Indentation.** Otherwise the body is the run of lines indented more deeply than the line the
   * definition starts on. That is not an approximation of Python's rule, it *is* Python's rule, and
   * it gets Ruby right for the same reason from the other end: `end` sits at the definition's own
   * indentation, so it falls outside the run.
   *
   * Null when neither applies - a declaration with no body, which is what `MethodRanges` documents
   * null to mean and what `im` on an interface method should do nothing about.
   */
  private fun bodyOf(text: CharSequence, definitionStart: Int, end: Int): Pair<Int, Int>? {
    val close = lastNonBlank(text, definitionStart, end) ?: return null
    if (text[close] == '}') {
      val open = matchingOpenBrace(text, definitionStart, close) ?: return null
      return if (open + 1 <= close) open + 1 to close else null
    }
    return indentedBody(text, definitionStart, end)
  }

  private fun lastNonBlank(text: CharSequence, from: Int, until: Int): Int? {
    for (index in until.coerceAtMost(text.length) - 1 downTo from) {
      if (!text[index].isWhitespace()) return index
    }
    return null
  }

  private fun matchingOpenBrace(text: CharSequence, from: Int, close: Int): Int? {
    var depth = 0
    for (index in close downTo from) {
      when (text[index]) {
        '}' -> depth++
        '{' -> {
          depth--
          if (depth == 0) return index
        }
      }
    }
    return null
  }

  /** The run of lines indented more deeply than the one [definitionStart] is on. */
  private fun indentedBody(text: CharSequence, definitionStart: Int, end: Int): Pair<Int, Int>? {
    val baseIndent = indentWidthAt(text, definitionStart)
    var start: Int? = null
    var last: Int? = null
    var lineStart = endOfLine(text, definitionStart) + 1
    while (lineStart < end) {
      val lineEnd = endOfLine(text, lineStart).coerceAtMost(end)
      // A blank line belongs to the body when it is inside it, and never begins or ends it.
      if (text.subSequence(lineStart, lineEnd).any { !it.isWhitespace() }) {
        if (indentWidthAt(text, lineStart) <= baseIndent) break
        if (start == null) start = lineStart
        last = lineEnd
      }
      lineStart = lineEnd + 1
    }
    val from = start ?: return null
    return from to (last ?: return null)
  }

  /**
   * How far in the line containing [offset] is indented, counting a tab as one.
   *
   * One rather than `'tabstop'` because this is only ever compared with another line in the same
   * file, and files do not mix the two within a block. Comparing widths would need the option and
   * would answer the same.
   */
  private fun indentWidthAt(text: CharSequence, offset: Int): Int {
    var index = startOfLineAt(text, offset)
    var width = 0
    while (index < text.length && (text[index] == ' ' || text[index] == '\t')) {
      width++
      index++
    }
    return width
  }

  private fun endOfLine(text: CharSequence, offset: Int): Int {
    for (index in offset.coerceIn(0, text.length) until text.length) {
      if (text[index] == '\n') return index
    }
    return text.length
  }
}
