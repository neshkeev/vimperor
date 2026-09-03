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

  /**
   * Writes [content] to [path], replacing whatever was there.
   *
   * Returns the reason it could not, rather than throwing, because both callers print it: `:mkvimrc`
   * reports `E739` naming the file. Directories are not created - Vim writes into a directory that
   * is already there and says so when it is not.
   */
  fun writeText(path: String, content: String): String?

  /**
   * Whether there is a file at [path].
   *
   * Asked before writing rather than after, because Vim's `E189` is a refusal and not a report: a
   * `:mkvimrc` with no `!` must leave the file that is already there completely alone.
   */
  fun exists(path: String): Boolean

  /** Whether [path] is a directory, which decides whether a glob may descend into it. */
  fun isDirectory(path: String): Boolean = false

  /**
   * The names directly inside [path] - files and directories alike, without their path in front.
   *
   * Empty for anything that is not a directory, and for a directory that cannot be read. `:vimgrep`
   * is the caller, and a glob over a tree it may not enter should find fewer files rather than fail.
   */
  fun listDirectory(path: String): List<String> = emptyList()
}

/**
 * The message is part of the contract: it is shown to the user verbatim when `:source` fails, and
 * matches the wording that was produced when this was an `IOException` caught in the engine.
 */
class VimFileReadException(val path: String, val reason: String?) :
  Exception("Cannot read file \"$path\": $reason")
