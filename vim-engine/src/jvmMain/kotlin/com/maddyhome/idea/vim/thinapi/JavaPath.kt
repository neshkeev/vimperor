/*
 * Copyright 2003-2025 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.thinapi

import com.intellij.vim.api.models.Path
import java.nio.file.Paths
import java.nio.file.Path as JavaPath

/*
 * The two conversions between the API's Path model and java.nio's. Split out of Path.kt, which is
 * otherwise pure string manipulation: leaving these together made one JVM-only file that fourteen
 * other thinapi files transitively depended on.
 */

val Path.javaPath: JavaPath
  get() {
    val uri = "$protocol$PROTOCOL_DELIMITER${getFilePath()}"
    return Paths.get(uri)
  }

val JavaPath.vimPath: Path?
  get() {
    val pathComponents: List<String> = iterator().asSequence().map { it.toString() }.toList()
    // Protocol is the first component:
    // e.g., temp:///src/aaa.txt => protocol = "temp:", path = ("src", "aaa.txt")
    val protocolRegex = Regex("^([a-zA-Z]+):")
    val protocolString = pathComponents.firstOrNull() ?: return null
    val protocol = protocolRegex.find(protocolString)?.groupValues[1] ?: return null
    // drop protocol from list
    val components = pathComponents.drop(1)

    return object : Path {
      override val protocol: String = protocol
      override val path: Array<String> = components.toTypedArray()
    }
  }
