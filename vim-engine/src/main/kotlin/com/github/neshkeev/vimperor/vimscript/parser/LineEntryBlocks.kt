/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vimscript.parser
/**
 * Folding `:append`, `:insert` and `:change` blocks into one line each, before the grammar sees them.
 *
 * These three are the only ex commands whose argument is *the lines that follow*:
 *
 * ```
 * :3append
 * a new line
 * and another
 * .
 * ```
 *
 * The grammar has no way to express that. Every rule in it ends a command at a newline, and the
 * three lines above would be parsed as three more commands - `E492` apiece. A lexer rule could
 * swallow the block the way `LOADKEYMAP` and the `lua <<EOF` rules do, but those begin with a word
 * nothing else begins with; these begin with `a`, `i` and `c`, and a rule anchored on a single
 * letter would swallow any `echo a` that happened to have a lone `.` somewhere below it.
 *
 * So the block is folded here instead, which is where this parser already does two other textual
 * passes before handing text to ANTLR. The inner newlines become [SEPARATOR], a character no
 * configuration file contains and that the grammar's catch-all `UNICODE_CHAR` rule lexes without
 * complaint, so the whole block arrives as one command with its lines in the argument. The commands
 * split them apart again.
 *
 * Replacing rather than removing is deliberate: a newline and [SEPARATOR] are both one character, so
 * every offset after a fold is where it was, and the only thing that actually goes is the `.` line -
 * which is registered as a deletion so the reported ranges stay honest. That is also what makes the
 * pass idempotent, which it has to be: [com.maddyhome.idea.vim.api.VimscriptParserBase.parse] feeds
 * its own preprocessed text back in when it recovers from an error, and a folded block has no
 * newlines left to fold.
 */
internal object LineEntryBlocks {

  /**
   * What an inner newline becomes: `U+0001`, SOH, which no configuration file has in it.
   *
   * Not one of the control characters the grammar already names - `U+0018` and `U+0008` are Vim's
   * own `CANCEL` and `BACKSPACE` and have lexer rules of their own, so either would arrive as
   * something with a meaning instead of as an anonymous character.
   */
  const val SEPARATOR: Char = '\u0001'

  /** The characters a range is made of - digits, the shorthands, a mark, or a search. */
  private const val RANGE = """(?:[0-9.,;${'$'}%+\-]|'[a-zA-Z<>'`\[\]]|/[^/]*/|\?[^?]*\?)*"""

  /** The three command names and every abbreviation of each, longest first so the match is greedy. */
  private const val NAMES =
    """(?:append|appen|appe|app|ap|a|insert|inser|inse|ins|in|i|change|chang|chan|cha|ch|c)"""

  /**
   * A line that is nothing but one of these three commands, with an optional range and bang.
   *
   * The range is spelled out rather than left as "anything", because "anything" is what makes this
   * dangerous: `echo a` ends in a valid command word too, and the only thing telling the two apart
   * is that a range holds no spaces and no letters outside a mark. So a line matches only when
   * everything before the command word is a range and there is nothing at all after it.
   */
  private val HEADER = Regex("""^[\s:]*$RANGE$NAMES!?\s*${'$'}""")

  /**
   * The same thing, looked for anywhere in the text, as a "is it worth splitting this?" check.
   *
   * It cannot be the cheaper test it started as - a lone `.` line - because a block is allowed to
   * end at the end of the file with no terminator at all, and that block has no `.` in it anywhere.
   */
  private val ANY_HEADER = Regex("""(^|\n)[ \t:]*$RANGE$NAMES!?[ \t]*(\n|${'$'})""")

  /**
   * Folds every block in [text]. [onDeletion] is told about each `.` line that goes, by offset in
   * the text being folded and length in characters, so that ranges can be mapped back afterwards.
   */
  fun fold(text: String, onDeletion: (offset: Int, length: Int) -> Unit): String {
    // Nothing to do for the overwhelming majority of scripts, and one regex over the text is
    // cheaper than splitting it - every `:source` and every command line typed at the prompt comes
    // through here.
    if (!ANY_HEADER.containsMatchIn(text)) return text

    val result = StringBuilder()
    val lines = text.split("\n")
    var offset = 0
    var index = 0
    while (index < lines.size) {
      val line = lines[index]
      val hasNewline = index < lines.size - 1
      val isHeader = hasNewline && HEADER.matches(line)
      result.append(line)
      offset += line.length + if (hasNewline) 1 else 0

      if (!isHeader) {
        if (hasNewline) result.append('\n')
        index++
        continue
      }

      // The header's own newline becomes the first separator, so the argument always begins with
      // one and an empty block is an argument of exactly one separator rather than no argument.
      index++
      while (index < lines.size) {
        val body = lines[index]
        val bodyHasNewline = index < lines.size - 1
        if (body.trim() == ".") {
          // The terminator goes, and it is the only thing that does. Its newline goes with it when
          // it has one; when it has none, the block ended at the end of the file.
          onDeletion(offset, body.length + if (bodyHasNewline) 1 else 0)
          index++
          break
        }
        // The last element of a split on newlines is the remainder after the final one, not a
        // line. Without this a block running to the end of a file gains an empty line nobody wrote.
        if (!bodyHasNewline && body.isEmpty()) break
        result.append(SEPARATOR).append(body)
        offset += body.length + if (bodyHasNewline) 1 else 0
        index++
        if (!bodyHasNewline) break
      }
      // One newline to end the folded command, whatever it was that ended the block.
      result.append('\n')
    }
    return result.toString()
  }
}
