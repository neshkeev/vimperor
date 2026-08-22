/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.action

import com.maddyhome.idea.vim.action.change.LazyVimCommand
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.vimscript.model.reflectiveFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

/**
 * A [CommandProvider] that reads its command list from a JSON classpath resource written by the
 * annotation processor, and builds each handler by loading the named class.
 *
 * The class loader is what makes this JVM-only, not the JSON: the beans name their handlers as
 * strings, which only means something to a host that can resolve a name to a class.
 */
interface JsonCommandProvider : CommandProvider {
  val commandListFileName: String

  @OptIn(ExperimentalSerializationApi::class)
  override fun getCommands(): Collection<LazyVimCommand> {
    val classLoader = this.javaClass.classLoader
    val commands: List<CommandBean> = Json.decodeFromStream(getFile())
    return commands
      .groupBy { it.`class` }
      .map {
        val keys = it.value.map { bean -> injector.parser.parseKeys(bean.keys) }.toSet()
        val modes = it.value.first().modes.map { mode -> MappingMode.parseModeChar(mode) }.toSet()
        LazyVimCommand(keys, modes, it.key, reflectiveFactory(it.key, classLoader))
      }
  }

  private fun getFile(): InputStream {
    return this.javaClass.classLoader.getResourceAsStream("ksp-generated/$commandListFileName")
      ?: throw RuntimeException("Failed to fetch ex commands from ${javaClass.name}")
  }
}

@Serializable
data class CommandBean(val keys: String, val `class`: String, val modes: String)
