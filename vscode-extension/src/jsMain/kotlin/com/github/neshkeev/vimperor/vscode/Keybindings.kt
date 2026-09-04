/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

/**
 * The chords VS Code has bound to its commands, for the second column of `:actionlist`.
 *
 * IdeaVim prints the shortcut beside every action because that is half of what the list is for: a
 * reader is either looking for a name to put in a mapping, or looking for what the editor already
 * does with a key. IntelliJ hands that over in one call - `ActionManager.getAction(id).shortcutSet`
 * - and **VS Code has no such API at all**. `vscode.d.ts` has no way to ask what is bound, to
 * anything, and the request for one has been open since 2016.
 *
 * So this reads the three files VS Code builds its own keymap out of, in the order VS Code applies
 * them, and each one has a different reason for being reachable:
 *
 *  - **The defaults.** Every core keybinding - `shift+cmd+f` for `workbench.action.findInFiles` and
 *    the other few hundred - is registered in VS Code's own TypeScript and appears in no manifest.
 *    The one place they are readable is the document behind
 *    `workbench.action.openDefaultKeybindingsFile`, which the workbench serves from
 *    [DEFAULT_KEYBINDINGS_URI] through a text model provider. `workspace.openTextDocument` resolves
 *    a URI through that same provider, so the file can be read without being shown. This is the
 *    only undocumented step here, and it is the one that carries the interesting half of the list -
 *    see [readDefaultKeybindings] for what happens when a VS Code stops serving it.
 *  - **The extensions.** `contributes.keybindings` in each `package.json`, which
 *    `extensions.all[].packageJSON` hands over as parsed JSON. Documented, and it covers the
 *    built-in extensions as well as installed ones - Git's chords come from here, and so do
 *    Vimperor's own.
 *  - **The user's.** `keybindings.json`, read off disk. Located from
 *    `ExtensionContext.globalStorageUri`, which is always `<user data>/User/globalStorage/<id>` -
 *    two directories up is the folder the file is in, whichever VS Code this is and wherever the
 *    user data was moved to. Hard-coding `~/Library/Application Support/Code` would find nothing
 *    for Insiders, VSCodium, a portable install or a remote window.
 *
 * What is *not* here is the `when` clause. Most bindings have one, this host cannot evaluate them -
 * that is the workbench's job and the context keys are write-only to an extension - and printing
 * conditions nobody can check would make the column less trustworthy rather than more. The column
 * says what is bound; whether it is bound *right now* is what the Keyboard Shortcuts editor is for.
 */
data class Keybinding(

  /** The command the chord runs. Never carries the leading `-` of a removal; see [removes]. */
  val command: String,

  /** VS Code's own notation - `shift+cmd+f`, or `cmd+k cmd+s` for a two-chord sequence. */
  val key: String,

  /**
   * Whether this entry takes a binding away rather than adding one.
   *
   * `keybindings.json` unbinds by naming the command with a `-` in front of it, and the entry has
   * to keep the key: `-workbench.action.findInFiles` bound to `shift+cmd+f` removes that one chord
   * and leaves any other. A user who has rebound half their keyboard would otherwise read a list
   * of chords that do nothing.
   */
  val removes: Boolean = false,
)

/**
 * Every chord bound to each command, built from the sources in the order VS Code applies them.
 *
 * The sources are held apart rather than merged as they arrive, because they do not arrive in the
 * order they apply: the defaults come over a promise and are therefore last, while they have to be
 * *applied* first for a user's `keybindings.json` to be able to override or unbind them. Each
 * setter recomputes, so the table is correct at every moment and not only once everything has
 * landed.
 */
class KeybindingTable {

  private var defaults: List<Keybinding> = emptyList()
  private var contributed: List<Keybinding> = emptyList()
  private var user: List<Keybinding> = emptyList()

  private var byCommand: Map<String, List<String>> = emptyMap()

  fun setDefaults(bindings: List<Keybinding>) {
    defaults = bindings
    recompute()
  }

  fun setContributed(bindings: List<Keybinding>) {
    contributed = bindings
    recompute()
  }

  fun setUser(bindings: List<Keybinding>) {
    user = bindings
    recompute()
  }

  /** The chords bound to [command], in the order they were found. Empty when nothing is. */
  fun shortcutsFor(command: String): List<String> = byCommand[command] ?: emptyList()

  /** How many commands have a chord, which is what activation reports and `:actionlist` asks about. */
  val commandCount: Int get() = byCommand.size

  private fun recompute() {
    val result = LinkedHashMap<String, MutableList<String>>()
    for (source in listOf(defaults, contributed, user)) {
      for (binding in source) {
        val chords = result.getOrPut(binding.command) { mutableListOf() }
        if (binding.removes) {
          chords.remove(binding.key)
        } else if (binding.key !in chords) {
          chords += binding.key
        }
      }
    }
    byCommand = result.filterValues { it.isNotEmpty() }
  }
}

/**
 * The entries of a `keybindings.json`, or of a `contributes.keybindings` array, for one platform.
 *
 * An entry names its chord in `key` and may override it per platform in `mac`, `win` or `linux`;
 * the defaults file is generated for the machine it is served on and uses `key` alone. Anything
 * without a command or without a chord is skipped - `"command": ""` is how VS Code's own defaults
 * file writes a chord that has been taken away from everything.
 */
fun keybindingsFrom(entries: dynamic, platform: String): List<Keybinding> {
  // An object with a length, which in practice means an array. The type check is not pedantry: a
  // string has a length too, and `dynamic` beats `String` in overload resolution - so without it
  // the text overload below resolved to this one and every file read as a list of characters.
  if (entries == null || jsTypeOf(entries) != "object" || jsTypeOf(entries.length) != "number") return emptyList()

  val found = mutableListOf<Keybinding>()
  val count = entries.length as Int
  for (index in 0 until count) {
    val entry = entries[index] ?: continue
    val command = text(entry.command)?.trim() ?: continue
    val key = (text(entry[platform]) ?: text(entry.key))?.trim() ?: continue
    if (key.isEmpty()) continue

    if (command.startsWith("-")) {
      val removed = command.substring(1).trim()
      if (removed.isNotEmpty()) found += Keybinding(removed, key, removes = true)
    } else if (command.isNotEmpty()) {
      found += Keybinding(command, key)
    }
  }
  return found
}

/** The same, from text that has not been parsed yet. Unreadable JSON is no keybindings. */
fun keybindingsFromJson(json: String, platform: String): List<Keybinding> = try {
  keybindingsFrom(JSON.parse<dynamic>(asStrictJson(json)), platform)
} catch (e: Throwable) {
  emptyList()
}

/**
 * JSONC as JSON: comments dropped, trailing commas dropped.
 *
 * Both of the files read here are JSONC, and both are full of it - `keybindings.json` is created
 * with a header comment and the default keybindings file is one long commented list. `JSON.parse`
 * refuses either. A parser rather than a pair of regexes because a `//` inside a string is a chord
 * in this file: `"key": "cmd+/"` is real, and so is a `when` clause with a comment marker in it.
 */
internal fun asStrictJson(text: String): String {
  val out = StringBuilder(text.length)
  var index = 0
  while (index < text.length) {
    val character = text[index]

    if (character == '"') {
      // A string, copied whole. An escaped quote does not end it, so both characters go over
      // together - which is also what keeps a `\\` before a quote from being misread.
      out.append(character)
      index++
      while (index < text.length) {
        val inner = text[index]
        out.append(inner)
        index++
        if (inner == '\\' && index < text.length) {
          out.append(text[index])
          index++
        } else if (inner == '"') {
          break
        }
      }
      continue
    }

    if (character == '/' && index + 1 < text.length && text[index + 1] == '/') {
      while (index < text.length && text[index] != '\n') index++
      continue
    }

    if (character == '/' && index + 1 < text.length && text[index + 1] == '*') {
      index += 2
      while (index + 1 < text.length && !(text[index] == '*' && text[index + 1] == '/')) index++
      index = minOf(index + 2, text.length)
      continue
    }

    if (character == ',' && nextIsClosing(text, index + 1)) {
      index++
      continue
    }

    out.append(character)
    index++
  }
  return out.toString()
}

/** Whether the next thing after some whitespace closes an array or an object, comments included. */
private fun nextIsClosing(text: String, from: Int): Boolean {
  var index = from
  while (index < text.length) {
    val character = text[index]
    when {
      character.isWhitespace() -> index++

      character == '/' && index + 1 < text.length && text[index + 1] == '/' -> {
        while (index < text.length && text[index] != '\n') index++
      }

      character == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
        index += 2
        while (index + 1 < text.length && !(text[index] == '*' && text[index + 1] == '/')) index++
        index = minOf(index + 2, text.length)
      }

      else -> return character == ']' || character == '}'
    }
  }
  return false
}

/**
 * Where the user's `keybindings.json` is, given the storage directory VS Code handed the extension.
 *
 * `globalStorageUri` is `<user data>/User/globalStorage/<publisher>.<name>`, so the file sits two
 * directories up. Null when the path is not that shape, which is the honest answer - a guess at a
 * standard location would read some other VS Code's file on a machine that has several.
 */
fun userKeybindingsPath(globalStorage: String?): String? {
  if (globalStorage.isNullOrEmpty()) return null

  val separator = if ('\\' in globalStorage && '/' !in globalStorage) '\\' else '/'
  val parts = globalStorage.trimEnd('/', '\\').split('/', '\\').filter { it.isNotEmpty() }
  if (parts.size < 2) return null
  if (parts[parts.size - 2] != "globalStorage") return null

  val prefix = if (globalStorage.startsWith("/")) "/" else ""
  return prefix + parts.dropLast(2).joinToString(separator.toString()) + separator + "keybindings.json"
}

/** Which of an entry's platform keys applies here. VS Code's own names for the three. */
fun currentPlatform(nodePlatform: String?): String = when (nodePlatform) {
  "darwin" -> "mac"
  "win32" -> "win"
  else -> "linux"
}

/** What Node says it is running on, which is what the extension host is running on. */
fun hostPlatform(): String = currentPlatform(text(process.platform))

/**
 * `contributes.keybindings` from every extension this window has, the built-in ones included.
 *
 * The only documented source of the three, and the only one that needs nothing but the API: an
 * extension's manifest is already parsed and already in memory.
 */
fun contributedKeybindings(platform: String): List<Keybinding> {
  val found = mutableListOf<Keybinding>()
  for (extension in extensions.all) {
    val contributes = extension.packageJSON?.contributes ?: continue
    found += keybindingsFrom(contributes.keybindings, platform)
  }
  return found
}

/** The user's `keybindings.json`, or nothing when they have never opened it. */
fun userKeybindings(path: String?, files: NodeFileSystem, platform: String): List<Keybinding> {
  if (path == null || !files.exists(path)) return emptyList()
  return try {
    keybindingsFromJson(files.readText(path), platform)
  } catch (e: Throwable) {
    emptyList()
  }
}

/**
 * The document the workbench serves for `workbench.action.openDefaultKeybindingsFile`.
 *
 * Read rather than shown: `openTextDocument` goes through the same text model provider the command
 * opens an editor onto, so the file arrives without a tab being opened in front of the user.
 *
 * This is the one thing in the extension that asks VS Code for something `vscode.d.ts` does not
 * promise, so it is written to be allowed to fail. A VS Code that stops serving the URI rejects the
 * promise, [onFailure] says so in the output channel, and `:actionlist` prints the two documented
 * sources with a blank column where the core chords would have been - which is what it printed
 * before any of this existed.
 */
fun readDefaultKeybindings(
  platform: String,
  onFailure: (String) -> Unit,
  onLoaded: (List<Keybinding>) -> Unit,
) {
  try {
    workspace.openTextDocument(UriFactory.parse(DEFAULT_KEYBINDINGS_URI)).then(
      { document -> onLoaded(keybindingsFromJson(document.getText(), platform)) },
      { failure -> onFailure(failure?.toString() ?: "the promise was rejected without a reason") },
    )
  } catch (e: Throwable) {
    onFailure(e.message ?: e::class.simpleName ?: "it threw")
  }
}

/**
 * Where VS Code keeps its own keybindings for reading.
 *
 * `vscode://defaultsettings/...` is the workbench's scheme for the read-only halves of the
 * preferences editors - `settings.json` has one beside this - and `PreferencesContribution`
 * registers the provider that answers for it.
 */
internal const val DEFAULT_KEYBINDINGS_URI = "vscode://defaultsettings/keybindings.json"

/** A string, or null for the undefined and the wrongly typed alike. */
private fun text(value: dynamic): String? = if (jsTypeOf(value) == "string") value as String else null

/** Node's, for the platform. The extension host is a Node process; see [NodeFileSystem]. */
private external val process: dynamic
