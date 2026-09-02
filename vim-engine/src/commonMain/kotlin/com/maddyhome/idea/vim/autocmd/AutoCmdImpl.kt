/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.autocmd

import com.maddyhome.idea.vim.api.AutoCmdService
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.state.mode.Mode

/**
 * `:autocmd` and `:augroup` - a registry of commands to run when something happens to a buffer.
 *
 * Nothing in here is IntelliJ-shaped, and it lived in the IntelliJ module anyway, so this port had
 * `:autocmd` reporting "Not implemented yet :(" - and no sweep could say so, because a bare
 * `:autocmd` is a Vim error before it reaches this. What a host has to supply is the *events*, not
 * the registry: which is why the two hosts share this file and fire it from their own listeners.
 *
 * The collections are plain rather than concurrent, and the iteration is over a copy. IdeaVim's
 * version used `ConcurrentHashMap` and `CopyOnWriteArrayList`; the copy is what those were actually
 * buying here, since an autocommand can register another one while the list is being walked, and
 * every caller of this is on the UI thread in one host and on the only thread in the other.
 */
class AutoCmdImpl : AutoCmdService {

  private val eventHandlers: MutableMap<AutoCmdEvent, MutableList<AuCommand>> = mutableMapOf()
  private var currentAugroup: String? = null

  override var eventsSuppressed: Boolean = false

  override fun registerEventCommand(command: String, event: AutoCmdEvent, pattern: String) {
    eventHandlers.getOrPut(event.canonical) { mutableListOf() }
      .add(AuCommand(command, currentAugroup, AutoCmdPattern(pattern)))
  }

  override fun clearEvents() {
    val group = currentAugroup
    if (group != null) {
      clearAugroup(group)
      return
    }
    eventHandlers.clear()
  }

  override fun startAugroup(name: String) {
    currentAugroup = name
  }

  override fun endAugroup() {
    currentAugroup = null
  }

  override fun clearAugroup(name: String) {
    eventHandlers.values.forEach { handlers ->
      handlers.removeAll { it.group == name }
    }
  }

  override fun handleEvent(event: AutoCmdEvent, filePath: String?, editor: VimEditor?) {
    if (eventsSuppressed) return
    val resolvedEditor = editor ?: injector.editorGroup.getSelectedEditor() ?: return
    val path = filePath ?: resolvedEditor.getPath()
    val handlers = eventHandlers[event.canonical] ?: return
    val context = injector.executionContextManager.getEditorExecutionContext(resolvedEditor)
    handlers.toList().forEach { auCommand ->
      if (auCommand.pattern.matches(path)) {
        if (event.runsInNormalMode && resolvedEditor.mode.isInsertOrReplace) {
          injector.changeGroup.processEscape(resolvedEditor, context)
        }
        injector.vimscriptExecutor.execute(auCommand.command, resolvedEditor, context, skipHistory = true)
      }
    }
  }
}

// Vim treats Replace mode like Insert for these purposes (`:help InsertEnter`).
private val Mode.isInsertOrReplace: Boolean
  get() = this is Mode.INSERT || this is Mode.REPLACE
