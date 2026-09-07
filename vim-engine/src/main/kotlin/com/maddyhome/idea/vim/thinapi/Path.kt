/*
 * Copyright 2003-2025 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.thinapi

import com.intellij.vim.api.models.Path

internal const val PATH_DELIMITER = "/"
internal const val PROTOCOL_DELIMITER = "://"

/**
 * Function that will create [Path] from protocol and file path passed as function parameters.
 */
internal fun Path.Companion.createApiPath(protocol: String, filePath: String): Path {
  val pathComponents: Array<String> = filePath
    .split(PATH_DELIMITER)
    .toTypedArray()

  return object : Path {
    override val protocol: String = protocol
    override val path: Array<String> = pathComponents
  }
}

/**
 * Function that returns a string that represents filePath without a protocol.
 */
internal fun Path.getFilePath(): String {
  return path.joinToString(PATH_DELIMITER)
}
