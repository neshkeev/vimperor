/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.VimExternalOpener

/**
 * `gx` and `:help` - handing a URL to whatever the operating system opens it with.
 *
 * The key sweep never reported this missing, and could not have. It presses `gx` on a buffer with
 * no URL under the caret, so the action returns before it ever asks for this service - a hole only
 * reachable with the right *text* under the caret, not the right key.
 *
 * `openExternal` returns a promise and nothing here waits for it. That is right rather than a
 * compromise: Vim's `gx` does not wait for the browser either, and there is no answer the engine
 * could use if it did.
 */
internal class VsCodeExternalOpener(
  private val openUrl: (String) -> Unit = { env.openExternal(UriFactory.parse(it)) },
  private val runViewer: (String, String) -> Unit = ::spawnDetached,
) : VimExternalOpener {

  override fun open(target: String, viewer: String?) {
    if (viewer.isNullOrBlank()) openUrl(target) else runViewer(viewer, target)
  }
}

/**
 * A viewer from `g:netrw_browsex_viewer`, started and let go of.
 *
 * Detached and unreferenced, which is the difference from `:!`: a viewer is a window the user is
 * about to look at, and waiting for it to exit would freeze the editor until they closed it.
 */
private fun spawnDetached(viewer: String, target: String) {
  val options: dynamic = js("({})")
  options.detached = true
  options.stdio = "ignore"
  options.shell = true
  val child = childProcess.spawn(viewer, arrayOf(target), options)
  child.unref()
}

private val childProcess: dynamic = require("child_process")

private external fun require(module: String): dynamic
