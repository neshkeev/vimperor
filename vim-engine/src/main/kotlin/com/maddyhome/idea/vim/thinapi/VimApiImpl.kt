/*
 * Copyright 2003-2025 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.thinapi


import com.intellij.vim.api.VimApi
import com.intellij.vim.api.models.Mode
import com.intellij.vim.api.scopes.CommandScope
import com.intellij.vim.api.scopes.DigraphScope
import com.intellij.vim.api.scopes.MappingScope
import com.intellij.vim.api.scopes.ModalInput
import com.intellij.vim.api.scopes.OptionScope
import com.intellij.vim.api.scopes.OutputPanelScope
import com.intellij.vim.api.scopes.StorageScope
import com.intellij.vim.api.scopes.TabScope
import com.intellij.vim.api.scopes.TextObjectScope
import com.intellij.vim.api.scopes.TextScope
import com.intellij.vim.api.scopes.VariableScope
import com.intellij.vim.api.scopes.commandline.CommandLineScope
import com.intellij.vim.api.scopes.editor.EditorScope
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.ListenerOwner
import com.maddyhome.idea.vim.key.MappingOwner
import com.maddyhome.idea.vim.thinapi.commandline.CommandLineScopeImpl
import com.maddyhome.idea.vim.thinapi.editor.EditorScopeImpl

/**
 * [projectId] is used to properly determine the selected editor. However,
 *   during the initialization, the projectId is null and the editor falls back to the fallback editor.
 */
class VimApiImpl(
  private val listenerOwner: ListenerOwner,
  private val mappingOwner: MappingOwner,
  val projectId: String?,
) : VimApi {
  override val mode: Mode
    get() = injector.vimState.mode.toMode()

  private val vimEditor: VimEditor
    get() = projectId?.let { injector.editorGroup.getSelectedEditor(it) } ?: injector.fallbackWindow

  private val vimContext: ExecutionContext
    get() = injector.executionContextManager.getEditorExecutionContext(vimEditor)

  override fun <T> variables(block: VariableScope.() -> T): T {
    val variableScope = VariableScopeImpl(vimEditor, vimContext)
    return variableScope.block()
  }

  override fun variables(): VariableScope {
    return VariableScopeImpl(vimEditor, vimContext)
  }

  override fun <T> commands(block: CommandScope.() -> T): T {
    val commandScope = CommandScopeImpl(listenerOwner, mappingOwner)
    return commandScope.block()
  }

  override fun commands(): CommandScope {
    return CommandScopeImpl(listenerOwner, mappingOwner)
  }

  override fun normal(command: String) {
    injector.pluginService.executeNormalWithoutMapping(command, vimEditor)
  }

  override fun <T> editor(block: EditorScope.() -> T): T {
    return EditorScopeImpl(projectId).block()
  }

  override fun <T> forEachEditor(block: EditorScope.() -> T): List<T> {
    return injector.editorGroup.getEditors().map { editor ->
      val editorScope = EditorScopeImpl(projectId)
      editorScope.block()
    }
  }

  override fun <T> mappings(block: MappingScope.() -> T): T {
    val mappingScope = MappingScopeImpl(listenerOwner, mappingOwner)
    return mappingScope.block()
  }

  override fun mappings(): MappingScope {
    return MappingScopeImpl(listenerOwner, mappingOwner)
  }

  override fun <T> textObjects(block: TextObjectScope.() -> T): T {
    val pluginName = (mappingOwner as? MappingOwner.Plugin)?.name ?: "unknown"
    val textObjectScope = TextObjectScopeImpl(pluginName, listenerOwner, mappingOwner)
    return textObjectScope.block()
  }

  override fun textObjects(): TextObjectScope {
    val pluginName = (mappingOwner as? MappingOwner.Plugin)?.name ?: "unknown"
    return TextObjectScopeImpl(pluginName, listenerOwner, mappingOwner)
  }

//  override fun listeners(block: ListenersScope.() -> Unit) {
//    val listenersScope = ListenerScopeImpl(listenerOwner, mappingOwner)
//    listenersScope.block()
//  }

  override fun <T> outputPanel(block: OutputPanelScope.() -> T): T {
    val outputPanelScope = OutputPanelScopeImpl(projectId)
    return outputPanelScope.block()
  }

  override fun outputPanel(): OutputPanelScope {
    return OutputPanelScopeImpl(projectId)
  }

  override fun modalInput(): ModalInput {
    return ModalInputImpl(listenerOwner, mappingOwner, projectId)
  }

  override fun <T> commandLine(block: CommandLineScope.() -> T): T {
    val commandLineScope = CommandLineScopeImpl(listenerOwner, mappingOwner, projectId)
    return commandLineScope.block()
  }

  override fun commandLine(): CommandLineScope {
    return CommandLineScopeImpl(listenerOwner, mappingOwner, projectId)
  }

  override fun <T> option(block: OptionScope.() -> T): T {
    return OptionScopeImpl(projectId).block()
  }

  override fun <T> digraph(block: DigraphScope.() -> T): T {
    val digraphScope = DigraphScopeImpl(projectId)
    return digraphScope.block()
  }

  override fun digraph(): DigraphScope {
    return DigraphScopeImpl(projectId)
  }

  override fun <T> tabs(block: TabScope.() -> T): T {
    val tabScope = TabScopeImpl(vimContext)
    return tabScope.block()
  }

  override fun tabs(): TabScope {
    return TabScopeImpl(vimContext)
  }

  override fun <T> text(block: TextScope.() -> T): T {
    val textScope = TextScopeImpl()
    return textScope.block()
  }

  override fun text(): TextScope {
    return TextScopeImpl()
  }

  // Window management methods commented out — see IJPL-235369.
  // After setAsCurrentWindow(), getSelectedTextEditor() returns stale data because
  // the propagation is async and unobservable. Re-enable when the platform provides
  // a synchronous or awaitable window-switching API.
  //
  // override fun selectNextWindow() {
  //   injector.window.selectNextWindow(vimContext)
  // }
  //
  // override fun selectWindow(index: Int) {
  //   injector.window.selectWindow(vimContext, index)
  // }
  //
  // override fun selectPreviousWindow() {
  //   injector.window.selectPreviousWindow(vimContext)
  // }
  //
  // override fun splitWindowVertically(filePath: Path?) {
  //   injector.window.splitWindowVertical(vimContext, filePath?.javaPath?.pathString ?: "")
  // }
  //
  // override fun splitWindowHorizontally(filePath: Path?) {
  //   injector.window.splitWindowHorizontal(vimContext, filePath?.javaPath?.pathString ?: "")
  // }
  //
  // override fun closeAllExceptCurrentWindow() {
  //   injector.window.closeAllExceptCurrent(vimContext)
  // }
  //
  // override fun closeCurrentWindow() {
  //   injector.window.closeCurrentWindow(vimContext)
  // }
  //
  // override fun closeAllWindows() {
  //   injector.window.closeAll(vimContext)
  // }

  override fun execute(script: String): Boolean {
    val result = injector.vimscriptExecutor.execute(
      script, vimEditor, vimContext,
      skipHistory = true,
      indicateErrors = true
    )
    return result == com.maddyhome.idea.vim.vimscript.model.ExecutionResult.Success
  }


  override fun <T> storage(block: StorageScope.() -> T): T {
    val storageScope = StorageScopeImpl(vimEditor)
    return storageScope.block()
  }

  override fun storage(): StorageScope {
    return StorageScopeImpl(vimEditor)
  }

  override fun saveFile() {
    injector.file.saveFile(vimEditor, vimContext)
  }

  override fun closeFile() {
    injector.file.closeFile(vimEditor, vimContext)
  }

}
