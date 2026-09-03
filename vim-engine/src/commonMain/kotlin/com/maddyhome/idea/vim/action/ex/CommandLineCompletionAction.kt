/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.action.ex

import com.intellij.vim.annotations.CommandOrMotion
import com.intellij.vim.annotations.Mode
import com.maddyhome.idea.vim.api.CommandCompletionTypes
import com.maddyhome.idea.vim.api.CommandLineCompletion
import com.maddyhome.idea.vim.api.CommandLineCompletionType
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimCommandLine
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.Argument
import com.maddyhome.idea.vim.options.ToggleOption

@CommandOrMotion(keys = ["<Tab>"], modes = [Mode.CMD_LINE])
class CommandLineCompletionAction : CommandLineActionHandler() {
  override fun execute(
    commandLine: VimCommandLine,
    editor: VimEditor,
    context: ExecutionContext,
    argument: Argument?,
  ): Boolean {
    return performCompletion(commandLine, context, forward = true)
  }

  override fun execute(commandLine: VimCommandLine): Boolean {
    return false
  }
}

@CommandOrMotion(keys = ["<S-Tab>"], modes = [Mode.CMD_LINE])
class CommandLineCompletionBackwardAction : CommandLineActionHandler() {
  override fun execute(
    commandLine: VimCommandLine,
    editor: VimEditor,
    context: ExecutionContext,
    argument: Argument?,
  ): Boolean {
    return performCompletion(commandLine, context, forward = false)
  }

  override fun execute(commandLine: VimCommandLine): Boolean {
    return false
  }
}

private fun performCompletion(
  commandLine: VimCommandLine,
  context: ExecutionContext,
  forward: Boolean,
): Boolean {
  if (!commandLine.isExCommand()) return false

  val existing = commandLine.activeCompletion
  if (existing != null && existing.expectedText == commandLine.text) {
    cycleExistingCompletion(commandLine, existing, forward)
    return true
  }

  return startNewCompletion(commandLine, context, forward)
}

internal fun cycleExistingCompletion(
  commandLine: VimCommandLine,
  completion: CommandLineCompletion,
  forward: Boolean,
) {
  val match = selectMatch(completion, forward)
  if (match == null) {
    injector.messages.indicateError()
    return
  }

  applyMatch(commandLine, completion, match)
}

private fun startNewCompletion(
  commandLine: VimCommandLine,
  context: ExecutionContext,
  forward: Boolean,
): Boolean {
  commandLine.activeCompletion = null
  commandLine.hideCompletionBar()

  val text = commandLine.text
  val parsed = parseCommandLineForCompletion(text)?.let { narrowToCompletedWord(it) } ?: return false
  val matches = findMatches(parsed, context) ?: return false

  if (matches.isEmpty()) {
    injector.messages.indicateError()
    return true
  }

  if (matches.size == 1) {
    applySingleMatch(commandLine, text, parsed.completionStart, matches[0])
    return true
  }

  val completion = CommandLineCompletion(text, parsed.completionStart, matches)
  commandLine.activeCompletion = completion
  commandLine.showCompletionBar(completion)

  val match = selectMatch(completion, forward) ?: return true
  applyMatch(commandLine, completion, match)
  return true
}

/**
 * The part of the line Tab is going to replace.
 *
 * The parser hands back the whole argument, because for most commands that is what is being typed:
 * `:edit my file.txt` names one file whose name has a space in it, and completing only `file.txt`
 * would produce a path nobody meant. A command whose argument is a *list* is the other case -
 * `:set nu sy` has already settled `nu` - and [CommandLineCompletionType.completesLastWord] is
 * which commands those are.
 */
private fun narrowToCompletedWord(parsed: CommandLineCompletionContext): CommandLineCompletionContext {
  if (parsed !is ArgumentCompletionContext) return parsed
  if (completionTypeOf(parsed)?.completesLastWord != true) return parsed
  val lastSpace = parsed.argumentPrefix.indexOfLast { it == ' ' || it == '\t' }
  if (lastSpace < 0) return parsed
  return parsed.copy(
    argumentPrefix = parsed.argumentPrefix.substring(lastSpace + 1),
    completionStart = parsed.completionStart + lastSpace + 1,
  )
}

/** What this command's argument completes against, or null for a name nothing is registered under. */
private fun completionTypeOf(parsed: ArgumentCompletionContext): CommandLineCompletionType? {
  val fullCommandName = injector.vimscriptParser.exCommands.getFullCommandName(parsed.commandName) ?: return null
  return CommandCompletionTypes.getCompletionType(fullCommandName)
}

private fun findMatches(parsed: CommandLineCompletionContext, context: ExecutionContext): List<String>? {
  return when (parsed) {
    is CommandNameCompletionContext -> findCommandNameMatches(parsed)
    is ArgumentCompletionContext -> findArgumentMatches(parsed, context)
  }
}

private fun findCommandNameMatches(parsed: CommandNameCompletionContext): List<String> {
  return injector.vimscriptParser.exCommands.findFullCommandsByPrefix(parsed.prefix)
}

private fun findArgumentMatches(parsed: ArgumentCompletionContext, context: ExecutionContext): List<String>? {
  return when (completionTypeOf(parsed) ?: return null) {
    CommandLineCompletionType.FILE -> injector.file.listFilesForCompletion(parsed.argumentPrefix, context)
    // Sorted here rather than trusted from the host: IntelliJ's `ActionManager.getActionIdList`
    // answers in no order at all, and `CommandLineCompletion` is documented as taking sorted
    // candidates because Tab walks them in the order it is given.
    CommandLineCompletionType.ACTION -> injector.actionExecutor.getActionIdList(parsed.argumentPrefix).sorted()
    CommandLineCompletionType.OPTION -> findOptionMatches(parsed.argumentPrefix)
    CommandLineCompletionType.NONE -> null
  }
}

/**
 * The option names `:set {prefix}` could be about to say.
 *
 * Full names only, as in Vim: `:set syn<Tab>` gives `syntax` rather than leaving `syn` alone,
 * because the abbreviation is a way of writing the option and not a second option. Sorted, because
 * `CommandLineCompletion` walks the list in the order it is given and Vim walks these
 * alphabetically.
 *
 * A word that has already said what it wants is not a name being typed. `:set nu?` is asking,
 * `:set nu!` is toggling and `:set sw=4` is assigning, and Vim completes none of the three - the
 * third only because a *value* is a different question, and one this fork has no answer to for any
 * option.
 */
private fun findOptionMatches(prefix: String): List<String> {
  if (prefix.any { it in SETTLED }) return emptyList()

  val options = injector.optionGroup.getAllOptions()
  val names = options.map { it.name }.filter { it.startsWith(prefix) }

  // `:set nonumber` and `:set invnumber` are the option's name with two or three characters in
  // front of it, and only a boolean option can take them.
  val negations = NEGATIONS.filter { prefix.startsWith(it) }.flatMap { negation ->
    val rest = prefix.substring(negation.length)
    options.filterIsInstance<ToggleOption>().map { it.name }.filter { it.startsWith(rest) }.map { negation + it }
  }

  return (names + negations).distinct().sorted()
}

/** The characters that turn a name being typed into a question, a toggle or an assignment. */
private val SETTLED = charArrayOf('?', '!', '&', '=', ':')

private val NEGATIONS = listOf("no", "inv")

internal fun selectMatch(completion: CommandLineCompletion, forward: Boolean): String? {
  return if (forward) completion.nextMatch() else completion.previousMatch()
}

private fun applySingleMatch(commandLine: VimCommandLine, originalText: String, completionStart: Int, match: String) {
  val prefix = originalText.substring(0, completionStart)
  val newText = prefix + match
  commandLine.setText(newText)
  commandLine.caret.offset = newText.length
}

internal fun applyMatch(commandLine: VimCommandLine, completion: CommandLineCompletion, match: String) {
  val prefix = completion.originalText.substring(0, completion.completionStart)
  val newText = prefix + match
  completion.updateExpectedText(newText)
  commandLine.setText(newText)
  commandLine.caret.offset = newText.length
  commandLine.selectCompletionItem(completion.currentIndex)
}
