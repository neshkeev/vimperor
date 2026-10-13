/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.help

import com.maddyhome.idea.vim.action.change.LazyVimCommand
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode

/**
 * The help file `:help` opens, rendered from the commands this host has registered.
 *
 * Every command describes itself in its `@CommandOrMotion` or `@ExCommand` annotation, those descriptions reach
 * runtime through the generated registries, and this lays them out in the format of Vim's own help files: a `*tag*`
 * marks the place `:help tag` jumps to, and `|tag|` links to it. Ported from IdeaVim, which renders the same file.
 *
 * **Rendered when it is asked for rather than generated into a resource, and that is the one real difference.**
 * IdeaVim writes `help/ideavim.txt` with a Gradle task, keeps it in the repository and has a workflow to regenerate it
 * when a description changes. This fork cannot read a classpath resource - `commonMain` compiles for JS, where there
 * is no classpath - and it deleted its workflows. Rendering from the live registries removes all of that: there is no
 * file to commit, nothing to keep in step, and the help cannot drift from the commands. It also leaves one copy of
 * [keysToTag] rather than two, where upstream has to keep the generator's copy and the command's in agreement by hand.
 *
 * What a user sees is a list of what *this* host implements, which is the point: a command missing from it is a
 * command Vimperor does not have.
 */
object HelpText {

  /** The width Vim's own help files are wrapped to. */
  private const val TEXT_WIDTH = 78

  /** Where a description starts, so the keys column lines up down the page. */
  private const val DESCRIPTION_COLUMN = 24

  /**
   * A part of the help file.
   *
   * A command appears in every section whose modes it is available in, except that Visual mode only lists the commands
   * that are not in Normal mode already - which is how Vim's `index.txt` is organised, and keeps `d` from being listed
   * twice.
   */
  private enum class Section(val title: String, val tag: String, val tagPrefix: String) {
    INSERT("Insert mode", "vimperor-insert", "i_"),
    NORMAL("Normal and Operator-pending mode", "vimperor-normal", ""),
    VISUAL("Visual and Select mode", "vimperor-visual", "v_"),
    CMDLINE("Command-line editing", "vimperor-cmdline", "c_"),
    EX("Ex commands", "vimperor-ex", ":"),
  }

  private class Entry(
    val display: String,
    val tags: List<String>,
    val description: String,
    val sortKey: String,
  )

  /**
   * The rendered file, built once.
   *
   * The registries are filled when the host starts and do not change afterwards - a mapping is not a builtin command -
   * so there is nothing to invalidate. `resetForTest` exists for the suite, which builds more than one host per run.
   */
  private var rendered: String? = null

  val text: String
    get() = rendered ?: render().also { rendered = it }

  /** Forgets the rendered text, for a test that registers a different set of commands. */
  fun resetForTest() {
    rendered = null
  }

  private fun render(): String {
    val entries = Section.entries.associateWith { mutableListOf<Entry>() }

    for (command in registeredCommands()) {
      if (command.description.isEmpty()) continue
      val keys = command.keys.map { injector.parser.toKeyNotation(it) }
      for (section in sectionsOf(command.modes)) {
        entries.getValue(section).add(
          Entry(
            display = keys.joinToString(" "),
            tags = keys.map { section.tagPrefix + keysToTag(it) },
            description = command.description,
            sortKey = keys.first().lowercase(),
          ),
        )
      }
    }

    for (command in injector.vimscriptParser.exCommands.allCommands()) {
      val description = command.instance.description
      if (description.isEmpty()) continue
      val names = listOf(command.abbreviation, command.fullCommand).distinct()
      entries.getValue(Section.EX).add(
        Entry(
          display = ":" + command.fullCommand,
          tags = names.map { ":" + escapeTagChars(it) },
          description = description,
          sortKey = command.fullCommand.lowercase(),
        ),
      )
    }

    return buildString {
      appendHeader()
      // A tag must lead to one place, so when two commands claim it the first one keeps it. Different handlers of the
      // same keys - one per mode - are common, and they usually share a description.
      val usedTags = mutableSetOf<String>()
      Section.entries.forEachIndexed { index, section ->
        append("=".repeat(TEXT_WIDTH)).append('\n')
        append(alignRight("${index + 1}. ${section.title}", "*${section.tag}*")).append('\n')
        append('\n')
        val sectionEntries = entries.getValue(section).distinctBy { it.display to it.description }
        for (entry in sectionEntries.sortedWith(compareBy({ it.sortKey }, { it.display }))) {
          appendEntry(entry, entry.tags.distinct().filter { usedTags.add(it) })
        }
      }
      append("=".repeat(TEXT_WIDTH)).append('\n')
      append('\n')
      append("This file is rendered from Vimperor's own command list every time `:help` is opened.\n")
      append("There is nothing to edit here - edit the description of the command instead.\n")
      append('\n')
      append(" vim:tw=78:ts=8:noet:ft=help:norl:\n")
    }
  }

  /** Every builtin command this host registered, once each, however many modes it is bound in. */
  private fun registeredCommands(): List<LazyVimCommand> =
    MappingMode.entries
      .flatMap { mode -> injector.keyGroup.getBuiltinCommandsTrie(mode).getEntries().mapNotNull { it.data } }
      .distinct()

  private fun sectionsOf(modes: Set<MappingMode>): List<Section> = buildList {
    if (MappingMode.INSERT in modes) add(Section.INSERT)
    if (MappingMode.NORMAL in modes || MappingMode.OP_PENDING in modes) {
      add(Section.NORMAL)
    } else if (MappingMode.VISUAL in modes || MappingMode.SELECT in modes) {
      add(Section.VISUAL)
    }
    if (MappingMode.CMD_LINE in modes) add(Section.CMDLINE)
  }

  private fun StringBuilder.appendHeader() {
    append(alignRight("*vimperor.txt*", "*vimperor*")).append('\n')
    append('\n')
    append(" ".repeat((TEXT_WIDTH - 17) / 2)).append("VIMPEROR COMMANDS\n")
    append('\n')
    append(
      wrap(
        "This file lists the commands Vimperor implements. Type \":help\" followed by a subject " +
          "to jump to it, for example:",
        0,
      ),
    )
    append('\n')
    append("    :help x         Normal mode command \"x\"\n")
    append("    :help v_d       Visual mode command \"d\"\n")
    append("    :help i_CTRL-W  Insert mode command CTRL-W\n")
    append("    :help c_CTRL-R  Command-line editing command CTRL-R\n")
    append("    :help :s        Ex command \":s\"\n")
    append('\n')
    append("Contents:\n")
    Section.entries.forEachIndexed { index, section ->
      append(alignRight("${index + 1}. ${section.title}", "|${section.tag}|")).append('\n')
    }
    append('\n')
  }

  private fun StringBuilder.appendEntry(entry: Entry, tags: List<String>) {
    for (tagLine in tagLines(tags)) {
      append(" ".repeat(TEXT_WIDTH - tagLine.length)).append(tagLine).append('\n')
    }
    val description = wrap(entry.description, DESCRIPTION_COLUMN).trimStart()
    if (entry.display.length < DESCRIPTION_COLUMN - 1) {
      append(entry.display.padEnd(DESCRIPTION_COLUMN)).append(description)
    } else {
      append(wrap(entry.display, 0))
      append(" ".repeat(DESCRIPTION_COLUMN)).append(description)
    }
    append('\n')
  }

  /** Splits the tags into right-aligned lines that fit the text width. */
  private fun tagLines(tags: List<String>): List<String> {
    val lines = mutableListOf<String>()
    var line = ""
    for (tag in tags.map { "*$it*" }) {
      line = when {
        line.isEmpty() -> tag
        line.length + 1 + tag.length <= TEXT_WIDTH -> "$line $tag"
        else -> {
          lines.add(line)
          tag
        }
      }
    }
    if (line.isNotEmpty()) lines.add(line)
    return lines
  }

  private fun alignRight(left: String, right: String): String =
    left + " ".repeat((TEXT_WIDTH - left.length - right.length).coerceAtLeast(1)) + right

  /** Wraps [text] to the text width, indenting every line by [indent] spaces. Ends with a line break. */
  private fun wrap(text: String, indent: Int): String = buildString {
    var line = StringBuilder()
    for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
      if (line.isNotEmpty() && indent + line.length + 1 + word.length > TEXT_WIDTH) {
        append(" ".repeat(indent)).append(line).append('\n')
        line = StringBuilder()
      }
      if (line.isNotEmpty()) line.append(' ')
      line.append(word)
    }
    if (line.isNotEmpty()) append(" ".repeat(indent)).append(line).append('\n')
  }

  // ---- tags, and finding one ---------------------------------------------------------------------

  private val TAG = Regex("""(?<=^|\s)\*([^*\s|]+)\*(?=\s|$)""")
  private val MODE_PREFIX = Regex("^[a-z]_")
  private val CARET_NOTATION = Regex("""\^(\S)""")

  /** The line each tag is on, in the order the tags appear. */
  private val tags: Map<String, Int>
    get() {
      val found = LinkedHashMap<String, Int>()
      text.lines().forEachIndexed { index, line ->
        // `putIfAbsent` is JVM-only; the first tag of a name is the one that counts
        for (match in TAG.findAll(line)) found.getOrPut(match.groupValues[1]) { index }
      }
      return found
    }

  /**
   * The line of the tag that matches [subject] best, or null when nothing does.
   *
   * Vim ranks every tag matching [subject] as a pattern. This is simpler and gives the same answer for the usual
   * cases: an exact match, then the Ex command of that name (`:help help` finds `:help`), then a match ignoring case,
   * then the shortest tag starting with [subject].
   *
   * A doubled operator like `dd` or `gUU` has no command of its own - the operator handles it - so it leads to the
   * operator.
   */
  fun findTag(subject: String): Int? {
    val known = tags
    val operator = subject.dropLast(1)
    if (subject.length > 1 && subject.last() == operator.last() && subject !in known) {
      known[operator]?.let { return it }
    }
    for (candidate in candidates(subject)) {
      known[candidate]?.let { return it }
      if (!candidate.startsWith(":")) known[":$candidate"]?.let { return it }
    }
    for (candidate in candidates(subject)) {
      known.entries.firstOrNull { it.key.equals(candidate, ignoreCase = true) }?.let { return it.value }
    }
    for (candidate in candidates(subject)) {
      known.entries.filter { it.key.startsWith(candidate) }.minByOrNull { it.key.length }?.let { return it.value }
    }
    return null
  }

  /**
   * The ways to read [subject] as a tag.
   *
   * Keys can be written as they are in a mapping (`<C-W>h`) or in caret notation (`^Wh`) and both mean `CTRL-W_h`. But
   * `^` can also be a key of its own, as in `g^`, so the subject is tried without the caret reading too.
   */
  private fun candidates(subject: String): List<String> {
    val prefix = MODE_PREFIX.find(subject)?.value?.takeIf { it.length < subject.length } ?: ""
    val keys = subject.substring(prefix.length)
    val caretNotation = CARET_NOTATION.replace(keys) { "<C-${it.groupValues[1]}>" }
    return listOf(prefix + keysToTag(caretNotation), prefix + keysToTag(keys)).distinct()
  }

  /**
   * Keys in Vim's key notation, as a tag in the help's: `<C-W>h` becomes `CTRL-W_h` and `g*` becomes `gstar`.
   *
   * One copy, used both to write the tags and to read what the user typed after `:help`. Upstream has two - the
   * generator's and the command's - and a comment on each asking that they be kept in agreement.
   */
  fun keysToTag(keys: String): String {
    val parts = mutableListOf<String>()
    var i = 0
    while (i < keys.length) {
      val close = if (keys[i] == '<') keys.indexOf('>', i + 1) else -1
      if (close > i + 1) {
        parts.add(keys.substring(i, close + 1))
        i = close + 1
      } else {
        parts.add(keys[i].toString())
        i++
      }
    }
    return buildString {
      var previousIsCtrl = false
      for (part in parts) {
        val ctrl = ctrlKey(part)
        if (isNotEmpty() && (ctrl != null || previousIsCtrl)) append('_')
        append(ctrl ?: escapeTagChars(part))
        previousIsCtrl = ctrl != null
      }
    }
  }

  /** `<C-W>` is `CTRL-W` in Vim's help; `<C-Left>` stays as it is. */
  private fun ctrlKey(part: String): String? {
    if (part.length != 5 || !part.startsWith("<C-", ignoreCase = true) || !part.endsWith('>')) return null
    return "CTRL-" + part[3].uppercaseChar()
  }

  /** `*` and `|` delimit a tag and a link, so they cannot be inside one. */
  private fun escapeTagChars(text: String): String = text.replace("*", "star").replace("|", "bar")
}
