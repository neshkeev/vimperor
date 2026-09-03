/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.newapi

import com.maddyhome.idea.vim.api.VimFileReadException
import com.maddyhome.idea.vim.api.VimFileSystem
import java.io.IOException
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class IjVimFileSystem : VimFileSystem {
  override fun readText(path: String): String =
    try {
      Path(path).readText()
    } catch (e: IOException) {
      // The engine used to catch this itself; the message it built is now the exception's.
      throw VimFileReadException(path, e.message)
    }

  override fun writeText(path: String, content: String): String? =
    try {
      Path(path).writeText(content)
      null
    } catch (e: IOException) {
      e.message ?: "write failed"
    }

  override fun exists(path: String): Boolean = Path(path).exists()

  override fun isDirectory(path: String): Boolean = Path(path).isDirectory()

  override fun listDirectory(path: String): List<String> =
    try {
      Path(path).listDirectoryEntries().map { it.name }
    } catch (e: IOException) {
      emptyList()
    }
}
