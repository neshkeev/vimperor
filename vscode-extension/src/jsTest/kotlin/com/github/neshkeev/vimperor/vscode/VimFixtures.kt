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
      val source = readText(path)
      // A file with its own `doTest` means something else entirely by the name - `GlobalCommandTest`
      // takes an ex command where `VimTestCase` takes keys - and reading those as keystrokes
      // produces nonsense that looks like a failure.
      if (source.contains("fun doTest(")) { skip("the file defines its own doTest"); continue }
      // What the class does for every test in it. Nearly always `enableExtensions("surround")` in
      // `setUp`, which is the only reason the extension tests could not be read: it is a statement
      // in a *different method*, and the harness only ever looked at the one it was in.
      val setUpBody = testMethods(source).firstOrNull { it.first == "setUp" }?.second
      val fromSetUp = if (setUpBody == null) emptyList<String>() to emptyList<String>() else {
        setUpCommands(stripLineComments(setUpBody))
      }
      val fileSetup = fromSetUp?.first
      val fileExtensions = fromSetUp?.second ?: emptyList()

      for ((method, body, at) in testMethods(source)) {
        if (method == "setUp" || method == "tearDown") continue
        // Annotations that mean the fixture is not the plain thing it looks like. The method's, not
        // the call's - a method that carries one carries it for every `doTest` in it.
        val head = source.substring(maxOf(0, at - 400), at)
        if (head.contains("@OptionTest")) { skip("the test sets options"); continue }
        if (head.contains("@VimBehaviorDiffers")) { skip("IdeaVim knowingly differs from Vim here"); continue }
        // A test IdeaVim has switched off does not pass over there either, so holding this host to
        // it measures nothing. `search in one time from select mode` was recorded here as a bug in
        // this host for a long time - `caret expected [81], actual [82]` - and IdeaVim's caret is at
        // 82 as well; the fixture's own `@Disabled` says "Ctrl-o doesn't work yet in select mode".
        if (head.contains("@Disabled")) { skip("IdeaVim has the test disabled"); continue }

        // Comments come out before the arguments are split, not after. A comment is not just noise
        // in front of an argument: `// Move the line to below the current line, which ...` has a
        // comma in it, and splitting on commas first tears the call into the wrong pieces.
        val trimmed = stripLineComments(body)
        // The method taken apart: what it bound, where it called `doTest`, and whether it did
        // anything else. Most of IdeaVim's plainly-named tests are written
        // `val before = ...; val after = ...; doTest(keys, before, after)`, and reading only
        // literals meant the call looked unevaluatable rather than unread.
        val parsed = analyse(trimmed)
        if (parsed.calls.isEmpty()) {
          // No `doTest`, so the other shape: `configureByText` / `typeText` / `assertState`, which
          // is what `doTest` is a helper over and how 922 of IdeaVim's tests are written.
          //
          // A method with neither is not a fixture at all - a helper, a `setUp`, a unit test of
          // something internal - and is passed over rather than counted as skipped. Counting them
          // would put 6,416 in the "cannot repeat" column and drown the reasons that mean something.
          if (!trimmed.contains("configureByText(") || !trimmed.contains("assertState(")) continue
          if (fileSetup == null) { skip("the class's setUp cannot be read (narrative)"); continue }
          if (fileExtensions.any { it in NOT_HERE }) {
            skip("the test needs an extension this host does not have"); continue
          }
          fixtures += narrativeFixtures(path, repositoryRoot, method, trimmed, parsed.bindings, fileSetup)
          continue
        }
        val enables = enableCalls(trimmed)

        // Options an earlier `doTest` in the same method set are still set for a later one: each
        // call re-seeds the text and none of them re-seeds the options. `test smartcase option`
        // turns on this - its fourth call sets only `ignorecase` and expects `smartcase` from its
        // second - so the setup accumulates rather than being read per call.
        val accumulated = mutableListOf<String>()
        val turnedOn = mutableListOf<String>()
        turnedOn += fileExtensions
        var ordinal = 0
        for ((index, call) in parsed.calls.withIndex()) {
          ordinal++
          // A method may call `doTest` more than once, with the same keys against different text or
          // the same text with different keys, and only the first was ever taken. The name has to
          // stay stable because it is the key in the baseline file, and it does: `upstream` is
          // read-only, so the calls in a method do not move.
          val name = if (ordinal == 1) method else "$method#$ordinal"
          // Whatever the method turned on before this call, as well as whatever the class did.
          turnedOn += enables.filter { it.first < call }.flatMap { it.second }
          if (turnedOn.any { it in NOT_HERE }) {
            skip("the test needs an extension this host does not have"); continue
          }
          if (fileSetup == null) { skip("the class's setUp cannot be read"); continue }
          // A statement this cannot read, standing before this call. `testInsertFromRegister` is
          // `setRegister('a', "World")` and then a `doTest` that pastes register `a`; reading the
          // call and not the line above it would record a failure that is entirely the harness's.
          if (parsed.firstUnread != null && parsed.firstUnread < call - "doTest".length) {
            skip("a statement before the call cannot be read"); continue
          }
          val spans = argumentSpans(trimmed, call) ?: skip("could not parse the arguments") ?: continue

          // A trailing lambda is `afterEditorInitialized`, and it is nearly always one or more
          // `enterCommand("set ...")` calls - a fixture about an option, which is worth more than a
          // plain one because an option nothing reads is this port's quietest kind of failure. Those
          // are reproduced by typing the command. A lambda that does anything else is refused, since
          // replaying it without its setup would measure the harness rather than the host.
          val afterParen = spans.last().second + 1
          val lambda = trimmed.substring(afterParen, parsed.callEnds[index]).trim()
          val setup = if (lambda.startsWith("{")) enterCommands(lambda) else emptyList()
          if (setup == null) { skip("the trailing lambda does more than set options"); continue }
          accumulated += setup

          if (spans.size < 3) { skip("fewer than three arguments"); continue }

          // A file type or name means the test is about a language, and this host has no parser.
          val extra = spans.drop(3).joinToString(" ") { trimmed.substring(it.first, it.second) }
          if (extra.contains("fileType") || extra.contains("fileName") || extra.contains("afterEditorInitialized")) {
            skip("the test needs a language"); continue
          }

          val values = spans.take(3).map { (from, to) -> evaluate(trimmed.substring(from, to).trim(), parsed.bindings) }
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
          fixtures += VimFixture(
            "${path.substringAfter("$repositoryRoot/")}:$name",
            keys,
            before,
            after,
            fileSetup + turnedOn.drop(fileExtensions.size).distinct().map { "set $it" } + accumulated.toList(),
          )
        }
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

  /**
   * One string-valued Kotlin expression, or null when it is anything this does not understand.
   *
   * [bindings] are the `val`s the method declared before it called `doTest`, so that
   * `doTest("yseb", before, after)` reads as the strings those names were given.
   */
  private fun evaluate(expression: String, bindings: Map<String, String> = emptyMap()): String? {
    bindings[expression]?.let { return it }
    if (expression.startsWith("listOf(")) {
      val parts = argumentSpans(expression, expression.indexOf('(')) ?: return null
      val pieces = parts.map { (from, to) -> evaluate(expression.substring(from, to).trim(), bindings) }
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
      return wrap(evaluate(expression.substring(parts[0].first, parts[0].second).trim(), bindings) ?: return null)
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
    // `typeText(injector.parser.parseKeys("dw"))` is `typeText("dw")` with the parse spelled out.
    // The replay parses the string itself, so the call is the string.
    "injector.parser.parseKeys" to { keys: String -> keys },
    "parseKeys" to { keys: String -> keys },
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
   * A test method taken apart: what it bound, where it called `doTest`, and whether it did anything
   * else.
   *
   * The last field is the one that matters most. `testInsertFromRegister` is
   * `setRegister('a', "World")` and then a `doTest` that pastes register `a`, and a harness that
   * read the call and not the line above it would record a failure that is entirely its own. So a
   * method is refused unless every statement in it is either a string binding this can read or a
   * `doTest` call.
   */
  private class MethodBody(
    val bindings: Map<String, String>,
    /** The index of the `(` of each `doTest`, in the order they appear. */
    val calls: List<Int>,
    /** The end of each call, after its trailing lambda if it has one. */
    val callEnds: List<Int>,
    /**
     * Where the first statement this cannot read is, or null.
     *
     * A position rather than a flag, because *where* it is decides what it costs. A statement
     * before a `doTest` may have set the register that `doTest` pastes; one after it is an extra
     * assertion, which this does not run and does not need to. Refusing on either would have cost
     * 95 fixtures - measured - for the sake of the handful that matter.
     */
    val firstUnread: Int?,
  )

  private fun analyse(body: String): MethodBody {
    val bindings = mutableMapOf<String, String>()
    val spans = mutableListOf<IntRange>()
    var unreadable: Int? = null

    for (match in BINDING.findAll(body)) {
      // A `val` inside a string is not a binding. Strings are the one thing a regex over source
      // cannot see past, and IdeaVim's mapping tests put Vim script in them.
      if (isInsideString(body, match.range.first)) continue
      val read = readStringExpression(body, match.range.last + 1)
      if (read == null) {
        unreadable = minOf(unreadable ?: match.range.first, match.range.first)
        continue
      }
      val name = match.groupValues[1]
      if (name !in bindings) bindings[name] = read.first
      spans += match.range.first..read.second
    }

    val calls = mutableListOf<Int>()
    val ends = mutableListOf<Int>()
    for (call in doTestCalls(body)) {
      val arguments = argumentSpans(body, call)
      if (arguments == null) {
        unreadable = minOf(unreadable ?: call, call)
        continue
      }
      val afterParen = arguments.last().second + 1
      val end = endOfTrailingLambda(body, afterParen) ?: afterParen
      calls += call
      ends += end
      spans += (call - "doTest".length)..(end - 1)
    }

    // An `enableExtensions(...)` is a statement this *can* read - it becomes a `:set` in the
    // fixture's setup - so it must not count as leftover and refuse the calls after it.
    for ((at, names) in enableCalls(body)) {
      val open = body.indexOf('(', at)
      val end = argumentSpans(body, open)?.last()?.second ?: continue
      if (names.isEmpty()) continue
      spans += at..end
    }

    val consumed = BooleanArray(body.length)
    for (span in spans) for (index in span) if (index in consumed.indices) consumed[index] = true
    val leftover = body.indices.firstOrNull { !consumed[it] && !body[it].isWhitespace() }

    val first = listOfNotNull(unreadable, leftover).minOrNull()
    return MethodBody(bindings, calls, ends, first)
  }

  /** Where a `{ ... }` starting at the first non-space character after [from] ends, or null. */
  private fun endOfTrailingLambda(text: String, from: Int): Int? {
    var index = from
    while (index < text.length && text[index].isWhitespace()) index++
    if (text.getOrNull(index) != '{') return null
    var depth = 0
    while (index < text.length) {
      if (text[index] == '"') {
        index = skipString(text, index) ?: return null
        continue
      }
      if (text[index] == '{') depth++
      if (text[index] == '}') {
        depth--
        if (depth == 0) return index + 1
      }
      index++
    }
    return null
  }

  /** Whether [at] falls inside a string literal, walked from the start of [text]. */
  private fun isInsideString(text: String, at: Int): Boolean {
    var index = 0
    while (index < at) {
      if (text[index] == '"') {
        val end = skipString(text, index) ?: return true
        if (at < end) return true
        index = end
        continue
      }
      index++
    }
    return false
  }

  /**
   * Where each `doTest(` begins in a method's body, as the index of its `(`.
   *
   * Every one of them, not only a body that *starts* with one. A method that binds its text first -
   * `val before = ...; val after = ...; doTest(keys, before, after)` - was skipped without being
   * counted, and a method that calls `doTest` three times against different text gave up two of the
   * three.
   */
  private fun doTestCalls(body: String): List<Int> {
    val found = mutableListOf<Int>()
    var index = 0
    while (index < body.length) {
      if (body[index] == '"') {
        index = skipString(body, index) ?: break
        continue
      }
      if (body.startsWith("doTest(", index) && !isIdentifierChar(body.getOrNull(index - 1))) {
        found += index + "doTest".length
        index += "doTest(".length
        continue
      }
      index++
    }
    return found
  }

  private val BINDING = Regex("""\bva[lr]\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*String\s*)?=\s*""")

  /**
   * A `+` chain of string literals starting at [start], with where it ended.
   *
   * Null when what is there is not a string at all, which is how `val editor = fixture.editor` is
   * told from `val before = "..."`.
   */
  private fun readStringExpression(text: String, start: Int): Pair<String, Int>? {
    val builder = StringBuilder()
    var index = start
    var read = 0
    while (true) {
      val piece = readString(text, index) ?: return if (read == 0) null else builder.toString() to index
      builder.append(piece.first)
      read++
      index = piece.second
      val rest = text.substring(index)
      val spaces = rest.takeWhile { it.isWhitespace() }.length
      if (!rest.drop(spaces).startsWith("+")) return builder.toString() to index
      index += spaces + 1
      index += text.substring(index).takeWhile { it.isWhitespace() }.length
    }
  }

  private fun isIdentifierChar(character: Char?): Boolean =
    character != null && (character.isLetterOrDigit() || character == '_' || character == '.')

  /**
   * What a class's `setUp` does, as ex commands, in the order it does them.
   *
   * The extension tests keep their setup here rather than in a trailing lambda, and the order is
   * load-bearing: `camelcasemotion` reads `g:camelcasemotion_key` when it is enabled, so the `let`
   * has to come before the `set`. Thirty-one fixtures turn on that one line.
   *
   * Null when `setUp` does something this cannot reproduce. `super.setUp` and `configureByText` are
   * read and ignored - the first is the fixture harness's own equivalent of itself, and the second
   * seeds text that every fixture replaces anyway.
   */
  private fun setUpCommands(body: String): Pair<List<String>, List<String>>? {
    val commands = mutableListOf<String>()
    val extensions = mutableListOf<String>()
    val spans = mutableListOf<IntRange>()

    for ((at, names) in enableCalls(body)) {
      val open = body.indexOf('(', at)
      val end = argumentSpans(body, open)?.last()?.second ?: return null
      spans += at..end
      names.forEach { commands += "at:$at set $it" }
      extensions += names
    }
    for (name in listOf("enterCommand", "configureByText", "super.setUp", "typeText")) {
      var index = 0
      while (true) {
        index = body.indexOf("$name(", index)
        if (index < 0) break
        val open = index + name.length
        val arguments = argumentSpans(body, open) ?: return null
        spans += index..arguments.last().second
        if (name == "enterCommand") {
          val value = evaluate(body.substring(arguments[0].first, arguments[0].second).trim()) ?: return null
          commands += "at:$index $value"
        }
        index = arguments.last().second + 1
      }
    }

    val consumed = BooleanArray(body.length)
    for (span in spans) for (index in span) if (index in consumed.indices) consumed[index] = true
    if (body.indices.any { !consumed[it] && !body[it].isWhitespace() }) return null

    // Back into order, which is why each one carries where it came from.
    val ordered = commands
      .sortedBy { it.substringAfter("at:").substringBefore(' ').toInt() }
      .map { it.substringAfter(' ') }
    return ordered to extensions
  }

  /**
   * The fixtures a long-way-round test is worth: one per `assertState`, each replayed from the top.
   *
   * A method of this shape often checks more than once - `enterCommand("2,4j")`, check, `typeText
   * ("u")`, check - and each check is a fixture in its own right whose keys are everything typed
   * before it. Replaying from the start rather than continuing means a fixture that fails says what
   * it means without depending on the one before it, which is the same reason the replay resets the
   * engine between fixtures.
   */
  private fun narrativeFixtures(
    path: String,
    repositoryRoot: String,
    method: String,
    body: String,
    bindings: Map<String, String>,
    fileSetup: List<String>,
  ): List<VimFixture> {
    val steps = narrativeIn(body, bindings)
    if (steps == null) { skip("the narrative does more than configure, type and assert"); return emptyList() }
    if (steps.none { it is Step.Check }) return emptyList()

    val fixtures = mutableListOf<VimFixture>()
    var before: String? = null
    val keys = StringBuilder()
    val commands = mutableListOf<String>()
    var typedAlready = false
    var ordinal = 0
    for (step in steps) {
      when (step) {
        is Step.Configure -> {
          before = step.text
          keys.clear()
          commands.clear()
          typedAlready = false
        }

        is Step.Type -> {
          keys.append(step.keys)
          typedAlready = true
        }

        is Step.Command -> {
          // A command run *after* some keys cannot be reproduced: the setup all runs first. Only
          // `:` commands that come before any typing are safe to lift out, which is every one of
          // them in practice - a test sets its options and its mappings and then types.
          if (typedAlready) { skip("the test runs a command after typing"); return fixtures }
          commands += step.text
        }

        is Step.Check -> {
          ordinal++
          val start = before
          if (start == null) { skip("the test checks before it has any text"); continue }
          val marked = listOf(start, step.text).map { caretsIn(it) }
          if (marked.any { it == null }) {
            skip("string interpolation other than the caret"); continue
          }
          val (from, to) = marked.map { it!! }
          if (from.contains(SELECTION_START)) { skip("the test starts with a selection"); continue }
          if (to.split(SELECTION_START).size != to.split(SELECTION_END).size) {
            skip("an unbalanced selection in the result"); continue
          }
          val name = if (ordinal == 1) method else "$method#$ordinal"
          fixtures += VimFixture(
            "${path.substringAfter("$repositoryRoot/")}:$name",
            keys.toString(),
            from,
            to,
            fileSetup + commands,
          )
        }
      }
    }
    return fixtures
  }

  /** One statement of a test written the long way: seed the text, type, check. */
  private sealed interface Step {
    /** `configureByText("...")` - the text the fixture starts from. */
    class Configure(val text: String) : Step

    /** `typeText(...)`, as keys. */
    class Type(val keys: String) : Step

    /**
     * `enterCommand("nmap ,f iHello<Esc>")`, which is *not* the same as typing those keys.
     *
     * A mapping's body can contain `<Esc>`, and typing it at the command line presses Escape rather
     * than writing five characters into it - which is why `doTest`'s own setup commands are typed
     * literally through `stringToKeys` and not parsed. Folding these into the key stream produced
     * sixteen fixtures that measured the harness: `:nmap ,f iHello<Esc><CR>,fdh` left an empty
     * buffer, because the mapping was never defined.
     */
    class Command(val text: String) : Step

    /** `assertState("...")` with text rather than a mode - a fixture ends at each of these. */
    class Check(val text: String) : Step
  }

  /**
   * A test written as `configureByText` / `typeText` / `assertState` rather than as `doTest`.
   *
   * 922 of IdeaVim's tests are this shape and it is the same fixture by a longer road: text in,
   * keys, text out. `doTest` is a helper over exactly these three calls - see `VimTestCase` - so a
   * harness that only read the helper was reading a third of what says the same thing.
   *
   * Null when the method does anything else. That is the whole of the care needed here: these tests
   * are freer than a `doTest` and some of them set a register in Kotlin, or wrap the body in
   * `try`/`finally` to put an option back. Reading the calls and not the rest would replay a fixture
   * that was never set up.
   */
  private fun narrativeIn(body: String, bindings: Map<String, String>): List<Step>? {
    val steps = mutableListOf<Pair<Int, Step>>()
    val spans = mutableListOf<IntRange>()

    for (match in BINDING.findAll(body)) {
      if (isInsideString(body, match.range.first)) continue
      val read = readStringExpression(body, match.range.last + 1) ?: return null
      spans += match.range.first..read.second
    }

    for ((at, names) in enableCalls(body)) {
      val open = body.indexOf('(', at)
      val end = argumentSpans(body, open)?.last()?.second ?: return null
      spans += at..end
      names.forEach { steps += at to Step.Command("set $it") }
    }

    for (name in listOf("configureByText", "typeText", "enterCommand", "assertState")) {
      var index = 0
      while (true) {
        index = body.indexOf("$name(", index)
        if (index < 0) break
        if (isInsideString(body, index) || isIdentifierChar(body.getOrNull(index - 1))) {
          index += name.length
          continue
        }
        val open = index + name.length
        val arguments = argumentSpans(body, open) ?: return null
        spans += index..arguments.last().second
        val values = arguments.map { (from, to) -> evaluate(body.substring(from, to).trim(), bindings) }
        when {
          // `assertState(Mode.NORMAL())` checks the mode rather than the text, and the replay does
          // not compare modes. Read so that it is not leftover; otherwise ignored.
          name == "assertState" && values.singleOrNull() == null && arguments.size == 1 -> Unit

          values.any { it == null } -> return null
          name == "configureByText" -> steps += index to Step.Configure(values.last()!!)
          name == "assertState" -> steps += index to Step.Check(values.single()!!)
          name == "enterCommand" -> steps += index to Step.Command(values.single()!!)
          else -> steps += index to Step.Type(values.joinToString("") { it!! })
        }
        index = arguments.last().second + 1
      }
    }

    val consumed = BooleanArray(body.length)
    for (span in spans) for (index in span) if (index in consumed.indices) consumed[index] = true
    if (body.indices.any { !consumed[it] && !body[it].isWhitespace() }) return null

    return steps.sortedBy { it.first }.map { it.second }
  }

  /**
   * The `enableExtensions("surround")` calls in a body, with where each one is.
   *
   * `VimTestCase.enableExtensions` sets the extension's toggle option, which is what `:set surround`
   * does - so a fixture's setup can say it in one line. That was not always true here: `set <name>`
   * was `E518` on this host until the extension options were registered, and a config runs with
   * errors suppressed, so it failed silently for all twenty-two. Extension fixtures could not have
   * been replayed before that was fixed.
   *
   * `enableExtensionsNewApi` is the same thing for a thin-API extension and reads the same here.
   */
  private fun enableCalls(body: String): List<Pair<Int, List<String>>> {
    val found = mutableListOf<Pair<Int, List<String>>>()
    for (name in listOf("enableExtensions", "enableExtensionsNewApi")) {
      var index = 0
      while (true) {
        index = body.indexOf("$name(", index)
        if (index < 0) break
        val open = index + name.length
        val spans = argumentSpans(body, open)
        if (spans == null) { index = open + 1; continue }
        val names = spans.mapNotNull { (from, to) -> evaluate(body.substring(from, to).trim()) }
        if (names.size == spans.size) found += index to names
        index = spans.last().second + 1
      }
    }
    return found.sortedBy { it.first }
  }

  /**
   * Extensions this host does not have, so a fixture that needs one is refused rather than failed.
   *
   * `matchit` and `VimEverywhere` are not ported and are not going to be - see CLAUDE.md for what
   * each of them wants that VS Code does not offer. `TestExtension` is one the test registers for
   * itself, in Kotlin, which is a different thing again.
   *
   * Everything else that reaches here is an option rather than an extension - `enableExtensions`
   * sets a toggle option and two of IdeaVim's tests use it for `ignorecase` and `smartcase` - and
   * `:set` is the right answer for those too.
   */
  private val NOT_HERE = setOf("matchit", "VimEverywhere", "TestExtension")

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
