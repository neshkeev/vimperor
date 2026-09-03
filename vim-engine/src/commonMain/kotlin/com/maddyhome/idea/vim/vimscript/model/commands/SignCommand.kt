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
import com.maddyhome.idea.vim.directory.WorkingDirectory
import com.maddyhome.idea.vim.ex.exExceptionMessage
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.sign.Signs
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * `:sign` - a mark in the gutter, and the six things you can do to one.
 *
 * Vim keeps definitions and placements apart and so does this: `:sign define` says what a mark looks
 * like, `:sign place` says where one is, and one definition serves any number of placements. That
 * split is the reason `:sign` is what plugins use to draw a column of git marks or lint errors
 * without inventing a mark per line.
 *
 * ```
 * :sign define {name} text=.. texthl=.. linehl=.. numhl=.. culhl=.. icon=.. priority=..
 * :sign undefine {name}
 * :sign list [{name}]
 * :sign place [{id}] line={lnum} name={name} [group={g}] [priority={p}] [file={f} | buffer={n}]
 * :sign place [{id}] name={name} [group={g}] [file={f} | buffer={n}]     move one that exists
 * :sign place [group={g}] [file={f} | buffer={n}]                        list
 * :sign unplace {id}|* [group={g}|*] [file={f} | buffer={n}]
 * :sign jump {id} [group={g}] [file={f} | buffer={n}]
 * ```
 *
 * A placement belongs to a *path*, not to a buffer number. Vim accepts either and this fork has no
 * buffers of its own - `:ls` numbers what the host has open, and those numbers shift when a tab
 * closes, so a sign that remembered one would end up on a different file. `buffer={nr}` is still
 * accepted and is resolved through that same list to the file it names, which is `E158` when the
 * number is not one of them.
 *
 * The highlight groups are the ones `:highlight` defines. A `texthl=Search` in a session that never
 * defined `Search` still paints, in the host's own find colour - the same bargain `:match` makes.
 *
 * see "h :sign"
 */
@ExCommand(command = "sign")
data class SignCommand(val range: Range, val modifier: CommandModifier, val argument: String) :
  Command.SingleExecution(range, modifier, argument) {

  override val argFlags: CommandHandlerFlags =
    flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  override fun processCommand(
    editor: VimEditor,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ): ExecutionResult {
    val tokens = commandArgument.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) throw exExceptionMessage("E471")

    val subcommand = tokens.first().lowercase()
    val rest = tokens.drop(1)
    when (subcommand) {
      "define" -> define(rest)
      "undefine" -> undefine(rest)
      "list" -> list(rest, editor, context)
      "place" -> place(rest, editor, context)
      "unplace" -> unplace(rest, editor, context)
      "jump" -> jump(rest, editor, context)
      else -> throw exExceptionMessage("E160", subcommand)
    }

    Signs.repaint(editor)
    return ExecutionResult.Success
  }

  // ---- define, undefine, list --------------------------------------------------------------------

  private fun define(arguments: List<String>) {
    val name = arguments.firstOrNull() ?: throw exExceptionMessage("E156")
    if ('=' in name) throw exExceptionMessage("E156")
    val settings = keyValues(arguments.drop(1))

    Signs.define(name) { existing ->
      var result = existing
      for ((key, value) in settings) {
        result = when (key) {
          "text" -> result.copy(text = validText(value))
          "texthl" -> result.copy(textHighlight = value)
          "linehl" -> result.copy(lineHighlight = value)
          "numhl" -> result.copy(numberHighlight = value)
          "culhl" -> result.copy(cursorLineHighlight = value)
          "icon" -> result.copy(icon = value)
          "priority" -> result.copy(priority = value.toIntOrNull() ?: throw exExceptionMessage("E487", value))
          else -> throw exExceptionMessage("E475", "$key=$value")
        }
      }
      result
    }
  }

  /**
   * Vim: "only printable characters are allowed and they must occupy one or two display cells".
   *
   * The second half is checked as a length rather than as a width, because a display cell is a
   * question about a font and neither host is asked one here. A two-cell CJK character therefore
   * gets through where Vim would count it as two - which shows a wider sign, not a wrong one.
   */
  private fun validText(text: String): String {
    if (text.isEmpty() || text.length > 2 || text.any { it.isISOControl() }) {
      throw exExceptionMessage("E239", text)
    }
    return text
  }

  private fun undefine(arguments: List<String>) {
    val name = arguments.firstOrNull() ?: throw exExceptionMessage("E156")
    if (!Signs.undefine(name)) throw exExceptionMessage("E155", name)
  }

  private fun list(arguments: List<String>, editor: VimEditor, context: ExecutionContext) {
    val wanted = arguments.firstOrNull()
    val definitions = if (wanted == null) {
      Signs.definitions()
    } else {
      listOf(Signs.definition(wanted) ?: throw exExceptionMessage("E155", wanted))
    }

    val text = if (definitions.isEmpty()) {
      "No signs defined."
    } else {
      definitions.joinToString("\n") { definition ->
        buildString {
          append("sign ").append(definition.name)
          definition.text?.let { append(" text=").append(it) }
          definition.textHighlight?.let { append(" texthl=").append(it) }
          definition.lineHighlight?.let { append(" linehl=").append(it) }
          definition.numberHighlight?.let { append(" numhl=").append(it) }
          definition.cursorLineHighlight?.let { append(" culhl=").append(it) }
          definition.icon?.let { append(" icon=").append(it) }
          if (definition.priority != Signs.DEFAULT_PRIORITY) append(" priority=").append(definition.priority)
        }
      }
    }
    injector.outputPanel.output(editor, context, text + "\n")
  }

  // ---- place, unplace, jump ----------------------------------------------------------------------

  private fun place(arguments: List<String>, editor: VimEditor, context: ExecutionContext) {
    val id = arguments.firstOrNull()?.takeIf { '=' !in it }
    val settings = keyValues(arguments.drop(if (id == null) 0 else 1))
    val group = settings["group"] ?: Signs.GLOBAL_GROUP

    // With no id and no name, this is the listing form: `:sign place`, `:sign place file=x`.
    if (id == null && "name" !in settings) {
      listPlaced(settings, group, editor, context)
      return
    }

    val number = id?.toIntOrNull() ?: Signs.nextId()
    if (id != null && number <= 0) throw exExceptionMessage("E157", id)
    val name = settings["name"] ?: throw exExceptionMessage("E156")
    val definition = Signs.definition(name) ?: throw exExceptionMessage("E155", name)
    val path = pathFrom(settings, editor, context)

    // `:sign place {id} name={name} file={f}` with no `line=` moves an existing sign to a new
    // definition and leaves it where it is - which is why the old placement is looked for first.
    val line = settings["line"]?.let {
      it.toIntOrNull() ?: throw exExceptionMessage("E487", it)
    } ?: Signs.placed(path = path, group = group, id = number).firstOrNull()?.line
      ?: throw exExceptionMessage("E159")

    Signs.place(
      Signs.Placement(
        id = number,
        group = group,
        name = name,
        path = path,
        line = line,
        priority = settings["priority"]?.toIntOrNull() ?: definition.priority,
      ),
    )
  }

  private fun listPlaced(
    settings: Map<String, String>,
    group: String,
    editor: VimEditor,
    context: ExecutionContext,
  ) {
    // `group=*` means every group, which is not the same as the global group named "".
    val wantedGroup = if (settings["group"] == "*") null else group
    val wantedPath = if ("file" in settings || "buffer" in settings) pathFrom(settings, editor, context) else null
    val placed = Signs.placed(path = wantedPath, group = wantedGroup)

    val text = if (placed.isEmpty()) {
      "No signs placed."
    } else {
      placed.groupBy { it.path }.entries.joinToString("\n") { (path, signs) ->
        buildString {
          append("Signs for ").append(path).append(':')
          for (sign in signs) {
            append("\n    line=").append(sign.line)
            append("  id=").append(sign.id)
            if (sign.group.isNotEmpty()) append("  group=").append(sign.group)
            append("  name=").append(sign.name)
            append("  priority=").append(sign.priority)
          }
        }
      }
    }
    injector.outputPanel.output(editor, context, text + "\n")
  }

  private fun unplace(arguments: List<String>, editor: VimEditor, context: ExecutionContext) {
    val id = arguments.firstOrNull()?.takeIf { '=' !in it }
    val settings = keyValues(arguments.drop(if (id == null) 0 else 1))

    val everyId = id == null || id == "*"
    val number = if (everyId) null else id.toIntOrNull() ?: throw exExceptionMessage("E157", id)
    val group = when (settings["group"]) {
      null -> Signs.GLOBAL_GROUP
      "*" -> null
      else -> settings["group"]
    }
    // `:sign unplace *` with no file clears every file, which is what Vim's bare `*` means. A
    // bare `:sign unplace` without even a `*` is about this file only.
    val path = when {
      "file" in settings || "buffer" in settings -> pathFrom(settings, editor, context)
      id == "*" -> null
      else -> editor.getPath()
    }

    Signs.unplace(path = path, group = group, id = number)
  }

  private fun jump(arguments: List<String>, editor: VimEditor, context: ExecutionContext) {
    val id = arguments.firstOrNull()?.takeIf { '=' !in it } ?: throw exExceptionMessage("E157", "")
    val number = id.toIntOrNull() ?: throw exExceptionMessage("E157", id)
    val settings = keyValues(arguments.drop(1))
    val group = if (settings["group"] == "*") null else settings["group"] ?: Signs.GLOBAL_GROUP
    val path = if ("file" in settings || "buffer" in settings) pathFrom(settings, editor, context) else null

    val sign = Signs.placed(path = path, group = group, id = number).firstOrNull()
      ?: throw exExceptionMessage("E157", id)

    // Only a jump inside the current file is a jump this can make. Opening the other file is
    // `:edit`'s job and Vim's own `:sign jump` is documented to fail when it cannot get there.
    if (sign.path != editor.getPath()) throw exExceptionMessage("E158", sign.path)
    val caret = editor.currentCaret()
    caret.moveToOffset(injector.motion.moveCaretToLineStartSkipLeading(editor, (sign.line - 1).coerceAtLeast(0)))
  }

  // ---- shared parsing ----------------------------------------------------------------------------

  /** `key=value` pairs, in any order, which is how every `:sign` subcommand takes its arguments. */
  private fun keyValues(tokens: List<String>): Map<String, String> {
    val settings = mutableMapOf<String, String>()
    for (token in tokens) {
      val equals = token.indexOf('=')
      if (equals < 0) throw exExceptionMessage("E475", token)
      settings[token.take(equals).lowercase()] = token.drop(equals + 1)
    }
    return settings
  }

  /**
   * The file a placement belongs to: `file=`, `buffer=`, or the one the command was typed in.
   *
   * Vim says the file "must already be loaded in a buffer" and that its name is used exactly -
   * no wildcards, no `$ENV`, no `~`. The `~` part is relaxed here, because a `~` that reached the
   * host would be a path no editor has and the expansion is the engine's anyway; a working
   * directory set by `:cd` applies for the same reason it applies to `:edit`.
   */
  private fun pathFrom(settings: Map<String, String>, editor: VimEditor, context: ExecutionContext): String {
    settings["file"]?.let { return WorkingDirectory.resolve(injector.pathExpansion.expandPath(it), editor) }

    settings["buffer"]?.let { number ->
      val index = number.toIntOrNull() ?: throw exExceptionMessage("E158", number)
      val buffers = injector.file.getBuffers(context)
      val buffer = buffers.getOrNull(index - 1) ?: throw exExceptionMessage("E158", number)
      // The buffer list carries names rather than paths, so the path comes from the editor showing
      // it. A buffer with no editor open on it is one nothing can draw a sign in either.
      return injector.editorGroup.getEditors()
        .firstOrNull { it.getPath()?.substringAfterLast('/') == buffer.name }
        ?.getPath()
        ?: throw exExceptionMessage("E158", number)
    }

    return editor.getPath() ?: throw exExceptionMessage("E158", "")
  }

  private companion object {
    val WHITESPACE = Regex("\\s+")
  }
}
