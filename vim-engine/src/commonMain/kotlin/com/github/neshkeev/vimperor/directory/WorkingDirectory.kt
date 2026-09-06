/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.directory
import com.maddyhome.idea.vim.annotations.TestOnly
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector

/**
 * Vim's current directory, which neither host has one of.
 *
 * A project or a workspace is not a working directory: it is a set of roots, and the IDE resolves a
 * relative path against whichever of them it likes. That is why `:pwd` reported the project root
 * and why there was nothing for `:cd` to change. This is the missing thing - one directory the
 * *engine* owns, which every engine command resolves a relative path against.
 *
 * **Nothing changes until somebody runs `:cd`.** [resolve] hands a relative path back untouched
 * while no directory has been set, so `:e foo.txt` still goes to the host and the host still
 * resolves it the way it always has. That is deliberate: adding a current directory should not
 * quietly move every relative path in every existing config, and the property is easy to state and
 * easy to test.
 *
 * Global and window-local, as Vim's are: `:cd` sets the global one and clears the window's,
 * `:lcd` sets the window's alone. Vim's `:tcd` is tab-local, and a tab here is a window - see
 * [ChangeDirectoryCommand].
 */
object WorkingDirectory {

  private var global: String? = null
  private var previousGlobal: String? = null

  /** Per window, keyed the way marks are keyed: by the file the window is showing. */
  private val local = mutableMapOf<String, String>()
  private val previousLocal = mutableMapOf<String, String>()

  /**
   * The directory in effect for [editor], or null when none has been set.
   *
   * Null rather than the host's root, because "no `:cd` has happened" is exactly the case [resolve]
   * has to leave alone. `:pwd` asks for the host's root itself when this answers null.
   */
  fun currentOrNull(editor: VimEditor): String? {
    editor.getPath()?.let { path -> local[path]?.let { return it } }
    return global
  }

  /** What `:pwd` prints: the engine's directory when there is one, and the host's root otherwise. */
  fun current(editor: VimEditor, context: ExecutionContext): String? =
    currentOrNull(editor) ?: injector.file.getWorkingDirectory(context)

  fun setGlobal(path: String) {
    previousGlobal = global
    global = path
    // Vim's `:cd` clears the window-local directory, so that the window follows the global one
    // again. Clearing every window's is the same thing in a host where `:cd` is not per window.
    local.clear()
  }

  fun setLocal(editor: VimEditor, path: String) {
    val key = editor.getPath() ?: return setGlobal(path)
    previousLocal[key] = local[key] ?: global ?: ""
    local[key] = path
  }

  /** `:cd -` - where you were before. Null when there is nowhere to go back to. */
  fun previous(editor: VimEditor): String? {
    editor.getPath()?.let { key -> if (key in local) return previousLocal[key]?.takeIf { it.isNotEmpty() } }
    return previousGlobal
  }

  /**
   * A path the user typed, made absolute against the current directory.
   *
   * Untouched when no `:cd` has run, which is the property described at the top: the hosts keep
   * resolving relative paths exactly as they did, and only a session that has asked for a current
   * directory gets one.
   */
  fun resolve(path: String, editor: VimEditor): String {
    val expanded = injector.pathExpansion.expandPath(path.trim())
    if (expanded.isEmpty() || isAbsolute(expanded)) return expanded
    val directory = currentOrNull(editor) ?: return expanded
    return "${directory.trimEnd('/', '\\')}/$expanded"
  }

  fun isAbsolute(path: String): Boolean =
    path.startsWith("/") || path.startsWith("~") || path.startsWith("\\\\") ||
      (path.length > 2 && path[1] == ':')

  @TestOnly
  fun reset() {
    global = null
    previousGlobal = null
    local.clear()
    previousLocal.clear()
  }
}
