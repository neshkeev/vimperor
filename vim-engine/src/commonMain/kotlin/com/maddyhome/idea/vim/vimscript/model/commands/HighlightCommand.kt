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
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.highlight.HighlightColors
import com.maddyhome.idea.vim.highlight.Highlights
import com.maddyhome.idea.vim.match.Matches
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * `:highlight` - defining what a highlight group looks like.
 *
 * This is the command `:match` was written against and did not have. Until now a `:match Todo`
 * meant "whatever this editor thinks Todo is", because the group name reached a host that mapped
 * it to the nearest colour in the loaded theme; there was no way for a config to say what it
 * actually wanted. Now there is, and the mapping stays exactly where it was for every group
 * nobody has defined - which is still almost all of them.
 *
 * What it does not do is Vim's other half. In Vim `:highlight` is also how a *syntax file* colours
 * a language, and neither of this fork's hosts highlights syntax: IntelliJ has a lexer and VS Code
 * has a grammar, and both of them decide what a comment looks like without asking. So defining
 * `Comment` here has no effect on any comment, and that is not a gap that can be closed - it is
 * two editors doing their own job. The groups that matter are the ones a *user* points at, which
 * is `:match` and its two numbered twins.
 *
 * Six forms, all of Vim's:
 *
 * ```
 * :highlight                              list every group that has been given something
 * :highlight {group}                      list one
 * :highlight clear                        forget all of it
 * :highlight clear {group}   :hi {group} NONE     stop painting one group
 * :highlight [default] {group} {key}={value} ...  define, merging with what was there
 * :highlight[!] [default] link {from} {to}        make one group mean another
 * ```
 *
 * see "h :highlight"
 */
@ExCommand(command = "hi[ghlight]")
data class HighlightCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val tokens = commandArgument.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return listEverything(editor, context)

    var rest = tokens
    val isDefault = rest.first().equals("default", ignoreCase = true)
    if (isDefault) rest = rest.drop(1)
    if (rest.isEmpty()) throw exExceptionMessage("E471")

    if (rest.first().equals("link", ignoreCase = true)) {
      makeLink(editor, rest.drop(1), isDefault)
      return repainted(editor)
    }

    // `:hi clear` is checked before the group name, which means a group called "clear" is out of
    // reach. It is out of reach in Vim too, and for the same reason.
    if (rest.first().equals("clear", ignoreCase = true)) {
      when (rest.size) {
        1 -> Highlights.clearAll()
        2 -> Highlights.disable(rest[1])
        else -> throw exExceptionMessage("E488", rest.drop(2).joinToString(" "))
      }
      return repainted(editor)
    }

    val group = rest.first()
    if ('=' in group) throw exExceptionMessage("E415", group)
    val settings = rest.drop(1)

    if (settings.isEmpty()) {
      val listing = Highlights.describe(group) ?: throw exExceptionMessage("E411", group)
      injector.outputPanel.output(editor, context, listing + "\n")
      return ExecutionResult.Success
    }

    if (settings.size == 1 && settings.first().equals("none", ignoreCase = true)) {
      Highlights.disable(group)
      return repainted(editor)
    }

    Highlights.define(group, parseSettings(settings), isDefault)
    return repainted(editor)
  }

  private fun listEverything(editor: VimEditor, context: ExecutionContext): ExecutionResult {
    val lines = Highlights.names().mapNotNull { Highlights.describe(it) }
    // Vim prints nothing at all when no group has anything set. Here that is the state a session
    // starts in rather than an unusual one, so it gets a sentence instead of a blank panel.
    val text = if (lines.isEmpty()) NOTHING_DEFINED else lines.joinToString("\n")
    injector.outputPanel.output(editor, context, text + "\n")
    return ExecutionResult.Success
  }

  private fun makeLink(editor: VimEditor, arguments: List<String>, isDefault: Boolean) {
    if (arguments.size < 2) throw exExceptionMessage("E412")
    if (arguments.size > 2) throw exExceptionMessage("E413")
    val (from, to) = arguments
    val target = if (to.equals("none", ignoreCase = true)) null else to
    val result = Highlights.link(from, target, isDefault, modifier == CommandModifier.BANG)
    // A refused `default` link is the normal case rather than a problem: it is how a syntax file
    // states a colour without overruling the one already there. Only a plain `:hi link` complains.
    if (result == Highlights.LinkResult.HAS_SETTINGS) throw exExceptionMessage("E414")
  }

  /**
   * `{key}={value}` pairs into the changes [Highlights.define] merges. A null value is Vim's `NONE`.
   *
   * The colours are resolved here rather than at paint time so that a name nobody recognises is
   * `E421` on the line that wrote it, which is where it can be fixed. What gets stored is still the
   * text that was typed - resolution happens twice, once to check and once to paint, and the second
   * one cannot fail because the first one ran.
   */
  private fun parseSettings(tokens: List<String>): Map<String, String?> {
    val changes = mutableMapOf<String, String?>()
    var index = 0
    while (index < tokens.size) {
      val token = tokens[index]
      val equals = token.indexOf('=')
      if (equals < 0) throw exExceptionMessage("E416", token)
      val key = token.take(equals).lowercase()
      if (key !in Highlights.KEYS) throw exExceptionMessage("E423", token)

      // A font name is the one value with spaces in it, and Vim reads it to the end of the line.
      // Everything else forbids blanks, so there is nothing to be ambiguous about.
      var value = token.drop(equals + 1)
      if (key == "font") {
        value = (listOf(value) + tokens.drop(index + 1)).joinToString(" ").trim()
        index = tokens.size
      } else {
        index++
      }
      if (value.isEmpty()) throw exExceptionMessage("E417", token)

      changes[key] = if (value.equals("none", ignoreCase = true)) null else checked(key, value)
    }
    return changes
  }

  private fun checked(key: String, value: String): String {
    when (key) {
      "term", "cterm", "gui" -> {
        for (attribute in value.split(',')) {
          if (attribute.trim().lowercase() !in ATTRIBUTES) throw exExceptionMessage("E418.value", value)
        }
      }

      "guifg", "guibg", "guisp" -> {
        if (!isTheEditorsOwn(value) && HighlightColors.gui(value) == null) {
          throw exExceptionMessage("E421", value)
        }
      }

      "ctermfg", "ctermbg", "ctermul" -> {
        if (!isTheEditorsOwn(value) && HighlightColors.cterm(value) == null) {
          throw exExceptionMessage("E421", value)
        }
      }
    }
    return value
  }

  private fun isTheEditorsOwn(value: String): Boolean =
    value.lowercase() in setOf("fg", "bg", "ul", "foreground", "background")

  /**
   * Whatever `:match` is showing has to be repainted, because its colours may have just changed.
   *
   * The pattern did not move, so this is not a search being re-run for nothing: it is the one
   * moment a standing highlight can change appearance without a keystroke, and without this the
   * new colour would not arrive until the next key was pressed.
   */
  private fun repainted(editor: VimEditor): ExecutionResult {
    Matches.repaint(editor)
    return ExecutionResult.Success
  }

  private companion object {
    val WHITESPACE = Regex("\\s+")

    /** Vim's attr-list, as `:help attr-list` gives it. `NONE` is handled before this is reached. */
    val ATTRIBUTES = setOf(
      "bold", "underline", "undercurl", "underdouble", "underdotted", "underdashed",
      "strikethrough", "reverse", "inverse", "italic", "standout", "nocombine",
    )

    const val NOTHING_DEFINED =
      "No highlight groups have been defined. Both hosts colour their own text, so a group only " +
        "matters once something names it - :match, :2match or :3match."
  }
}
