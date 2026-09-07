/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.tags
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.Options
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.annotations.TestOnly
import com.github.neshkeev.vimperor.directory.WorkingDirectory
import com.maddyhome.idea.vim.options.OptionAccessScope

/**
 * Tags: a `tags` file on disk, the matches found in it, and the stack of places you jumped from.
 *
 * A real reader of ctags output rather than a wrapper around the host's symbol search, which is the
 * decision worth explaining. Both hosts do have a symbol index - `gd` and `<C-]>` go through it
 * already - but neither will answer *synchronously*, and a `:tag` that returns before it knows
 * whether the tag exists cannot report `E426`. A tags file can be read and answered from inside the
 * command, which is also what makes `:tselect`, `:tnext` and the tag stack possible at all: they
 * are questions about a *list of matches*, and a symbol search that resolves later has no list to
 * ask about.
 *
 * It is also what Vim does, so `:set tags=` means what its documentation says, a project with a
 * `tags` file gets Vim's behaviour exactly, and a project without one gets Vim's answer for that
 * too - `E433: No tags file` - rather than a silent fallback to something that is not tags.
 *
 * One simplification, at [TagMatch.address]: Vim treats a search address as a full regular
 * expression and this matches it as text. Ctags writes `/^func main() {$/` - the line as it was
 * written, with only `/` and `\` escaped - so the text is what the pattern is *for*, and a line
 * that has since moved is still found. A pattern with regex characters in the source line finds
 * nothing rather than the wrong line.
 */
object Tags {

  /** One line of a tags file: what it is called, where it lives, and how to find it there. */
  data class TagMatch(
    val name: String,
    val path: String,

    /**
     * Vim's "tag address": either a line number, or a search command like `/^int main()$/`.
     *
     * Kept as written rather than resolved on the way in, because resolving it means reading the
     * file it points at - and a `:tselect` over forty matches would read forty files to print a
     * list nobody has chosen from yet.
     */
    val address: String,

    /** ctags' extension field, `f` for a function and so on. Null when the file has none. */
    val kind: String?,
  )

  /**
   * One place on the tag stack: where you were, and which of the matches you went to.
   *
   * Vim's stack entry holds the whole match list rather than the one match, which is what makes
   * `:tnext` after a `:tag` mean anything - the list is the thing being walked, and it belongs to
   * the jump rather than to the session.
   */
  data class StackEntry(
    val tagName: String,
    val fromPath: String,
    val fromLine: Int,
    val fromColumn: Int,
    val matches: List<TagMatch>,
    var index: Int,
  )

  private val stacks = mutableMapOf<String, MutableList<StackEntry>>()

  /**
   * How much of the stack is in use, which is not the same as how big it is.
   *
   * `:pop` moves this down without throwing entries away, so that `:tag` with no argument can walk
   * back up to where it was. A new `:tag {name}` truncates to here first, which is what makes the
   * stack a stack again after wandering down it.
   */
  private val pointers = mutableMapOf<String, Int>()

  fun stack(projectId: String): List<StackEntry> = stacks[projectId].orEmpty()

  fun pointer(projectId: String): Int = pointers[projectId] ?: stack(projectId).size

  /** The entry `:tnext` and its relatives walk, which is the one most recently jumped into. */
  fun current(projectId: String): StackEntry? =
    stack(projectId).getOrNull(pointer(projectId) - 1)

  fun push(projectId: String, entry: StackEntry) {
    val list = stacks.getOrPut(projectId) { mutableListOf() }
    // Everything above where we are is a road not taken any more.
    while (list.size > pointer(projectId)) list.removeAt(list.size - 1)
    list += entry
    if (list.size > STACK_SIZE) list.removeAt(0)
    pointers[projectId] = list.size
  }

  fun setPointer(projectId: String, value: Int) {
    pointers[projectId] = value.coerceIn(0, stack(projectId).size)
  }

  /**
   * Every tag named [name], across every tags file `'tags'` names.
   *
   * Ordered as the files are, which is Vim's order and the reason `'tags'` is a list rather than a
   * path: the first file named is the one whose answer wins.
   */
  fun find(name: String, editor: VimEditor, context: ExecutionContext): List<TagMatch> {
    val files = tagsFiles(editor, context)
    if (files.isEmpty()) throw com.maddyhome.idea.vim.ex.exExceptionMessage("E433")

    return files.flatMap { file -> parse(readOrEmpty(file), directoryOf(file)) }
      .filter { it.name == name }
  }

  /** The same, by prefix, for `:tag /pattern` - Vim's "starts with" form spelled the simple way. */
  fun findStartingWith(prefix: String, editor: VimEditor, context: ExecutionContext): List<TagMatch> {
    val files = tagsFiles(editor, context)
    if (files.isEmpty()) throw com.maddyhome.idea.vim.ex.exExceptionMessage("E433")

    return files.flatMap { file -> parse(readOrEmpty(file), directoryOf(file)) }
      .filter { it.name.startsWith(prefix) }
  }

  /**
   * The tags files that actually exist, in `'tags'` order.
   *
   * `./tags` is resolved against the directory of the current file and a bare `tags` against the
   * working directory, which is Vim's rule and the only part of `'tags'` that is not a plain path.
   */
  fun tagsFiles(editor: VimEditor, context: ExecutionContext): List<String> {
    val setting = injector.optionGroup
      .getOptionValue(Options.tags, OptionAccessScope.EFFECTIVE(editor))
      .toVimString().value
      .ifEmpty { "./tags,tags" }
    val editorDirectory = editor.getPath()?.let { directoryOf(it) }
    val workingDirectory = WorkingDirectory.current(editor, context)

    return setting.split(",").mapNotNull { entry ->
      val trimmed = entry.trim()
      when {
        trimmed.isEmpty() -> null
        trimmed.startsWith("./") -> editorDirectory?.let { "$it/${trimmed.substring(2)}" }
        trimmed.startsWith("/") || trimmed.startsWith("~") -> trimmed
        trimmed.length > 2 && trimmed[1] == ':' -> trimmed
        else -> workingDirectory?.let { "${it.trimEnd('/', '\\')}/$trimmed" }
      }
    }.filter { injector.fileSystem.exists(it) }
  }

  /**
   * One tags file's worth of matches.
   *
   * The format is three tab-separated fields and then whatever ctags felt like adding after `;"`.
   * Lines beginning `!_TAG_` are the file's own metadata and are not tags. Paths in the file are
   * relative to the file, which is why [directory] has to come in.
   */
  fun parse(text: String, directory: String?): List<TagMatch> = text.lineSequence()
    .filter { it.isNotBlank() && !it.startsWith("!_TAG_") }
    .mapNotNull { line ->
      val fields = line.split("\t")
      if (fields.size < 3) return@mapNotNull null

      val rest = fields.drop(2).joinToString("\t")
      val addressEnd = rest.indexOf(";\"")
      val address = (if (addressEnd >= 0) rest.substring(0, addressEnd) else rest).trim()
      val extras = if (addressEnd >= 0) rest.substring(addressEnd + 2).trim() else ""

      TagMatch(
        name = fields[0],
        path = resolve(fields[1], directory),
        address = address,
        kind = extras.split("\t").firstOrNull { it.isNotEmpty() && !it.contains(":") }
          ?: extras.split("\t").firstOrNull { it.startsWith("kind:") }?.removePrefix("kind:"),
      )
    }
    .toList()

  /**
   * The line the address points at, zero-based, or null when it points at nothing.
   *
   * A number is the line. A `/.../` or `?...?` is the text of the line as it was when the tags file
   * was made, and it is searched for rather than matched as a regex - see the note at the top.
   */
  fun lineFor(match: TagMatch, text: String): Int? {
    match.address.toIntOrNull()?.let { return (it - 1).coerceAtLeast(0) }

    val delimiter = match.address.firstOrNull() ?: return null
    if (delimiter != '/' && delimiter != '?') return null

    val body = match.address.trim().removeSurrounding(delimiter.toString())
    val wanted = body.removePrefix("^").removeSuffix("$")
      .replace("\\/", "/").replace("\\?", "?").replace("\\\\", "\\")
    if (wanted.isEmpty()) return null

    val lines = text.split("\n")
    val exact = lines.indexOfFirst { it == wanted }
    if (exact >= 0) return exact
    val loose = lines.indexOfFirst { it.trim() == wanted.trim() }
    return if (loose >= 0) loose else null
  }

  private fun readOrEmpty(path: String): String = try {
    injector.fileSystem.readText(path)
  } catch (e: Throwable) {
    ""
  }

  private fun resolve(path: String, directory: String?): String = when {
    directory == null -> path
    path.startsWith("/") || (path.length > 2 && path[1] == ':') -> path
    else -> "$directory/$path"
  }

  private fun directoryOf(path: String): String {
    val cut = path.lastIndexOfFirst('/', '\\')
    return if (cut <= 0) "." else path.substring(0, cut)
  }

  private fun String.lastIndexOfFirst(vararg characters: Char): Int =
    characters.maxOf { lastIndexOf(it) }

  @TestOnly
  fun reset() {
    stacks.clear()
    pointers.clear()
  }

  /** Vim's, and the reason a session that jumps all day does not grow without bound. */
  private const val STACK_SIZE = 20
}
