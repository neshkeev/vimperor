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
import kotlin.io.path.readText

internal class IjVimFileSystem : VimFileSystem {
  override fun readText(path: String): String =
    try {
      Path(path).readText()
    } catch (e: IOException) {
      // The engine used to catch this itself; the message it built is now the exception's.
      throw VimFileReadException(path, e.message)
    }
}
