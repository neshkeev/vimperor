/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

/** An [ExCommandProvider] backed by a JSON classpath resource and the class loader. */
interface JsonExCommandProvider : ExCommandProvider {
  val exCommandsFileName: String

  @OptIn(ExperimentalSerializationApi::class)
  override fun getCommands(): Map<String, LazyExCommandInstance> {
    val classLoader = this.javaClass.classLoader
    val commandToClass: Map<String, ExCommandBean> = Json.decodeFromStream(getFile())
    return commandToClass.entries.associate {
      it.key to lazyExCommand(it.value.`class`, it.value.standardConstructor, classLoader)
    }
  }

  private fun getFile(): InputStream {
    return this.javaClass.classLoader.getResourceAsStream("ksp-generated/$exCommandsFileName")
      ?: throw RuntimeException("Failed to fetch ex-commands for ${javaClass.name}")
  }
}

/**
 * The processor's record of one ex-command. [standardConstructor] is computed by KSP from the
 * declared types, so the JVM and a generated registry read the same answer rather than each
 * working it out.
 */
@Serializable
data class ExCommandBean(val `class`: String, val standardConstructor: Boolean)
