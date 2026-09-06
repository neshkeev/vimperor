/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

/**
 * IdeaVim's own tests, read as data.
 *
 * The tests in this module are written by whoever wrote the code, which is the weakness they all
 * share: they encode what this port's author believed Vim does. IdeaVim has 7,499 tests that encode
 * what Vim *actually* does, checked against real Vim by people who have been arguing about it for
 * twenty years, and most of them are one call - `doTest(keys, before, after)` - with nothing
 * IntelliJ-shaped in it. Text in, keys, text out.
 *
 * So they are harvested rather than reimplemented. This walks the IntelliJ module's test sources,
 * finds the `doTest` calls it can evaluate without a Kotlin compiler, and hands back the triples.
 * [VimFixtureReplayTest] presses them against this host.
 *
 * The parsing is deliberately narrow. It understands ordinary and raw string literals and the calls
 * that follow them - `trimIndent`, `trimMargin`, `dotToSpace`, `dotToTab`, `repeat` - along with
 * `listOf` of strings, `"a" + "b"`, the `exCommand`/`searchCommand` helpers that are only string
 * building, `//` comments between the arguments, and IdeaVim's `${'$'}{c}`, `${'$'}{s}` and
 * `${'$'}{se}` markers. It refuses everything else, because a fixture it half-understands is worse
 * than one it skips. What it refuses is counted, so the yield is visible rather than assumed;
 * `VimFixtureReplayTest` writes those counts out with every run.
 */
internal data class VimFixture(
  /** Where it came from, which is the name in a failure and the key in the baseline. */
  val source: String,
  val keys: String,
  val before: String,
  /** Still carrying its markers: `<caret>`, and `<selection>`/`</selection>` when there is one. */
  val after: String,
  /** Ex commands the test runs before the keys, as `enterCommand("set ...")` in a trailing lambda. */
  val setup: List<String> = emptyList(),
)

internal object VimFixtures {

  const val CARET = "<caret>"

  /**
   * IdeaVim's selection markers, which is what `${'$'}{s}` and `${'$'}{se}` expand to over there.
   *
   * They only ever appear in the `after` of a fixture - 462 of them do, and not one `before` does -
   * so a fixture that has them is an ordinary one that additionally says where the selection ended
   * up. That is worth having: Visual mode is most of what this port had no outside check on.
   */
  const val SELECTION_START = "<selection>"
  const val SELECTION_END = "</selection>"

  /** Why a `doTest` was not harvested, counted so that a drop in yield is visible. */
  val skipped: MutableMap<String, Int> = mutableMapOf()

  fun load(repositoryRoot: String): List<VimFixture> {
    skipped.clear()
    val fixtures = mutableListOf<VimFixture>()
    for (path in kotlinFilesUnder("$repositoryRoot/src/test")) {
      // The extension tests need plugins this host has not ported. They would all fail, and they
      // would fail for a reason that is already written down.
      if (path.contains("/extension/")) continue
      val source = readText(path)
      // A file with its own `doTest` means something else entirely by the name - `GlobalCommandTest`
      // takes an ex command where `VimTestCase` takes keys - and reading those as keystrokes
      // produces nonsense that looks like a failure.
      if (source.contains("fun doTest(")) { skip("the file defines its own doTest"); continue }
      for ((method, body, at) in testMethods(source)) {
        // Comments come out before the arguments are split, not after. A comment is not just noise
        // in front of an argument: `// Move the line to below the current line, which ...` has a
        // comma in it, and splitting on commas first tears the call into the wrong pieces.
        val trimmed = stripLineComments(body).trim()
        if (!trimmed.startsWith("doTest(")) continue
        val spans = argumentSpans(trimmed, trimmed.indexOf('(')) ?: skip("could not parse the arguments")
          ?: continue

        // A trailing lambda is `afterEditorInitialized`, and it is nearly always one or more
        // `enterCommand("set ...")` calls - a fixture about an option, which is worth more than a
        // plain one because an option nothing reads is this port's quietest kind of failure. Those
        // are reproduced by typing the command. A lambda that does anything else is refused, since
        // replaying it without its setup would measure the harness rather than the host.
        val tail = trimmed.substring(spans.last().second + 1).trimStart()
        val setup = if (tail.startsWith("{")) enterCommands(tail) else emptyList()
        if (setup == null) { skip("the test sets something up this cannot repeat"); continue }

        // Annotations that mean the fixture is not the plain thing it looks like.
        val head = source.substring(maxOf(0, at - 400), at)
        if (head.contains("@OptionTest")) { skip("the test sets options"); continue }
        if (head.contains("@VimBehaviorDiffers")) { skip("IdeaVim knowingly differs from Vim here"); continue }
        // A test IdeaVim has switched off does not pass over there either, so holding this host to
        // it measures nothing. `search in one time from select mode` was recorded here as a bug in
        // this host for a long time - `caret expected [81], actual [82]` - and IdeaVim's caret is at
        // 82 as well; the fixture's own `@Disabled` says "Ctrl-o doesn't work yet in select mode".
        if (head.contains("@Disabled")) { skip("IdeaVim has the test disabled"); continue }
        if (spans.size < 3) { skip("fewer than three arguments"); continue }

        // A file type or name means the test is about a language, and this host has no parser.
        val extra = spans.drop(3).joinToString(" ") { trimmed.substring(it.first, it.second) }
        if (extra.contains("fileType") || extra.contains("fileName") || extra.contains("afterEditorInitialized")) {
          skip("the test needs a language"); continue
        }

        val values = spans.take(3).map { (from, to) -> evaluate(trimmed.substring(from, to).trim()) }
        if (values.any { it == null }) { skip("an argument this cannot evaluate"); continue }
        val withCarets = values.map { caretsIn(it!!) }
        if (withCarets.any { it == null }) { skip("string interpolation other than the caret"); continue }

        val (keys, before, after) = withCarets.map { it!! }
        // No caret at all is not a refusal: IdeaVim's `configureByText` puts one at the start when
        // the text does not say otherwise, so the fixture means offset zero. More than one is not a
        // refusal either any more, and it should never have been the interesting one to give up on:
        // multiple cursors are the feature VS Code is best known for, and every one of these
        // fixtures is IdeaVim saying what a multi-caret command should do. The replay sets them all
        // through VS Code's own selections, which is how a real one would arrive.
        if (before.contains(SELECTION_START)) { skip("the test starts with a selection"); continue }
        if (after.split(SELECTION_START).size != after.split(SELECTION_END).size) {
          skip("an unbalanced selection in the result"); continue
        }
        fixtures += VimFixture("${path.substringAfter("$repositoryRoot/")}:$method", keys, before, after, setup)
      }
    }
    return fixtures
  }

  /**
   * The ex commands in a trailing lambda, or null when it contains anything else.
   *
   * Only `enterCommand("...")` with a literal argument. Anything else - a register set up by hand,
   * an IntelliJ call - is something this cannot reproduce, and guessing would produce a failure
   * that says more about the harness than about the host.
   */
  private fun enterCommands(lambda: String): List<String>? {
    val body = lambda.removePrefix("{").substringBeforeLast("}").trim()
    if (body.isEmpty()) return emptyList()
    val commands = mutableListOf<String>()
    for (statement in body.split("\n")) {
      val line = statement.trim()
      if (line.isEmpty()) continue
      val match = ENTER_COMMAND.find(line) ?: return null
      commands += evaluate(match.groupValues[1]) ?: return null
    }
    return commands
  }

  private val ENTER_COMMAND = Regex("^enterCommand\\((\".*\")\\)$")

  private fun skip(reason: String): Nothing? {
    skipped[reason] = (skipped[reason] ?: 0) + 1
    return null
  }

  /**
   * `${'$'}{c}` and `${'$'}c` are the caret; any other interpolation means the fixture depends on
   * something only the compiler knows, and it is refused rather than guessed at.
   */
  private fun caretsIn(value: String): String? {
    val substituted = value
      .replace(Regex("\\\$\\{c\\}"), CARET)
      .replace(Regex("\\\$c(?![A-Za-z0-9_])"), CARET)
      .replace(Regex("\\\$\\{se\\}"), SELECTION_END)
      .replace(Regex("\\\$\\{s\\}"), SELECTION_START)
    return if (Regex("\\\$\\{|\\\$[A-Za-z_]").containsMatchIn(substituted)) null else substituted
  }

  /** One string-valued Kotlin expression, or null when it is anything this does not understand. */
  private fun evaluate(expression: String): String? {
    if (expression.startsWith("listOf(")) {
      val parts = argumentSpans(expression, expression.indexOf('(')) ?: return null
      val pieces = parts.map { (from, to) -> evaluate(expression.substring(from, to).trim()) }
      if (pieces.any { it == null }) return null
      return pieces.joinToString("") { it!! }
    }
    // Two helpers off `VimTestCase` that are only string building, and are used where a fixture
    // would otherwise be an ordinary one: `exCommand("copy .")` is `":copy .<CR>"` and nothing more.
    for ((helper, wrap) in HELPERS) {
      if (!expression.startsWith("$helper(")) continue
      val parts = argumentSpans(expression, expression.indexOf('(')) ?: return null
      if (parts.size != 1) return null
      if (parts.last().second + 1 != expression.length) return null
      return wrap(evaluate(expression.substring(parts[0].first, parts[0].second).trim()) ?: return null)
    }
    // `"<a>\\n" + "  ${'$'}{c}<b>\\n" + ...`, which is how the tag-object fixtures are written -
    // one literal per line of the document, because a raw string cannot carry the escapes they need.
    val builder = StringBuilder()
    var index = 0
    while (true) {
      val (value, end) = readString(expression, index) ?: return null
      builder.append(value)
      index = end
      val rest = expression.substring(index).trimStart()
      if (rest.isEmpty()) return builder.toString()
      if (!rest.startsWith("+")) return null
      index = expression.length - rest.length + 1
      index += expression.substring(index).takeWhile { it.isWhitespace() }.length
    }
  }

  /** A string literal and the `trimIndent`/`dotToSpace` calls that so often follow one. */
  private fun readString(text: String, start: Int): Pair<String, Int>? {
    var value: String
    var index: Int
    if (text.startsWith("\"\"\"", start)) {
      val end = text.indexOf("\"\"\"", start + 3)
      if (end < 0) return null
      value = text.substring(start + 3, end)
      index = end + 3
    } else if (text.getOrNull(start) == '"') {
      val builder = StringBuilder()
      var at = start + 1
      while (at < text.length) {
        val character = text[at]
        if (character == '\\') {
          val escaped = text.getOrNull(at + 1) ?: return null
          builder.append(
            when (escaped) {
              'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
              '\\' -> '\\'; '"' -> '"'; '$' -> '$'; '\'' -> '\''
              // `\u3002` is a full stop in Japanese, and a word-motion fixture turns on it being
              // one character rather than six.
              'u' -> text.substring(at + 2, minOf(at + 6, text.length))
                .takeIf { it.length == 4 }?.toIntOrNull(16)?.toChar() ?: return null
              else -> return null
            },
          )
          at += if (escaped == 'u') 6 else 2
          continue
        }
        if (character == '"') { at++; break }
        builder.append(character)
        at++
      }
      value = builder.toString()
      index = at
    } else {
      return null
    }

    while (true) {
      val suffix = SUFFIX.find(text.substring(index)) ?: break
      value = when (suffix.groupValues[1]) {
        "trimIndent" -> trimIndent(value)
        "trimMargin" -> trimMargin(value, suffix.groupValues[2].ifEmpty { "|" })
        "dotToSpace" -> value.replace('.', ' ')
        "dotToTab" -> value.replace('.', '\t')
        else -> value.repeat(suffix.groupValues[3].toInt())
      }
      index += suffix.value.length
    }
    return value to index
  }

  /**
   * The calls that so often follow a string literal in these tests, and what they take.
   *
   * `trimMargin` is worth more than the rest together: 318 of the fixtures are written with it
   * rather than `trimIndent`, and it was the single largest thing this could not read.
   */
  private val SUFFIX =
    Regex("""^\s*\.(trimIndent|trimMargin|dotToSpace|dotToTab|repeat)\((?:"([^"]*)"|(\d+))?\)""")

  private val HELPERS: List<Pair<String, (String) -> String>> = listOf(
    "exCommand" to { command: String -> ":$command<CR>" },
    "searchCommand" to { pattern: String -> "$pattern<CR>" },
  )

  /**
   * The source with its `//` comments removed, leaving string literals alone.
   *
   * `"http://x"` is not a comment, and a comment is not always harmless: a fixture's arguments are
   * split on the commas between them, so a comment containing a comma splits an argument in half.
   */
  private fun stripLineComments(source: String): String {
    val builder = StringBuilder()
    var index = 0
    while (index < source.length) {
      val character = source[index]
      if (character == '"') {
        val end = skipString(source, index) ?: return source
        builder.append(source, index, end)
        index = end
        continue
      }
      if (character == '/' && source.getOrNull(index + 1) == '/') {
        while (index < source.length && source[index] != '\n') index++
        continue
      }
      builder.append(character)
      index++
    }
    return builder.toString()
  }

  /**
   * Kotlin's own `trimMargin`, which is how 318 of these fixtures are written.
   *
   * Not the same rule as `trimIndent`, and the difference matters here: a line whose first
   * non-blank run does not start with the margin prefix is kept exactly as it was, prefix and all.
   * That is what makes it the one IdeaVim reaches for when the text under test has its own leading
   * whitespace - which is most of the indentation fixtures.
   */
  private fun trimMargin(value: String, prefix: String): String {
    val lines = value.split("\n")
    return lines.mapIndexedNotNull { index, line ->
      if ((index == 0 || index == lines.lastIndex) && line.isBlank()) return@mapIndexedNotNull null
      val firstNonBlank = line.indexOfFirst { !it.isWhitespace() }
      if (firstNonBlank >= 0 && line.startsWith(prefix, firstNonBlank)) {
        line.substring(firstNonBlank + prefix.length)
      } else {
        line
      }
    }.joinToString("\n")
  }

  /** Kotlin's own `trimIndent`, over text this has read out of a source file rather than compiled. */
  private fun trimIndent(value: String): String {
    var lines = value.split("\n")
    if (lines.firstOrNull()?.isBlank() == true) lines = lines.drop(1)
    if (lines.lastOrNull()?.isBlank() == true) lines = lines.dropLast(1)
    val indent = lines.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
    return lines.joinToString("\n") { if (it.isBlank()) "" else it.substring(indent) }
  }

  /** The top-level argument spans of a call whose opening bracket is at [open]. */
  private fun argumentSpans(text: String, open: Int): List<Pair<Int, Int>>? {
    if (text.getOrNull(open) != '(') return null
    val spans = mutableListOf<Pair<Int, Int>>()
    var depth = 0
    var start = open + 1
    var index = open
    while (index < text.length) {
      val character = text[index]
      if (character == '"') {
        index = skipString(text, index) ?: return null
        continue
      }
      when {
        character == '(' || character == '[' || character == '{' -> depth++
        character == ')' || character == ']' || character == '}' -> {
          depth--
          if (depth == 0) { spans += start to index; return spans }
        }
        character == ',' && depth == 1 -> { spans += start to index; start = index + 1 }
      }
      index++
    }
    return null
  }

  private fun skipString(text: String, start: Int): Int? {
    if (text.startsWith("\"\"\"", start)) {
      val end = text.indexOf("\"\"\"", start + 3)
      return if (end < 0) null else end + 3
    }
    var index = start + 1
    while (index < text.length) {
      if (text[index] == '\\') { index += 2; continue }
      if (text[index] == '"') return index + 1
      index++
    }
    return null
  }

  /** Every `fun \`test ...\`()` body, found by matching braces rather than by a regex over them. */
  private fun testMethods(source: String): List<Triple<String, String, Int>> {
    val found = mutableListOf<Triple<String, String, Int>>()
    for (match in TEST_METHOD.findAll(source)) {
      val open = match.range.last
      var depth = 0
      var index = open
      while (index < source.length) {
        val character = source[index]
        if (character == '"') {
          index = skipString(source, index) ?: break
          continue
        }
        if (character == '{') depth++
        if (character == '}') {
          depth--
          if (depth == 0) {
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            found += Triple(name, source.substring(open + 1, index), match.range.first)
            break
          }
        }
        index++
      }
    }
    return found
  }

  /**
   * A test method's name, backticked or not.
   *
   * IdeaVim writes most of them backticked and 578 of them not - `fun testSurroundWordParens()` -
   * and this only ever looked for the first kind, so those methods were invisible rather than
   * refused. They did not appear in the skip counts either, which is why the yield looked higher
   * than it was: the harness was measuring what it could parse out of what it could see.
   */
  private val TEST_METHOD = Regex("""fun\s+(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))\s*\([^)]*\)\s*\{""")
}
