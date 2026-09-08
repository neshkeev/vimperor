/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.parser.errors

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.diagnostic.vimLogger
import org.antlr.v4.kotlinruntime.BaseErrorListener
import org.antlr.v4.kotlinruntime.Parser
import org.antlr.v4.kotlinruntime.RecognitionException
import org.antlr.v4.kotlinruntime.Recognizer
import org.antlr.v4.kotlinruntime.Token
import org.antlr.v4.kotlinruntime.misc.Interval

class IdeavimErrorListener : BaseErrorListener() {
  private val logger = vimLogger<IdeavimErrorListener>()

  companion object {
    val testLogger: MutableList<String> = mutableListOf<String>()

    /** The rules a *statement* starts in. An error here means the line is not a command at all. */
    private val statementStartRules = setOf("script", "blockMember")

    /**
     * ANTLR appends the whole expected-token set, which for this grammar is hundreds of names.
     *
     * Both braces are escaped. Upstream escapes only the opening one, which Java's regex engine
     * accepts - but Kotlin/JS compiles a `Regex` to a JavaScript `RegExp` with the `u` flag, and
     * that rejects a lone `}` outright: "Invalid regular expression: Lone quantifier brackets".
     * It throws while building the companion object, so *every* parse dies, not just a failing one.
     */
    private val expectedTokensRegex = Regex(" expecting \\{.*\\}")
  }

  override fun syntaxError(
    recognizer: Recognizer<*, *>,
    offendingSymbol: Any?,
    line: Int,
    charPositionInLine: Int,
    msg: String,
    e: RecognitionException?,
  ) {
    injector.vimscriptParser.linesWithErrors.add(line)

    // Two messages from one error, on purpose. The parser's own diagnostic names the rule and every
    // token that would have been legal, which is what you want in a log and is unreadable in a
    // status bar; the user gets Vim's wording instead.
    val diagnostic = "line $line:$charPositionInLine $msg"
    injector.vimscriptParser.errorMessages.add(
      userFacingMessage(recognizer, offendingSymbol, line, charPositionInLine, msg),
    )
    if (injector.application.isUnitTest()) {
      testLogger.add(diagnostic)
    } else {
      logger.warn(diagnostic)
    }
  }

  private fun userFacingMessage(
    recognizer: Recognizer<*, *>?,
    offendingSymbol: Any?,
    line: Int,
    charPositionInLine: Int,
    msg: String?,
  ): String {
    unknownCommandText(recognizer, offendingSymbol)?.let { return injector.messages.message("E492", it) }
    return "line $line:$charPositionInLine ${msg?.replace(expectedTokensRegex, "")}"
  }

  /**
   * The text of a line that is not a command, or null when the error is something else.
   *
   * The whole rest of the line rather than the offending token: `:foo bar` is not the command `foo`
   * with a bad argument, it is not a command at all, and `E492: Not an editor command: foo bar` is
   * what Vim prints.
   */
  private fun unknownCommandText(recognizer: Recognizer<*, *>?, offendingSymbol: Any?): String? {
    val parser = recognizer as? Parser ?: return null
    // `context`, not ANTLR-Java's `ruleContext`, and `Interval(a, b)` rather than `Interval.of` -
    // antlr-kotlin renames a few of these, and `size` is a function here.
    val ruleIndex = parser.context?.ruleIndex ?: return null
    if (parser.ruleNames.getOrNull(ruleIndex) !in statementStartRules) return null

    val token = offendingSymbol as? Token ?: return null
    val input = token.inputStream ?: return null
    // The EOF token starts past the end of the input, and there is no command text to report
    if (token.startIndex < 0 || token.startIndex >= input.size()) return null

    return input.getText(Interval(token.startIndex, input.size() - 1)).substringBefore('\n').trimEnd().ifEmpty { null }
  }
}
