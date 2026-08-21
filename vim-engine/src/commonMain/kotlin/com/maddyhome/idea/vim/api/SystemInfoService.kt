/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

interface SystemInfoService {
  val isWindows: Boolean
  val isXWindow: Boolean

  /**
   * The value of an environment variable, or `null` if it is not set.
   *
   * The host provides this because not every host has a process environment to read - a browser
   * extension has none at all, and should return `null` rather than pretend.
   */
  fun getenv(name: String): String?
}
