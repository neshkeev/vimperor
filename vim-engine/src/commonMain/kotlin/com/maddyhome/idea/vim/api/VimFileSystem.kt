/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

/**
 * Reading files the engine is asked to source (`:source`, the `.ideavimrc`).
 *
 * Paths are plain strings rather than a path type, because the engine only ever passes back a path
 * the host itself produced, and every host spells paths differently.
 */
interface VimFileSystem {
  /**
   * The entire contents of [path] as text.
   *
   * @throws VimFileReadException if the file cannot be read, for any reason.
   */
  fun readText(path: String): String
}

/**
 * The message is part of the contract: it is shown to the user verbatim when `:source` fails, and
 * matches the wording that was produced when this was an `IOException` caught in the engine.
 */
class VimFileReadException(val path: String, val reason: String?) :
  Exception("Cannot read file \"$path\": $reason")
