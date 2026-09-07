/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.github.neshkeev.vimperor.api.*
import com.maddyhome.idea.vim.annotations.Internal
import com.maddyhome.idea.vim.api.*
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

/**
 * A [VimInjector] with nothing implemented yet.
 *
 * Phase 2 starts here. The engine reaches the outside world through this one interface, so a
 * headless host is exactly the set of these members that a given piece of behaviour touches - and
 * nobody knows that set from reading the code. Every member throws with its own name, so running a
 * command says which service it wants next, and the host grows to fit real demand instead of a
 * guess about it.
 *
 * Subclasses override what they need. The `*Base` classes in `commonMain` carry most of the logic
 * already; a host supplies the parts that touch an editor.
 */
abstract class HeadlessInjectorBase : VimInjector {
  override val vimState: VimStateMachine
    get() = TODO("headless host does not provide vimState yet")

  override val fallbackWindow: VimEditor
    get() = TODO("headless host does not provide fallbackWindow yet")

  override val parser: VimStringParser
    get() = TODO("headless host does not provide parser yet")

  override val messages: VimMessages
    get() = TODO("headless host does not provide messages yet")

  override val registerGroup: VimRegisterGroup
    get() = TODO("headless host does not provide registerGroup yet")

  override val registerGroupIfCreated: VimRegisterGroup?
    get() = TODO("headless host does not provide registerGroupIfCreated yet")

  override val processGroup: VimProcessGroup
    get() = TODO("headless host does not provide processGroup yet")

  override val application: VimApplication
    get() = TODO("headless host does not provide application yet")

  override val executionContextManager: ExecutionContextManager
    get() = TODO("headless host does not provide executionContextManager yet")

  override val digraphGroup: VimDigraphGroup
    get() = TODO("headless host does not provide digraphGroup yet")

  override val enabler: VimEnabler
    get() = TODO("headless host does not provide enabler yet")

  override val optionGroup: VimOptionGroup
    get() = TODO("headless host does not provide optionGroup yet")

  override val nativeActionManager: NativeActionManager
    get() = TODO("headless host does not provide nativeActionManager yet")

  override val keyGroup: VimKeyGroup
    get() = TODO("headless host does not provide keyGroup yet")

  override val keymapGroup: VimKeymapGroup
    get() = TODO("headless host does not provide keymapGroup yet")

  override val abbreviationGroup: VimAbbreviationGroup
    get() = TODO("headless host does not provide abbreviationGroup yet")

  override val markService: VimMarkService
    get() = TODO("headless host does not provide markService yet")

  override val jumpService: VimJumpService
    get() = TODO("headless host does not provide jumpService yet")

  override val visualMotionGroup: VimVisualMotionGroup
    get() = TODO("headless host does not provide visualMotionGroup yet")

  override val engineEditorHelper: EngineEditorHelper
    get() = TODO("headless host does not provide engineEditorHelper yet")

  override val editorGroup: VimEditorGroup
    get() = TODO("headless host does not provide editorGroup yet")

  override val commandGroup: VimCommandGroup
    get() = TODO("headless host does not provide commandGroup yet")

  override val changeGroup: VimChangeGroup
    get() = TODO("headless host does not provide changeGroup yet")

  override val actionExecutor: VimActionExecutor
    get() = TODO("headless host does not provide actionExecutor yet")

  override val clipboardManager: VimClipboardManager
    get() = TODO("headless host does not provide clipboardManager yet")

  override val historyGroup: VimHistory
    get() = TODO("headless host does not provide historyGroup yet")

  override val extensionRegistrator: VimExtensionRegistrator
    get() = TODO("headless host does not provide extensionRegistrator yet")

  override val jsonExtensionProvider: JsonExtensionProvider
    get() = TODO("headless host does not provide jsonExtensionProvider yet")

  override val extensionLoader: ExtensionLoader
    get() = TODO("headless host does not provide extensionLoader yet")

  override val tabService: TabService
    get() = TODO("headless host does not provide tabService yet")

  override val regexpService: VimRegexpService
    get() = TODO("headless host does not provide regexpService yet")

  override val searchHelper: VimSearchHelper
    get() = TODO("headless host does not provide searchHelper yet")

  override val motion: VimMotionGroup
    get() = TODO("headless host does not provide motion yet")

  override val scroll: VimScrollGroup
    get() = TODO("headless host does not provide scroll yet")

  override val lookupManager: VimLookupManager
    get() = TODO("headless host does not provide lookupManager yet")

  override val templateManager: VimTemplateManager
    get() = TODO("headless host does not provide templateManager yet")

  override val searchGroup: VimSearchGroup
    get() = TODO("headless host does not provide searchGroup yet")

  override val virtualBufferGroup: VirtualBufferGroup
    get() = TODO("headless host does not provide virtualBufferGroup yet")

  override val searchWindowGroup: SearchWindowGroup
    get() = TODO("headless host does not provide searchWindowGroup yet")

  override val statisticsService: VimStatistics
    get() = TODO("headless host does not provide statisticsService yet")

  override val spellcheckerService: SpellcheckerService
    get() = TODO("headless host does not provide spellcheckerService yet")

  override val put: VimPut
    get() = TODO("headless host does not provide put yet")

  override val window: VimWindowGroup
    get() = TODO("headless host does not provide window yet")

  override val yank: VimYankGroup
    get() = TODO("headless host does not provide yank yet")

  override val file: VimFile
    get() = TODO("headless host does not provide file yet")

  override val macro: VimMacro
    get() = TODO("headless host does not provide macro yet")

  override val undo: VimUndoRedo
    get() = TODO("headless host does not provide undo yet")

  override val lineChange: LineChange
    get() = TODO("headless host does not provide lineChange yet")

  override val psiService: VimPsiService
    get() = TODO("headless host does not provide psiService yet")

  override val commentService: VimCommentService
    get() = TODO("headless host does not provide commentService yet")

  /** Nothing headless has a file tree, and nothing headless runs `NERDTree`. */
  override val fileTree: VimFileTreeService
    get() = TODO("headless host has no file tree")

  /** Nothing headless has a window, so there is nothing to make bigger. */
  override val windowResize: VimWindowResizeService
    get() = TODO("headless host has no windows to resize")

  override val vimscriptExecutor: VimscriptExecutor
    get() = TODO("headless host does not provide vimscriptExecutor yet")

  override val vimscriptParser: VimscriptParser
    get() = TODO("headless host does not provide vimscriptParser yet")

  override val variableService: VariableService
    get() = TODO("headless host does not provide variableService yet")

  override val modalInput: VimModalInputService
    get() = TODO("headless host does not provide modalInput yet")

  override val commandLine: VimCommandLineService
    get() = TODO("headless host does not provide commandLine yet")

  override val outputPanel: VimOutputPanelService
    get() = TODO("headless host does not provide outputPanel yet")

  override val functionService: VimscriptFunctionService
    get() = TODO("headless host does not provide functionService yet")

  override val vimrcFileState: VimrcFileState
    get() = TODO("headless host does not provide vimrcFileState yet")

  override val systemInfoService: SystemInfoService
    get() = TODO("headless host does not provide systemInfoService yet")

  override val timerService: VimTimerService
    get() = TODO("headless host does not provide timerService yet")

  override val fileSystem: VimFileSystem
    get() = TODO("headless host does not provide fileSystem yet")

  override val vimStorageService: VimStorageService
    get() = TODO("headless host does not provide vimStorageService yet")

  override val listenersNotifier: VimListenersNotifier
    get() = TODO("headless host does not provide listenersNotifier yet")

  override val autoCmd: AutoCmdService
    get() = TODO("headless host does not provide autoCmd yet")

  override val redrawService: VimRedrawService
    get() = TODO("headless host does not provide redrawService yet")

  override val pluginService: VimPluginService
    get() = TODO("headless host does not provide pluginService yet")

  override val highlightingService: VimHighlightingService
    get() = TODO("headless host does not provide highlightingService yet")

  override val pathExpansion: VimPathExpansion
    get() = TODO("headless host does not provide pathExpansion yet")

  override val pluginActivator: VimPluginActivator
    get() = TODO("headless host does not provide pluginActivator yet")

  override val externalOpener: VimExternalOpener
    get() = TODO("headless host does not provide externalOpener yet")
}
