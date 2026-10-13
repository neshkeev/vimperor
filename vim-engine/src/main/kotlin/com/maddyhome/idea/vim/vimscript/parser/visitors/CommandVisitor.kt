/*
 * Copyright 2003-2024 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.parser.visitors

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.diagnostic.vimLogger
import com.maddyhome.idea.vim.ex.ExException
import com.maddyhome.idea.vim.ex.ranges.Address
import com.maddyhome.idea.vim.ex.ranges.Address.Companion.createRangeAddresses
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.parser.generated.VimscriptBaseVisitor
import com.maddyhome.idea.vim.parser.generated.VimscriptParser
import com.maddyhome.idea.vim.parser.generated.VimscriptParser.CallCommandContext
import com.maddyhome.idea.vim.parser.generated.VimscriptParser.DelfunctionCommandContext
import com.maddyhome.idea.vim.parser.generated.VimscriptParser.EchoCommandContext
import com.maddyhome.idea.vim.parser.generated.VimscriptParser.ExprContext
import com.maddyhome.idea.vim.parser.generated.VimscriptParser.OtherCommandContext
import com.maddyhome.idea.vim.parser.generated.VimscriptParser.RangeContext
import com.maddyhome.idea.vim.parser.generated.VimscriptParser.RangeOffsetContext
import com.maddyhome.idea.vim.vimscript.model.commands.CallCommand
import com.maddyhome.idea.vim.vimscript.model.commands.Command
import com.maddyhome.idea.vim.vimscript.model.commands.CommandModifier
import com.maddyhome.idea.vim.vimscript.model.commands.DelfunctionCommand
import com.maddyhome.idea.vim.vimscript.model.commands.EchoCommand
import com.maddyhome.idea.vim.vimscript.model.commands.ExecuteCommand
import com.maddyhome.idea.vim.vimscript.model.commands.FunctionCommand
import com.maddyhome.idea.vim.vimscript.model.commands.GlobalCommand
import com.maddyhome.idea.vim.vimscript.model.commands.GoToLineCommand
import com.maddyhome.idea.vim.vimscript.model.commands.LazyExCommandInstance
import com.maddyhome.idea.vim.vimscript.model.commands.LetCommand
import com.maddyhome.idea.vim.vimscript.model.commands.MarkCommand
import com.maddyhome.idea.vim.vimscript.model.commands.ShiftLeftCommand
import com.maddyhome.idea.vim.vimscript.model.commands.ShiftRightCommand
import com.maddyhome.idea.vim.vimscript.model.commands.SplitCommand
import com.maddyhome.idea.vim.vimscript.model.commands.SplitType
import com.maddyhome.idea.vim.vimscript.model.commands.SubstituteCommand
import com.maddyhome.idea.vim.vimscript.model.commands.UnknownCommand
import com.maddyhome.idea.vim.vimscript.model.commands.mapping.AbbrevClearCommand
import com.maddyhome.idea.vim.vimscript.model.commands.mapping.AbbrevCommand
import com.maddyhome.idea.vim.vimscript.model.commands.mapping.LoadKeymapCommand
import com.maddyhome.idea.vim.vimscript.model.commands.mapping.MapClearCommand
import com.maddyhome.idea.vim.vimscript.model.commands.mapping.MapCommand
import com.maddyhome.idea.vim.vimscript.model.commands.mapping.UnMapCommand
import com.maddyhome.idea.vim.vimscript.model.commands.mapping.UnabbrevCommand
import com.maddyhome.idea.vim.vimscript.model.expressions.Expression
import com.maddyhome.idea.vim.vimscript.model.expressions.Scope
import com.maddyhome.idea.vim.vimscript.model.expressions.SimpleExpression
import com.maddyhome.idea.vim.vimscript.model.expressions.operators.AssignmentOperator
import com.maddyhome.idea.vim.vimscript.model.expressions.operators.AssignmentOperator.Companion.getByValue
import org.antlr.v4.kotlinruntime.ParserRuleContext
import kotlin.reflect.KClass

object CommandVisitor : VimscriptBaseVisitor<Command?>() {

  /** Null, as the Java runtime returned - see [ExpressionVisitor.defaultResult]. */
  override fun defaultResult(): Command? = null

  private const val CTRL_V = '\u0016'


  private val logger = vimLogger<CommandVisitor>()
  private val expressionVisitor: ExpressionVisitor = ExpressionVisitor

  private fun parseRangeOffset(ctx: RangeOffsetContext?): Int {
    var offset = 0
    if (ctx != null) {
      offset += ctx.plusOneOffset().size
      offset -= ctx.minusOneOffset().size
      for (number in ctx.numberInOffset()) {
        offset += number.text!!.toInt()
      }
    }
    return offset
  }

  private fun parseRangeExpression(ctx: VimscriptParser.RangeExpressionContext?): Pair<String, Int> {
    val offset = parseRangeOffset(ctx?.rangeOffset())
    return if (ctx == null) {
      Pair(".", offset)
    } else if (ctx.rangeMember() == null) {
      Pair(".", offset)
    } else {
      // One call, not four: each `rangeMember()` is a fresh invocation, so the null check above
      // does not smart-cast the ones that follow it.
      val rangeMember = ctx.rangeMember()!!
      val search = rangeMember.search()
      if (search == null || search.isEmpty()) {
        Pair(rangeMember.text, offset)
      } else {
        Pair(search.joinToString("\u0000") { it.text }, offset)
      }
    }
  }

  private fun parseRangeUnit(ctx: VimscriptParser.RangeUnitContext): Array<Address> {
    val valueAndOffset = parseRangeExpression(ctx.rangeExpression())
    val move = ctx.rangeSeparator()?.text == ";"
    val addresses = createRangeAddresses(valueAndOffset.first, valueAndOffset.second, move)
    if (addresses == null) {
      logger.warn("Could not create an address for node ${ctx.text}")
      throw ExException("Could not create an address ${ctx.text}")
    }
    return addresses
  }

  private fun parseRange(ctx: RangeContext?): Range {
    val range = Range()
    if (ctx?.rangeUnit() != null) {
      val units = ctx.rangeUnit()
      var lastProcessed = -1
      for ((index, unit) in units.withIndex()) {
        if (index > 0 && units[index - 1].rangeSeparator() == null) break
        range.addAddresses(parseRangeUnit(unit))
        lastProcessed = index
      }
      // If the range ends with a dangling separator, the last address is an implied current line address
      if (lastProcessed >= 0 && units[lastProcessed].rangeSeparator()?.text == ",") {
        createRangeAddresses(".", 0, false)?.let { range.addAddresses(it) }
      }
    }
    return range
  }

  override fun visitLet1Command(ctx: VimscriptParser.Let1CommandContext): Command {
    val range: Range = parseRange(ctx.range())
    val lvalue: Expression? = ctx.lvalue?.let { expressionVisitor.visitExpression(it) }
    val unpackLValues = ctx.unpack?.lvalues?.map { expressionVisitor.visitExpression(it) }
    val unpackRest: Expression? = ctx.unpack?.rest?.let { expressionVisitor.visitExpression(it) }
    val operator = getByValue(ctx.assignmentOperator().text)
    val expression: Expression = expressionVisitor.visitExpression(ctx.rvalue!!)
    val assignmentTextForErrors = buildString {
      ctx.children.orEmpty()
        .dropWhile { it != ctx.unpack && it != ctx.lvalue }
        .takeWhile { it != ctx.rvalue }
        .forEach { append(it.text) }
      append(ctx.rvalue!!.text)
    }
    val command = LetCommand(
      range,
      lvalue,
      unpackLValues,
      unpackRest,
      operator,
      expression,
      isSyntaxSupported = true,
      assignmentTextForErrors
    )
    command.rangeInScript = ctx.getTextRange()
    return command
  }

  override fun visitLet2Command(ctx: VimscriptParser.Let2CommandContext): Command {
    val command = LetCommand(
      Range(),
      SimpleExpression(0),
      AssignmentOperator.ASSIGNMENT,
      SimpleExpression(0),
      false,
      ctx.text
    )
    command.rangeInScript = ctx.getTextRange()
    return command
  }

  override fun visitEchoCommand(ctx: EchoCommandContext): Command {
    val range: Range = parseRange(ctx.range())
    val expressions = ctx.expr()
      .map { tree: ExprContext ->
        expressionVisitor.visitExpression(tree)
      }
    val command = EchoCommand(range, expressions)
    command.rangeInScript = ctx.getTextRange()
    return command
  }

  override fun visitCallCommand(ctx: CallCommandContext): Command {
    val range: Range = parseRange(ctx.range())
    val functionCall = ExpressionVisitor.visitExpression(ctx.expr())
    val command = CallCommand(range, functionCall)
    command.rangeInScript = ctx.getTextRange()
    return command
  }

  override fun visitDelfunctionCommand(ctx: DelfunctionCommandContext): DelfunctionCommand {
    val range: Range = parseRange(ctx.range())
    val functionScope =
      if (ctx.functionScope() != null) Scope.getByValue(ctx.functionScope()!!.text) else null
    // Preserve the autoload namespace prefix (e.g. `foo#bar#`) so deletion targets the full name.
    val autoloadPrefix = ctx.anyCaseNameWithDigitsAndUnderscores().joinToString(separator = "") { "${it.text}#" }
    val functionName = autoloadPrefix + ctx.functionName().text
    val ignoreIfMissing = ctx.replace != null
    val command = DelfunctionCommand(range, functionScope, functionName, ignoreIfMissing)
    command.rangeInScript = ctx.getTextRange()
    return command
  }

  override fun visitFunctionCommand(ctx: VimscriptParser.FunctionCommandContext): Command {
    val range: Range = parseRange(ctx.range())
    val argument: VimscriptParser.FunctionCommandArgumentContext? = ctx.functionCommandArgument()
    val command = argument?.functionCommandName()?.let {
      val namePrefix: String? = it.functionCommandPrefix()?.text
      val name = it.curlyBracesFunctionName()?.let { name -> expressionVisitor.visitCurlyBracesFunctionName(name) }
      // `Token.text` is nullable in antlr-kotlin where the Java runtime returned a non-null string
      val trailing: String? = it.trailing?.takeIf { it.isNotEmpty() }?.joinToString("") { it.text.orEmpty() }
      val literalDictionaryKey = when {
        it.literalDictionaryKey().isEmpty() && trailing?.first() == '.' -> ""
        it.literalDictionaryKey().isEmpty() -> null
        else -> it.literalDictionaryKey().joinToString(separator = ".") { key -> key.text }
      }
      FunctionCommand(range, CommandModifier.NONE, namePrefix, name, literalDictionaryKey, trailing)
    } ?: argument?.functionCommandPattern()?.let {
      FunctionCommand(range, CommandModifier.NONE, it.text)
    } ?: FunctionCommand(range, CommandModifier.NONE)
    command.rangeInScript = ctx.getTextRange()
    return command
  }

  override fun visitGoToLineCommand(ctx: VimscriptParser.GoToLineCommandContext): Command {
    val range: Range
    if (ctx.range() != null) {
      range = parseRange(ctx.range())
    } else {
      range = Range()
      range.addAddresses(
        createRangeAddresses(ctx.shortRange()!!.text, 0, false)
          ?: throw ExException("Could not create a range"),
      )
    }
    val command = GoToLineCommand(range)
    command.rangeInScript = ctx.getTextRange()
    return command
  }

  override fun visitCommandWithComment(ctx: VimscriptParser.CommandWithCommentContext): Command {
    val ranges = parseRange(ctx.range())
    val commandName = ctx.name!!.text!!
    val modifier = if (ctx.bangModifier != null) CommandModifier.BANG else CommandModifier.NONE
    val argument = ctx.commandArgumentWithoutBars()?.text ?: ""
    return createCommandByCommandContext(ranges, commandName, modifier, argument, ctx)
  }

  override fun visitCommandWithoutComments(ctx: VimscriptParser.CommandWithoutCommentsContext): Command {
    val ranges = parseRange(ctx.range())
    val commandName = ctx.name!!.text!!
    val modifier = if (ctx.bangModifier != null) CommandModifier.BANG else CommandModifier.NONE
    val argument = ctx.commandArgumentWithoutBars()?.text ?: ""
    return createCommandByCommandContext(ranges, commandName, modifier, argument, ctx)
  }

  override fun visitLoadKeymapCommand(ctx: VimscriptParser.LoadKeymapCommandContext): Command {
    val ranges = parseRange(ctx.range())
    // The LOADKEYMAP token holds the command name plus the whole keymap table; they are separated by
    // the first newline. Everything after it is the table (empty when `:loadkeymap` has no body).
    val argument = ctx.LOADKEYMAP().text.substringAfter('\n', "")
    return createCommandByCommandContext(ranges, "loadkeymap", CommandModifier.NONE, argument, ctx)
  }

  override fun visitCommandWithBars(ctx: VimscriptParser.CommandWithBarsContext): Command {
    val ranges = parseRange(ctx.range())
    val commandName = ctx.name!!.text!!
    val argument = ctx.commandArgumentWithBars()?.text ?: ""
    val modifier = if (ctx.bangModifier != null) CommandModifier.BANG else CommandModifier.NONE
    return createCommandByCommandContext(ranges, commandName, modifier, argument, ctx)
  }

  private fun createCommandByCommandContext(
    range: Range,
    commandName: String,
    modifier: CommandModifier,
    commandLine: String,
    ctx: ParserRuleContext,
  ): Command {
    val (argument, nextCommand) = splitOffNextCommand(commandName, commandLine)
    val command = when (getCommandByName(commandName)) {
      LoadKeymapCommand::class -> LoadKeymapCommand(range, commandName, modifier, argument)
      MapCommand::class -> MapCommand(range, commandName, modifier, argument)
      MapClearCommand::class -> MapClearCommand(range, commandName, modifier, argument)
      UnMapCommand::class -> UnMapCommand(range, commandName, modifier, argument)
      AbbrevCommand::class -> AbbrevCommand(range, commandName, modifier, argument)
      UnabbrevCommand::class -> UnabbrevCommand(range, commandName, modifier, argument)
      AbbrevClearCommand::class -> AbbrevClearCommand(range, commandName, modifier, argument)
      GlobalCommand::class -> {
        if (commandName.startsWith("v")) {
          GlobalCommand(range, modifier, argument, true)
        } else {
          val inverse = modifier == CommandModifier.BANG
          GlobalCommand(range, modifier, argument, inverse)
        }
      }

      SplitCommand::class -> {
        if (commandName.startsWith("v")) {
          SplitCommand(range, argument, SplitType.VERTICAL)
        } else {
          SplitCommand(range, argument, SplitType.HORIZONTAL)
        }
      }

      SubstituteCommand::class -> SubstituteCommand(range, argument, commandName)
      else -> injector.vimscriptParser.exCommands.getCommand(commandName)?.create(range, modifier, argument)
        ?: error("No ex-command with a standard constructor is registered for '$commandName'")
    }
    command.nextCommand = nextCommand
    command.rangeInScript = ctx.getTextRange()
    return command
  }

  override fun visitShiftLeftCommand(ctx: VimscriptParser.ShiftLeftCommandContext): ShiftLeftCommand {
    val ranges = parseRange(ctx.range())
    val argument = ctx.commandArgumentWithoutBars()?.text ?: ""
    val length = ctx.lShift().text.length
    val command = ShiftLeftCommand(ranges, argument, length)
    command.rangeInScript = ctx.getTextRange()
    return command
  }

  override fun visitShiftRightCommand(ctx: VimscriptParser.ShiftRightCommandContext): ShiftRightCommand {
    val ranges = parseRange(ctx.range())
    val argument = ctx.commandArgumentWithoutBars()?.text ?: ""
    val length = ctx.rShift().text.length
    val command = ShiftRightCommand(ranges, argument, length)
    command.rangeInScript = ctx.getTextRange()
    return command
  }

  override fun visitExecuteCommand(ctx: VimscriptParser.ExecuteCommandContext): ExecuteCommand {
    val ranges = parseRange(ctx.range())
    val expressions = ctx.expr()
      .map { tree: ExprContext ->
        expressionVisitor.visitExpression(tree)
      }
    val command = ExecuteCommand(ranges, expressions)
    command.rangeInScript = ctx.getTextRange()
    return command
  }

  override fun visitLetCommand(ctx: VimscriptParser.LetCommandContext): Command {
    val command = injector.vimscriptParser.parseLetCommand(ctx.text)
      ?: LetCommand(
        Range(),
        SimpleExpression(0),
        AssignmentOperator.ASSIGNMENT,
        SimpleExpression(0),
        false,
        ctx.text,
      )
    command.rangeInScript = ctx.getTextRange()
    return command
  }

  /**
   * Called for unmatched commands, such as aliases
   */
  override fun visitOtherCommand(ctx: OtherCommandContext): Command {
    val range: Range = parseRange(ctx.range())
    val name = ctx.commandName().text
    val modifier = if (ctx.bangModifier == null) CommandModifier.NONE else CommandModifier.BANG
    val commandLine = ctx.commandArgumentWithBars()?.text ?: ""

    // Special case for `:k{mark}`, with no whitespace between command and argument. The `:k` command (shorthand for
    // `:mark`) is already recognised and handled as a command. That parser rule allows optional whitespace, so
    // `:k{mark}` should work. However, the catch-all "other" rule is greedier and accepts a command name starting with
    // `k` and containing alpha marks, so the rest of the name is passed as an argument here. (Note that the whitespace
    // between end of command name and what the parser thinks of as the argument isn't necessarily correct, but this is
    // an error anyway)
    //
    // `k` is no longer the only command that starts with one, which is why the registry is asked first: this used to
    // run before the lookup, and `:keepjumps {cmd}` therefore set a mark named `e` and never ran the command - with no
    // error, because setting a mark is a perfectly good thing to have done. Vim resolves the same way round.
    if (name.startsWith("k") && findExCommand(name) == null) {
      // Looked up as `:k`, which is what the table is keyed by - the mark name is part of the parsed command name
      val (argument, nextCommand) = splitOffNextCommand("k", commandLine)
      val command = MarkCommand(range, CommandModifier.NONE, name.substring(1) + " " + argument)
      command.nextCommand = nextCommand
      command.rangeInScript = ctx.getTextRange()
      return command
    }

    // A bang is part of the parsed command name, not `bangModifier` - `commandName` is left-recursive over BANG, and
    // ANTLR's left recursion is greedy, so it always wins. That is what user-defined aliases need, because they are
    // looked up with the bang attached (see VimCommandGroupBase.getAliasName), but it means a command registered with
    // `@ExCommand` would never see one. Retry the lookup without the bang, so that e.g. `:stopinsert!` resolves.
    // Retrying only on failure keeps the alias path untouched. There is no ambiguity: aliases must start with an
    // uppercase letter, and ex-command names are lowercase.
    var exCommand = findExCommand(name)
    var commandModifier = modifier
    var lookupName = name
    if (exCommand == null && name.endsWith("!")) {
      lookupName = name.dropLast(1)
      exCommand = findExCommand(lookupName)
      commandModifier = CommandModifier.BANG
    }

    // The table is keyed by the name alone, so the bang comes off before the bar handling is read. An unregistered
    // name - a user-defined command - takes the `|` as part of its argument, because `:command` has no `-bar` here.
    val (argument, nextCommand) = splitOffNextCommand(lookupName, commandLine)

    // Note that the fallback keeps the original name and modifier, so alias resolution still sees the bang
    val command = exCommand?.create(range, commandModifier, argument)
      ?: UnknownCommand(range, name, modifier, argument)
    command.nextCommand = nextCommand
    command.rangeInScript = ctx.getTextRange()
    return command
  }

  /**
   * Splits a command line into the argument of its first command and the rest of the line. See [Command.nextCommand].
   *
   * Each command declares how a `|` in its argument is treated in its `@ExCommand` annotation, which reaches here
   * through the generated command table - the job Vim's `TRLBAR` flag does. A command the table does not know is a
   * user-defined one, and takes the `|` as part of its argument, since `:command` supports no `-bar`.
   */
  private fun splitOffNextCommand(commandName: String, commandLine: String): Pair<String, String> {
    val exCommand = injector.vimscriptParser.exCommands.getCommand(commandName)
    val delimitedSections = exCommand?.delimitedSections ?: 0

    val endOfArgument = when {
      delimitedSections > 0 -> findEndOfDelimitedSections(commandLine, delimitedSections)
      exCommand?.barSeparates == true -> 0
      else -> commandLine.length
    }

    val separator = indexOfCommandSeparator(commandLine, endOfArgument)
    if (separator == -1) return commandLine to ""
    return commandLine.substring(0, separator) to commandLine.substring(separator + 1)
  }

  /**
   * Finds the index just after the [count] delimiter-wrapped sections of [commandLine], such as the pattern and the
   * replacement of `:s/pat/sub/flags`, or the single pattern of `:sort /pat/ u`.
   *
   * An unescaped `|` inside a section belongs to the argument, so the result is the first index at which a `|` can
   * separate commands. The delimiter is whatever character the user chose, and it need not come first: `:sort u /pat/`
   * is as valid as `:sort /pat/ u`, and `:s` can be given flags with no pattern at all.
   */
  private fun findEndOfDelimitedSections(commandLine: String, count: Int): Int {
    var i = indexAfterFlagsAndCount(commandLine)
    if (i == commandLine.length || commandLine[i] == '|' || commandLine[i] == '"') return i

    val delimiter: Char
    if (commandLine[i] == '\\') {
      // Undocumented vi feature: `\/` and `\?` reuse the last search pattern and `\&` the last substitute pattern,
      // so the command holds one section fewer and the delimiter is the second character
      val escaped = commandLine.getOrNull(i + 1)
      if (escaped == null || escaped !in "/?&") return i
      delimiter = escaped
      i += 2
    } else {
      delimiter = commandLine[i]
      i = injector.searchGroup.findEndOfPattern(commandLine, delimiter, i + 1)
      if (i == commandLine.length) return commandLine.length
      i++
    }

    repeat(count - 1) { i = indexAfterSection(commandLine, delimiter, i) }
    return i
  }

  /** The index of the first character of [commandLine] that is neither a flag nor a count, and so may be a delimiter */
  private fun indexAfterFlagsAndCount(commandLine: String): Int {
    var i = 0
    while (i < commandLine.length && (commandLine[i].isLetterOrDigit() || commandLine[i].isWhitespace())) i++
    return i
  }

  /**
   * The index just after the [delimiter] that closes a plain text section, or the end of [commandLine] if it is never
   * closed. A `\` escapes the next character, as in Vim's own scan of a replacement.
   */
  private fun indexAfterSection(commandLine: String, delimiter: Char, startIndex: Int): Int {
    var i = startIndex
    while (i < commandLine.length) {
      when {
        commandLine[i] == delimiter -> return i + 1
        commandLine[i] == '\\' && i + 1 < commandLine.length -> i += 2
        else -> i++
      }
    }
    return commandLine.length
  }

  /**
   * Finds the first `|` at or after [startIndex] that separates commands, or `-1` if there is none.
   *
   * A `|` is escaped, and part of the argument, when the character right before it is a `\` or a CTRL-V - the test
   * Vim makes in `separate_nextcmd`, and the one the lexer's `ESCAPED_BAR` token makes. The escape is not itself
   * escapable: in `\\|` it is the second `\` that escapes the bar, as in `:set langmap=\\|a`.
   */
  private fun indexOfCommandSeparator(commandLine: String, startIndex: Int): Int {
    for (i in startIndex until commandLine.length) {
      if (commandLine[i] != '|') continue
      val escape = commandLine.getOrNull(i - 1)
      if (escape != '\\' && escape != CTRL_V) return i
    }
    return -1
  }

  /**
   * The registered ex-command for [commandName], if it can be built from the standard
   * `(Range, CommandModifier, String)` constructor.
   *
   * Returns null for an unregistered name, matching the old behaviour: that fell back to
   * `UnknownCommand::class`, which has no such constructor. `ExCommandConstructorInvariantsTest`
   * pins that.
   */
  private fun findExCommand(commandName: String): LazyExCommandInstance? =
    injector.vimscriptParser.exCommands.getCommand(commandName)?.takeIf { it.hasStandardConstructor }

  private fun getCommandByName(commandName: String): KClass<out Command> {
    return injector.vimscriptParser.exCommands.getCommand(commandName)?.getKClass() ?: UnknownCommand::class
  }
}
