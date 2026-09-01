/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.annotations.Internal
import com.maddyhome.idea.vim.common.VimListenersNotifier
import com.maddyhome.idea.vim.diagnostic.VimLogger
import com.maddyhome.idea.vim.extension.ExtensionLoader
import com.maddyhome.idea.vim.extension.JsonExtensionProvider
import com.maddyhome.idea.vim.group.TabService
import com.maddyhome.idea.vim.group.VimWindowGroup
import com.maddyhome.idea.vim.history.VimHistory
import com.maddyhome.idea.vim.macro.VimMacro
import com.maddyhome.idea.vim.put.VimPut
import com.maddyhome.idea.vim.register.VimRegisterGroup
import com.maddyhome.idea.vim.state.VimStateMachine
import com.maddyhome.idea.vim.thinapi.VimHighlightingService
import com.maddyhome.idea.vim.thinapi.VimPluginService
import com.maddyhome.idea.vim.undo.LineChange
import com.maddyhome.idea.vim.undo.VimUndoRedo
import com.maddyhome.idea.vim.vimscript.services.VariableService
import com.maddyhome.idea.vim.yank.VimYankGroup
import kotlin.reflect.KClass
// The injector names most of its services from its own package, so they arrive unqualified.
import com.maddyhome.idea.vim.api.*

/**
 * A [VimInjector] with nothing implemented yet.
 *
 * The same scaffolding the headless host was built on, and built for the same reason: the engine
 * reaches the outside world through this one interface, and nobody knows which of its seventy
 * members a given piece of behaviour touches by reading the code. Every member throws with its own
 * name, so running a command in VS Code says which service it wants next and the host grows to fit
 * real demand instead of a guess about it.
 *
 * This duplicates `HeadlessInjectorBase` rather than sharing with it. That is deliberate for now:
 * the headless host lives in the engine's test sources and is not visible here, and the two hosts
 * will disagree about most of what a host supplies - "no window, truthfully" is right for a headless
 * buffer and wrong for an editor with a viewport, a clipboard and a keymap. Extracting the part
 * that is genuinely shared is worth doing once both hosts exist and the overlap is a fact rather
 * than a prediction.
 */
abstract class VsCodeInjectorBase : VimInjector {
  override val vimState: VimStateMachine
    get() = TODO("the VS Code host does not provide vimState yet")

  override val fallbackWindow: VimEditor
    get() = TODO("the VS Code host does not provide fallbackWindow yet")

  override val parser: VimStringParser
    get() = TODO("the VS Code host does not provide parser yet")

  override val messages: VimMessages
    get() = TODO("the VS Code host does not provide messages yet")

  override val registerGroup: VimRegisterGroup
    get() = TODO("the VS Code host does not provide registerGroup yet")

  override val registerGroupIfCreated: VimRegisterGroup?
    get() = TODO("the VS Code host does not provide registerGroupIfCreated yet")


  override val application: VimApplication
    get() = TODO("the VS Code host does not provide application yet")

  override val executionContextManager: ExecutionContextManager
    get() = TODO("the VS Code host does not provide executionContextManager yet")

  override val digraphGroup: VimDigraphGroup
    get() = TODO("the VS Code host does not provide digraphGroup yet")

  override val enabler: VimEnabler
    get() = TODO("the VS Code host does not provide enabler yet")

  override val optionGroup: VimOptionGroup
    get() = TODO("the VS Code host does not provide optionGroup yet")

  override val nativeActionManager: NativeActionManager
    get() = TODO("the VS Code host does not provide nativeActionManager yet")

  override val keyGroup: VimKeyGroup
    get() = TODO("the VS Code host does not provide keyGroup yet")

  override val keymapGroup: VimKeymapGroup
    get() = TODO("the VS Code host does not provide keymapGroup yet")

  override val abbreviationGroup: VimAbbreviationGroup
    get() = TODO("the VS Code host does not provide abbreviationGroup yet")

  override val markService: VimMarkService
    get() = TODO("the VS Code host does not provide markService yet")

  override val jumpService: VimJumpService
    get() = TODO("the VS Code host does not provide jumpService yet")

  override val visualMotionGroup: VimVisualMotionGroup
    get() = TODO("the VS Code host does not provide visualMotionGroup yet")

  override val engineEditorHelper: EngineEditorHelper
    get() = TODO("the VS Code host does not provide engineEditorHelper yet")

  override val editorGroup: VimEditorGroup
    get() = TODO("the VS Code host does not provide editorGroup yet")

  override val commandGroup: VimCommandGroup
    get() = TODO("the VS Code host does not provide commandGroup yet")

  override val changeGroup: VimChangeGroup
    get() = TODO("the VS Code host does not provide changeGroup yet")

  override val actionExecutor: VimActionExecutor
    get() = TODO("the VS Code host does not provide actionExecutor yet")

  override val clipboardManager: VimClipboardManager
    get() = TODO("the VS Code host does not provide clipboardManager yet")

  override val historyGroup: VimHistory
    get() = TODO("the VS Code host does not provide historyGroup yet")

  override val extensionRegistrator: VimExtensionRegistrator
    get() = TODO("the VS Code host does not provide extensionRegistrator yet")

  override val jsonExtensionProvider: JsonExtensionProvider
    get() = TODO("the VS Code host does not provide jsonExtensionProvider yet")

  override val extensionLoader: ExtensionLoader
    get() = TODO("the VS Code host does not provide extensionLoader yet")

  override val tabService: TabService
    get() = TODO("the VS Code host does not provide tabService yet")

  override val regexpService: VimRegexpService
    get() = TODO("the VS Code host does not provide regexpService yet")

  override val searchHelper: VimSearchHelper
    get() = TODO("the VS Code host does not provide searchHelper yet")

  override val motion: VimMotionGroup
    get() = TODO("the VS Code host does not provide motion yet")

  override val scroll: VimScrollGroup
    get() = TODO("the VS Code host does not provide scroll yet")

  override val lookupManager: VimLookupManager
    get() = TODO("the VS Code host does not provide lookupManager yet")

  override val templateManager: VimTemplateManager
    get() = TODO("the VS Code host does not provide templateManager yet")

  override val searchGroup: VimSearchGroup
    get() = TODO("the VS Code host does not provide searchGroup yet")

  override val virtualBufferGroup: VirtualBufferGroup
    get() = TODO("the VS Code host does not provide virtualBufferGroup yet")

  override val searchWindowGroup: SearchWindowGroup
    get() = TODO("the VS Code host does not provide searchWindowGroup yet")

  override val statisticsService: VimStatistics
    get() = TODO("the VS Code host does not provide statisticsService yet")

  override val spellcheckerService: SpellcheckerService
    get() = TODO("the VS Code host does not provide spellcheckerService yet")

  override val put: VimPut
    get() = TODO("the VS Code host does not provide put yet")

  override val window: VimWindowGroup
    get() = TODO("the VS Code host does not provide window yet")

  override val yank: VimYankGroup
    get() = TODO("the VS Code host does not provide yank yet")

  override val file: VimFile
    get() = TODO("the VS Code host does not provide file yet")

  override val macro: VimMacro
    get() = TODO("the VS Code host does not provide macro yet")

  override val undo: VimUndoRedo
    get() = TODO("the VS Code host does not provide undo yet")

  override val lineChange: LineChange
    get() = TODO("the VS Code host does not provide lineChange yet")

  override val psiService: VimPsiService
    get() = TODO("the VS Code host does not provide psiService yet")

  override val vimscriptExecutor: VimscriptExecutor
    get() = TODO("the VS Code host does not provide vimscriptExecutor yet")

  override val vimscriptParser: VimscriptParser
    get() = TODO("the VS Code host does not provide vimscriptParser yet")

  override val variableService: VariableService
    get() = TODO("the VS Code host does not provide variableService yet")

  override val modalInput: VimModalInputService
    get() = TODO("the VS Code host does not provide modalInput yet")

  override val commandLine: VimCommandLineService
    get() = TODO("the VS Code host does not provide commandLine yet")

  override val outputPanel: VimOutputPanelService
    get() = TODO("the VS Code host does not provide outputPanel yet")

  override val functionService: VimscriptFunctionService
    get() = TODO("the VS Code host does not provide functionService yet")

  override val vimrcFileState: VimrcFileState
    get() = TODO("the VS Code host does not provide vimrcFileState yet")

  override val systemInfoService: SystemInfoService
    get() = TODO("the VS Code host does not provide systemInfoService yet")

  override val timerService: VimTimerService
    get() = TODO("the VS Code host does not provide timerService yet")

  override val fileSystem: VimFileSystem
    get() = TODO("the VS Code host does not provide fileSystem yet")

  override val vimStorageService: VimStorageService
    get() = TODO("the VS Code host does not provide vimStorageService yet")

  override val listenersNotifier: VimListenersNotifier
    get() = TODO("the VS Code host does not provide listenersNotifier yet")

  override val autoCmd: AutoCmdService
    get() = TODO("the VS Code host does not provide autoCmd yet")

  override val redrawService: VimRedrawService
    get() = TODO("the VS Code host does not provide redrawService yet")

  override val pluginService: VimPluginService
    get() = TODO("the VS Code host does not provide pluginService yet")

  override val highlightingService: VimHighlightingService
    get() = TODO("the VS Code host does not provide highlightingService yet")

  override val pathExpansion: VimPathExpansion
    get() = TODO("the VS Code host does not provide pathExpansion yet")

  override val pluginActivator: VimPluginActivator
    get() = TODO("the VS Code host does not provide pluginActivator yet")

}
