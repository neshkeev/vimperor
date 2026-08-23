/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.VimApplication
import com.maddyhome.idea.vim.api.VimDocument
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimEditorGroup
import com.maddyhome.idea.vim.api.VimStringParser
import com.maddyhome.idea.vim.api.VimStringParserBase
import com.maddyhome.idea.vim.api.VimOptionGroup
import com.maddyhome.idea.vim.api.VimOptionGroupBase
import com.maddyhome.idea.vim.api.VimScriptFunctionServiceBase
import com.maddyhome.idea.vim.api.SystemInfoService
import com.maddyhome.idea.vim.handler.Motion
import com.maddyhome.idea.vim.api.VimMotionGroup
import com.maddyhome.idea.vim.api.VimMotionGroupBase
import com.maddyhome.idea.vim.api.VimScrollGroup
import com.maddyhome.idea.vim.api.VimJumpService
import com.maddyhome.idea.vim.api.VimJumpServiceBase
import com.maddyhome.idea.vim.api.VimClipboardManager
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.common.VimCopiedText
import com.maddyhome.idea.vim.register.VimRegisterGroup
import com.maddyhome.idea.vim.register.VimRegisterGroupBase
import com.maddyhome.idea.vim.history.VimHistory
import com.maddyhome.idea.vim.history.VimHistoryBase
import com.maddyhome.idea.vim.impl.state.VimStateMachineImpl
import com.maddyhome.idea.vim.state.VimStateMachine
import com.maddyhome.idea.vim.api.ImmutableVimCaret
import com.maddyhome.idea.vim.api.VimSearchHelper
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimChangeGroup
import com.maddyhome.idea.vim.api.VimChangeGroupBase
import com.maddyhome.idea.vim.api.VimMarkService
import com.maddyhome.idea.vim.api.VimMarkServiceBase
import com.maddyhome.idea.vim.api.VimSearchGroup
import com.maddyhome.idea.vim.api.VimSearchGroupBase
import com.maddyhome.idea.vim.api.VimSearchHelperBase
import com.maddyhome.idea.vim.api.VimStatistics
import com.maddyhome.idea.vim.api.VimStorageService
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.Key
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.api.VimscriptFunctionService
import com.maddyhome.idea.vim.api.VimscriptParser
import com.maddyhome.idea.vim.api.VimscriptParserBase
import com.maddyhome.idea.vim.vimscript.model.functions.VimscriptFunctionProvider
import com.maddyhome.idea.vim.vimscript.model.functions.engineFunctionProvider
import com.maddyhome.idea.vim.vimscript.services.VariableService
import com.maddyhome.idea.vim.vimscript.services.VimVariableServiceBase
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.diagnostic.VimLogger
import kotlin.reflect.KClass

/**
 * The headless host, as far as it goes.
 *
 * Grown one service at a time, each because something asked for it. See [HeadlessInjectorBase] for
 * the ones that still throw.
 */
class HeadlessInjector : HeadlessInjectorBase() {

  // Every service is `by lazy`, and that is a requirement rather than a style choice. Engine
  // services read the global `injector` while constructing - `VimscriptParserBase` builds a logger
  // in its initialiser - so a host that builds its services eagerly touches `injector` before the
  // assignment that installs the host has completed. IntelliJ never hits this because its services
  // are created on first use by the platform.

  /**
   * Whole-cloth from `VimStringParserBase`, which leaves nothing abstract: turning `"<C-A>"` into
   * keystrokes is string work with no editor in it. This is what `getCommands()` needs to build the
   * command list, so supplying it is what lets the generated registry be exercised rather than
   * merely compiled.
   */
  override val parser: VimStringParser by lazy { object : VimStringParserBase() {} }

  /** Also whole-cloth: `VimscriptParserBase` leaves nothing abstract either. */
  override val vimscriptParser: VimscriptParser by lazy { object : VimscriptParserBase() {} }

  override val application: VimApplication by lazy { HeadlessApplication }

  /**
   * The engine's own builtin functions and nothing else - no host adds any here. The provider is
   * the JSON resource on the JVM and the generated registry on JS, so evaluating `strlen("abc")`
   * exercises whichever of the two this target uses.
   */
  override val functionService: VimscriptFunctionService by lazy {
    object : VimScriptFunctionServiceBase() {
      override val functionProviders: List<VimscriptFunctionProvider> = listOf(engineFunctionProvider)
    }
  }

  /**
   * Records nothing. Usage statistics are a product decision belonging to a shipped host, and the
   * engine calls this on ordinary paths - evaluating a function call reaches it - so it has to
   * exist rather than throw.
   */
  override val statisticsService: VimStatistics by lazy { HeadlessStatistics }

  /** `VimOptionGroupBase` leaves nothing abstract; the options are the engine's own defaults. */
  override val optionGroup: VimOptionGroup by lazy {
    object : VimOptionGroupBase() {}.also { it.initialiseOptions() }
  }

  /**
   * No editors. The option group asks for them when a value is read at global scope - it looks for
   * open windows to apply a changed option to - and "none open" is a truthful answer for a host
   * with no windows, not a placeholder.
   */
  override val editorGroup: VimEditorGroup by lazy { HeadlessEditorGroup }

  /**
   * Maps keyed by editor. The engine stores per-window, per-buffer and per-tab data here - local
   * option values among them - and with one buffer and no windows the three scopes cannot be told
   * apart, so they share a map rather than pretending to a distinction this host does not have.
   */
  override val vimStorageService: VimStorageService by lazy { HeadlessStorageService() }

  /**
   * Neither Windows nor X, and no environment. Option defaults branch on these - `'clipboard'`
   * consults `isXWindow` - so answering rather than throwing is what lets options initialise, and a
   * host with no operating system underneath it has no truthful alternative.
   */
  override val systemInfoService: SystemInfoService by lazy { HeadlessSystemInfo }

  /**
   * `VimSearchHelperBase` leaves nothing abstract: word motions are functions of the buffer text
   * and an offset, with no editor operation in them.
   */
  override val searchHelper: VimSearchHelper by lazy {
    object : VimSearchHelperBase() {
      // The three the base leaves to a host are all IDE features rather than Vim ones: method
      // navigation needs a syntax tree and misspelled-word navigation needs a spellchecker.
      override fun findMethodStart(editor: VimEditor, caret: ImmutableVimCaret, count: Int): Int =
        TODO("headless host has no syntax tree to find methods in")

      override fun findMethodEnd(editor: VimEditor, caret: ImmutableVimCaret, count: Int): Int =
        TODO("headless host has no syntax tree to find methods in")

      override fun findMisspelledWord(editor: VimEditor, caret: ImmutableVimCaret, count: Int): Int =
        TODO("headless host has no spellchecker")
    }
  }

  /**
   * The change group is entirely common except for `reformatCode`, which is an IDE feature: Vim's
   * `=` asks the editor to re-indent, and a buffer with no language attached cannot.
   */
  override val changeGroup: VimChangeGroup by lazy {
    object : VimChangeGroupBase() {
      override fun reformatCode(editor: VimEditor, start: Int, end: Int) =
        TODO("headless host cannot reformat code")

      // The typing path: these hand a character to the editor so it can run its own
      // auto-indent, bracket matching and completion. There is no such editor here.
      override fun processBackspace(editor: VimEditor, context: ExecutionContext) =
        TODO("headless host has no typing path")

      override fun autoIndentRange(
        editor: VimEditor,
        context: ExecutionContext,
        ranges: List<TextRange>,
        carets: List<VimCaret>,
      ) = TODO("headless host cannot auto-indent")

      override fun type(vimEditor: VimEditor, context: ExecutionContext, key: Char) =
        TODO("headless host has no typing path")

      override fun type(vimEditor: VimEditor, context: ExecutionContext, string: String) =
        TODO("headless host has no typing path")
    }
  }

  /** `VimMarkServiceBase` leaves nothing abstract; marks are offsets in a buffer. */
  override val markService: VimMarkService by lazy { object : VimMarkServiceBase() {} }

  /** `VimSearchGroupBase` holds the `:s` and `/` logic; only the highlighting is a host concern. */
  override val searchGroup: VimSearchGroup by lazy {
    object : VimSearchGroupBase() {
      // Everything the base leaves open is about what the user can *see* - `'hlsearch'`
      // highlighting, the `'incsearch'` preview, the "3 of 12" count, and the confirmation
      // highlight `:s///c` paints. A host with no window draws none of it, and `:s` works without
      // any of it, which is why the substitution itself is common code.
      override fun isSomeTextHighlighted(): Boolean = false
      override fun getCurrentIncsearchResultRange(editor: VimEditor): TextRange? = null
      override fun highlightSearchLines(editor: VimEditor, startLine: Int, endLine: Int) {}
      override fun updateSearchHighlights(force: Boolean) {}
      override fun updateSearchCount(matchOffset: Int) {}
      override fun resetIncsearchHighlights() {}
      override fun setShouldShowSearchHighlights() {}
      override fun clearSearchHighlight() {}

      override fun addSubstitutionConfirmationHighlight(
        editor: VimEditor,
        startOffset: Int,
        endOffset: Int,
      ): SearchHighlight = TODO("headless host cannot paint a confirmation highlight")
    }
  }

  /**
   * The engine ships its own state machine - `VimStateMachineImpl` is in `commonMain` - so a host
   * supplies nothing here beyond an instance. It tracks the pending command, the register in use,
   * and the digraph state.
   */
  override val vimState: VimStateMachine by lazy { VimStateMachineImpl() }

  /** `VimHistoryBase` leaves nothing abstract; the search and command histories are lists. */
  override val historyGroup: VimHistory by lazy { object : VimHistoryBase() {} }

  /**
   * `VimRegisterGroupBase` leaves nothing abstract - registers are a map from a character to text
   * plus a selection type. `:s` reaches it because the last substitute pattern goes to the `/`
   * register.
   */
  override val registerGroup: VimRegisterGroup by lazy { object : VimRegisterGroupBase() {} }

  override val registerGroupIfCreated: VimRegisterGroup? get() = registerGroup

  /**
   * An in-memory clipboard with no system behind it.
   *
   * The register group reaches this because Vim's `"*` and `"+` registers *are* the selection and
   * the clipboard. "Transferable data" - the IDE's rich-text payload that travels with a copy - has
   * no meaning here, so it is always empty.
   */
  override val clipboardManager: VimClipboardManager by lazy { HeadlessClipboardManager() }

  /** `VimJumpServiceBase` leaves nothing abstract; the jump list is a list of positions. */
  override val jumpService: VimJumpService by lazy {
    object : VimJumpServiceBase() {
      /** IntelliJ's own navigation history; there is none to add to here. */
      override fun includeCurrentCommandAsNavigation(editor: VimEditor) {}
      override var lastJumpTimeStamp: Long = 0
    }
  }

  /**
   * Nothing scrolls, because nothing is displayed. Every one of these moves a viewport, and a host
   * with no viewport has none to move - `scrollCaretIntoView` is the one `:s` reaches, after
   * jumping to a match.
   */
  override val scroll: VimScrollGroup by lazy { HeadlessScrollGroup }

  /** `VimMotionGroupBase` holds the motion logic; `:s` uses it to put the caret on the match. */
  override val motion: VimMotionGroup by lazy {
    object : VimMotionGroupBase() {
      // What the base leaves open is every motion defined in terms of the *screen* rather than the
      // buffer - `H`, `M`, `L`, `g0`, `gm`, `g$` - plus tab switching. A host with no viewport and
      // no tabs cannot answer them, and buffer motions do not go through here.
      override fun moveCaretToFirstDisplayLine(editor: VimEditor, caret: ImmutableVimCaret, count: Int, normalizeToScreen: Boolean): Int =
        TODO("headless host has no display lines or tabs: moveCaretToFirstDisplayLine")

      override fun moveCaretToMiddleDisplayLine(editor: VimEditor, caret: ImmutableVimCaret): Int =
        TODO("headless host has no display lines or tabs: moveCaretToMiddleDisplayLine")

      override fun moveCaretToLastDisplayLine(editor: VimEditor, caret: ImmutableVimCaret, count: Int, normalizeToScreen: Boolean): Int =
        TODO("headless host has no display lines or tabs: moveCaretToLastDisplayLine")

      override fun moveCaretToCurrentDisplayLineStart(editor: VimEditor, caret: ImmutableVimCaret): Motion =
        TODO("headless host has no display lines or tabs: moveCaretToCurrentDisplayLineStart")

      override fun moveCaretToCurrentDisplayLineStartSkipLeading(editor: VimEditor, caret: ImmutableVimCaret): Int =
        TODO("headless host has no display lines or tabs: moveCaretToCurrentDisplayLineStartSkipLeading")

      override fun moveCaretToCurrentDisplayLineMiddle(editor: VimEditor, caret: ImmutableVimCaret): Motion =
        TODO("headless host has no display lines or tabs: moveCaretToCurrentDisplayLineMiddle")

      override fun moveCaretToCurrentDisplayLineEnd(editor: VimEditor, caret: ImmutableVimCaret, allowEnd: Boolean): Motion =
        TODO("headless host has no display lines or tabs: moveCaretToCurrentDisplayLineEnd")

      override fun moveCaretGotoNextTab(editor: VimEditor, context: ExecutionContext, rawCount: Int): Int =
        TODO("headless host has no display lines or tabs: moveCaretGotoNextTab")

      override fun moveCaretGotoPreviousTab(editor: VimEditor, context: ExecutionContext, rawCount: Int): Int =
        TODO("headless host has no display lines or tabs: moveCaretGotoPreviousTab")
    }
  }

  /** `VimVariableServiceBase` leaves nothing abstract; variables are a map. */
  override val variableService: VariableService by lazy { object : VimVariableServiceBase() {} }

  /** Silent. A test that needs to read the log can swap this out; nothing does yet. */
  override fun <T : Any> getLogger(clazz: KClass<T>): VimLogger = SilentLogger
}

private object SilentLogger : VimLogger {
  override fun isTrace(): Boolean = false
  override fun isDebug(): Boolean = false
  override fun info(message: String) {}
  override fun warn(message: String) {}
  override fun error(message: String) {}
  override fun debug(message: String) {}
  override fun trace(data: String) {}
  override fun warn(message: String, e: Throwable) {}
  override fun error(message: String, e: Throwable) {}
}

/**
 * There is one thread and no event loop, so every "do this later" is "do this now".
 *
 * Not a stub standing in for something real: a headless host genuinely has nothing to marshal
 * between, and running the action immediately is the honest answer rather than a convenient one.
 */
private object HeadlessApplication : VimApplication {
  override fun isMainThread(): Boolean = true
  override fun invokeLater(editor: VimEditor, action: () -> Unit) = action()
  override fun invokeLater(action: () -> Unit) = action()
  override fun invokeAndWait(action: () -> Unit) = action()
  override fun isUnitTest(): Boolean = true
  override fun isInternal(): Boolean = false
  override fun <T> runWriteAction(action: () -> T): T = action()
  override fun <T> runReadAction(action: () -> T): T = action()
  override fun runAfterGotFocus(runnable: () -> Unit) = runnable()

  /** No stack traces without a host to attribute them to; only diagnostics read this. */
  override fun currentStackTrace(): String = ""

  override fun postKey(stroke: VimKeyStroke, editor: VimEditor) =
    TODO("headless host has no key queue yet")
}

private object HeadlessStatistics : VimStatistics {
  override fun logTrackedAction(actionId: String) {}
  override fun logCopiedAction(actionId: String) {}
  override fun setIfIfUsed(value: Boolean) {}
  override fun setIfFunctionCallUsed(value: Boolean) {}
  override fun setIfFunctionDeclarationUsed(value: Boolean) {}
  override fun setIfLoopUsed(value: Boolean) {}
  override fun setIfMapExprUsed(value: Boolean) {}
  override fun addExtensionEnabledWithPlug(extension: String) {}
  override fun addSourcedFile(path: String) {}
}

private object HeadlessEditorGroup : VimEditorGroup {
  override fun notifyIdeaJoin(editor: VimEditor) {}
  override fun getEditorsRaw(): Collection<VimEditor> = emptyList()
  override fun getEditors(): Collection<VimEditor> = emptyList()
  override fun getEditors(buffer: VimDocument): Collection<VimEditor> = emptyList()
  override fun updateCaretsVisualAttributes(editor: VimEditor) {}
  override fun updateCaretsVisualPosition(editor: VimEditor) {}
  override fun getFocusedEditor(): VimEditor? = null
  override fun getSelectedEditor(projectId: String): VimEditor? = null
  override fun getSelectedEditor(): VimEditor? = null
}

private class HeadlessStorageService : VimStorageService {
  private val windowData = mutableMapOf<VimEditor, MutableMap<Key<*>, Any?>>()
  private val bufferData = mutableMapOf<VimEditor, MutableMap<Key<*>, Any?>>()
  private val tabData = mutableMapOf<VimEditor, MutableMap<Key<*>, Any?>>()

  @Suppress("UNCHECKED_CAST")
  private fun <T> get(store: MutableMap<VimEditor, MutableMap<Key<*>, Any?>>, editor: VimEditor, key: Key<T>): T? =
    store[editor]?.get(key) as T?

  private fun <T> put(
    store: MutableMap<VimEditor, MutableMap<Key<*>, Any?>>,
    editor: VimEditor,
    key: Key<T>,
    data: T,
  ) {
    store.getOrPut(editor) { mutableMapOf() }[key] = data
  }

  override fun <T> getDataFromWindow(editor: VimEditor, key: Key<T>): T? = get(windowData, editor, key)
  override fun <T> putDataToWindow(editor: VimEditor, key: Key<T>, data: T) = put(windowData, editor, key, data)
  override fun <T> getDataFromBuffer(editor: VimEditor, key: Key<T>): T? = get(bufferData, editor, key)
  override fun <T> putDataToBuffer(editor: VimEditor, key: Key<T>, data: T) = put(bufferData, editor, key, data)
  override fun <T> getDataFromTab(editor: VimEditor, key: Key<T>): T? = get(tabData, editor, key)
  override fun <T> putDataToTab(editor: VimEditor, key: Key<T>, data: T) = put(tabData, editor, key, data)
}

private object HeadlessSystemInfo : SystemInfoService {
  override val isWindows: Boolean = false
  override val isXWindow: Boolean = false
  override fun getenv(name: String): String? = null
}

private class HeadlessClipboardManager : VimClipboardManager {
  private var clipboard: VimCopiedText? = null
  private var primary: VimCopiedText? = null

  override fun getPrimaryContent(editor: VimEditor, context: ExecutionContext): VimCopiedText? = primary

  override fun getClipboardContent(editor: VimEditor, context: ExecutionContext): VimCopiedText? = clipboard

  override fun setClipboardContent(
    editor: VimEditor,
    context: ExecutionContext,
    textData: VimCopiedText,
  ): Boolean {
    clipboard = textData
    return true
  }

  override fun setPrimaryContent(
    editor: VimEditor,
    context: ExecutionContext,
    textData: VimCopiedText,
    selectionType: SelectionType,
  ): Boolean {
    primary = textData
    return true
  }

  override fun setClipboardText(text: String, rawText: String, transferableData: List<Any>): Any? {
    clipboard = HeadlessCopiedText(text)
    return null
  }

  override fun collectCopiedText(
    editor: VimEditor,
    context: ExecutionContext,
    range: TextRange,
    text: String,
  ): VimCopiedText = HeadlessCopiedText(text)

  override fun dumbCopiedText(text: String): VimCopiedText = HeadlessCopiedText(text)

  override fun getTransferableData(vimEditor: VimEditor, textRange: TextRange): List<Any> = emptyList()

  override fun preprocessText(
    vimEditor: VimEditor,
    textRange: TextRange,
    text: String,
    transferableData: List<*>,
  ): String = text
}

private data class HeadlessCopiedText(override val text: String) : VimCopiedText {
  override fun updateText(newText: String): VimCopiedText = copy(text = newText)
}

private object HeadlessScrollGroup : VimScrollGroup {
  override fun scrollCaretIntoView(editor: VimEditor) {}
  override fun scrollFullPage(editor: VimEditor, caret: VimCaret, pages: Int): Boolean = false
  override fun scrollHalfPage(editor: VimEditor, caret: VimCaret, rawCount: Int, down: Boolean): Boolean = false
  override fun scrollLines(editor: VimEditor, lines: Int): Boolean = false
  override fun scrollCurrentLineToDisplayTop(editor: VimEditor, rawCount: Int, start: Boolean): Boolean = false
  override fun scrollCurrentLineToDisplayMiddle(editor: VimEditor, rawCount: Int, start: Boolean): Boolean = false
  override fun scrollCurrentLineToDisplayBottom(editor: VimEditor, rawCount: Int, start: Boolean): Boolean = false
  override fun scrollColumns(editor: VimEditor, columns: Int): Boolean = false
  override fun scrollCaretColumnToDisplayLeftEdge(vimEditor: VimEditor): Boolean = false
  override fun scrollCaretColumnToDisplayRightEdge(editor: VimEditor): Boolean = false
}
