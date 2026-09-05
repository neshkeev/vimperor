/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

/**
 * Where the functions and classes in a document are, kept up to date in the background.
 *
 * VS Code knows this and will not say so synchronously: `vscode.executeDocumentSymbolProvider` goes
 * out to whichever language server owns the file and comes back over a promise. A text object is
 * asked its range in the middle of a keystroke, several times per operator, and cannot wait for
 * one - which is why `am`, `aM`, `im` and `ac` had nothing to work with here and their extensions
 * were left out of `VsCodeExtensions.BUNDLED`.
 *
 * `runAfterHostCatchesUp` is not the answer to this one. That seam lets a caller *continue* later,
 * which suits an extension driving keys; a text object has to *return a range now*. So the shape
 * that fits is the other one: ask ahead of time, and have the answer already in hand.
 *
 * The cost is one the design admits rather than hides. What is cached belongs to a document
 * version, and an edit invalidates it - so for the moment between an edit and the language server
 * answering again, these text objects do nothing, exactly as they did before. A refresh is started
 * on activation and after every change, so that moment is short and usually over before a key is
 * pressed; but it exists, and a text object that quietly used a stale range would be worse than one
 * that declines.
 */
interface DocumentSymbols {

  /**
   * What is known about [document] right now, or null if that is nothing current.
   *
   * Asking also starts a refresh when what is held is stale, so a key that declines once tends to
   * work the next time it is pressed.
   */
  fun of(document: TextDocument): List<SymbolRange>?

  /** Asks VS Code again unless what is held already describes this version. */
  fun refresh(document: TextDocument)

  /** A document that is no longer open. */
  fun forget(document: TextDocument)

  /** For a host with no language servers to ask - the stub, and every offline test. */
  object None : DocumentSymbols {
    override fun of(document: TextDocument): List<SymbolRange>? = null
    override fun refresh(document: TextDocument) {}
    override fun forget(document: TextDocument) {}
  }
}

/**
 * One symbol, flattened out of VS Code's tree and converted to offsets.
 *
 * [start] and [end] are `DocumentSymbol.range`, which VS Code documents as everything belonging to
 * the symbol including its leading comments. [nameStart] is `selectionRange.start`, the identifier
 * itself, and is what tells a doc comment from a signature: see `VsCodePsiService`.
 */
data class SymbolRange(
  val kind: Int,
  val start: Int,
  val end: Int,
  val nameStart: Int,
)

/**
 * `vscode.SymbolKind`, which is numeric on the wire and has no Kotlin declaration here.
 *
 * Only the ones a text object asks about are named. The numbers are the LSP's own and are part of
 * the protocol rather than of any one server.
 */
object SymbolKind {
  const val MODULE: Int = 1
  const val CLASS: Int = 4
  const val METHOD: Int = 5
  const val CONSTRUCTOR: Int = 8
  const val ENUM: Int = 9
  const val INTERFACE: Int = 10
  const val FUNCTION: Int = 11
  const val STRUCT: Int = 22

  /** What `am`, `aM` and `im` count as a function. */
  val FUNCTION_LIKE: Set<Int> = setOf(METHOD, CONSTRUCTOR, FUNCTION)

  /**
   * What `ac` counts as a class.
   *
   * `MODULE` is here for Ruby, whose `module` is class-like and which IdeaVim's own PSI table
   * treats as one (`RModuleImpl`). It also catches a TypeScript `namespace`, which is a stretch -
   * but a stretch in the direction of doing something, and `ac` inside one has no better answer.
   */
  val CLASS_LIKE: Set<Int> = setOf(CLASS, INTERFACE, STRUCT, ENUM, MODULE)
}

/**
 * The real cache, over `vscode.executeDocumentSymbolProvider`.
 *
 * [ask] is injectable so that a test can answer without a language server; the default is the
 * command itself.
 */
class VsCodeDocumentSymbols(
  private val ask: (Uri, (dynamic) -> Unit) -> Unit = ::requestDocumentSymbols,
  /** How long after an edit to ask again. Long enough that typing a word asks once. */
  private val settleMillis: Int = 250,
  /** How that waiting is done. A timer in a window; see [Settle.NOW] for why a test wants a say. */
  private val settle: Settle = Settle.TIMER,
) : DocumentSymbols {

  private class Entry(val version: Int, val symbols: List<SymbolRange>)

  private val cached: MutableMap<String, Entry> = mutableMapOf()

  /** The version a request is out for, so that typing does not open one request per character. */
  private val inFlight: MutableMap<String, Int> = mutableMapOf()

  private val settling: MutableMap<String, () -> Unit> = mutableMapOf()

  override fun of(document: TextDocument): List<SymbolRange>? {
    val entry = cached[keyOf(document)]
    if (entry != null && entry.version == document.version) return entry.symbols
    // Stale or absent. Answering null is the honest thing and starting a refresh is the useful
    // one: the next press of the same key finds it there.
    refresh(document)
    return null
  }

  override fun refresh(document: TextDocument) {
    val key = keyOf(document)
    val version = document.version
    if (cached[key]?.version == version) return
    if (inFlight[key] == version) return

    // A pending settle is for an older version, and this one supersedes it.
    settling.remove(key)?.invoke()
    settling[key] = settle.after(settleMillis) {
      settling.remove(key)
      request(document, key, version)
    }
  }

  override fun forget(document: TextDocument) {
    val key = keyOf(document)
    cached.remove(key)
    inFlight.remove(key)
    settling.remove(key)?.invoke()
  }

  private fun request(document: TextDocument, key: String, version: Int) {
    inFlight[key] = version
    ask(document.uri) { answer ->
      if (inFlight[key] == version) inFlight.remove(key)
      // The document may have moved on while the language server was thinking, and symbols for a
      // version that is gone are worse than none - every offset in them is wrong. Dropping them
      // leaves the cache stale, which the next `of` turns into another request.
      if (document.version != version) return@ask
      cached[key] = Entry(version, symbolsFrom(answer, document))
    }
  }

  private fun keyOf(document: TextDocument): String =
    document.uri.scheme + "://" + document.uri.path
}

/**
 * Flattens VS Code's answer into offsets, depth first, parents before children.
 *
 * Two shapes come back and both have to be read. A modern provider returns `DocumentSymbol[]`, a
 * tree with `range`, `selectionRange` and `children`. An older one returns `SymbolInformation[]`,
 * a flat list whose range is under `location` and which has no `selectionRange` at all - there the
 * name offset is the range's own start, which costs `am` its distinction from `aM` and nothing
 * else.
 */
private fun symbolsFrom(answer: dynamic, document: TextDocument): List<SymbolRange> {
  if (answer == null || answer == undefined) return emptyList()
  val out = mutableListOf<SymbolRange>()
  collectSymbols(answer, document, out)
  return out
}

private fun collectSymbols(nodes: dynamic, document: TextDocument, out: MutableList<SymbolRange>) {
  val length = (nodes.length as? Int) ?: return
  for (index in 0 until length) {
    val node = nodes[index]
    val range = node.range ?: node.location?.range ?: continue
    val kind = (node.kind as? Int) ?: continue
    val start = document.offsetAt(range.start)
    val end = document.offsetAt(range.end)
    val name = node.selectionRange?.start ?: range.start
    out += SymbolRange(kind = kind, start = start, end = end, nameStart = document.offsetAt(name))
    val children = node.children
    if (children != null) collectSymbols(children, document, out)
  }
}

/**
 * The command itself. `vscode.executeDocumentSymbolProvider` is one of VS Code's "execute a
 * provider" commands - the documented way for an extension to reach a language server it does not
 * own - and it resolves with whatever that server said, or with nothing at all when the file's
 * language has no symbol provider.
 */
private fun requestDocumentSymbols(uri: Uri, onAnswer: (dynamic) -> Unit) {
  commands.executeCommand(VsCodeCommands.DOCUMENT_SYMBOLS, uri).then(
    { answer -> onAnswer(answer) },
    // A provider that throws is a provider that has nothing to say. There is no user to tell.
    { onAnswer(null) },
  )
}

/**
 * How the cache waits before asking again.
 *
 * There is a real reason for this to be injectable rather than a call to `setTimeout`: everything
 * else about the cache - what is fresh, what is stale, which request is superseded by which - is
 * ordinary logic that a test should be able to run through end to end without an event loop. With
 * a timer in the middle it could not.
 */
fun interface Settle {
  /** Runs [action] after [millis], and returns the function that cancels it. */
  fun after(millis: Int, action: () -> Unit): () -> Unit

  companion object {
    /** A real window: debounced, so that typing a word asks once rather than once per letter. */
    val TIMER: Settle = Settle { millis, action ->
      val handle = setTimeout(action, millis)
      ({ clearTimeout(handle) })
    }

    /** A test: now, and nothing to cancel. */
    val NOW: Settle = Settle { _, action ->
      action()
      ({})
    }
  }
}

private external fun setTimeout(handler: () -> Unit, timeout: Int): Int

private external fun clearTimeout(handle: Int)
