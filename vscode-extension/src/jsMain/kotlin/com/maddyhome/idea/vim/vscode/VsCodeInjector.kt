/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.action.change.LazyVimCommand
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.*
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.common.VimCopiedText
import com.maddyhome.idea.vim.common.VimListenersNotifier
import com.maddyhome.idea.vim.diagnostic.VimLogger
import com.maddyhome.idea.vim.handler.EditorActionHandlerBase
import com.maddyhome.idea.vim.handler.Motion
import com.maddyhome.idea.vim.helper.EngineMessageHelper
import com.maddyhome.idea.vim.history.VimHistory
import com.maddyhome.idea.vim.history.VimHistoryBase
import com.maddyhome.idea.vim.impl.state.VimStateMachineImpl
import com.maddyhome.idea.vim.key.ShortcutOwnerInfo
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.key.interceptors.VimInputInterceptor
import com.maddyhome.idea.vim.register.VimRegisterGroup
import com.maddyhome.idea.vim.register.VimRegisterGroupBase
import com.maddyhome.idea.vim.state.VimStateMachine
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.undo.LineChange
import com.maddyhome.idea.vim.undo.VimKeyBasedUndoService
import com.maddyhome.idea.vim.undo.VimUndoRedo
import com.maddyhome.idea.vim.vimscript.model.functions.VimscriptFunctionProvider
import com.maddyhome.idea.vim.vimscript.model.functions.engineFunctionProvider
import com.maddyhome.idea.vim.vimscript.services.VariableService
import com.maddyhome.idea.vim.vimscript.services.VimVariableServiceBase
import com.maddyhome.idea.vim.yank.VimYankGroup
import com.maddyhome.idea.vim.yank.YankGroupBase
import kotlin.reflect.KClass

/**
 * What the engine reaches VS Code through.
 *
 * Every service is `by lazy` for the same reason the headless host's are: several of them read the
 * global `injector` while constructing - `VimscriptParserBase` builds a logger in its initialiser -
 * so a host that builds eagerly touches `injector` before the assignment installing it has
 * finished. IntelliJ never meets this because the platform creates its services on first use.
 *
 * Where VS Code has a real answer, it gives one. Where it has an answer that is only reachable
 * asynchronously - the system clipboard, running its own commands, its undo stack - the member
 * throws with its own name rather than guessing, because that is the *second* half of the
 * synchronous/asynchronous problem and it has no `DocumentBuffer`-shaped escape: buffering a write
 * works, inventing the result of a read does not.
 */
class VsCodeInjector(
  private val messageSink: MessageSink = MessageSink.Discarding,
  private val hostCommands: HostCommandRunner = HostCommandRunner.None,
) : VsCodeInjectorBase() {

  /** The editors this host knows about. VS Code's own list is of `TextEditor`, not of these. */
  private val openEditors: MutableList<VsCodeEditor> = mutableListOf()

  fun register(editor: VsCodeEditor) {
    if (openEditors.none { it === editor }) openEditors += editor
  }

  fun unregister(editor: VsCodeEditor) {
    openEditors.removeAll { it === editor }
  }

  // ---- Pure engine. Nothing about a host in any of these; the `*Base` classes are complete.

  override val parser: VimStringParser by lazy { object : VimStringParserBase() {} }
  override val vimscriptParser: VimscriptParser by lazy { object : VimscriptParserBase() {} }
  override val markService: VimMarkService by lazy { object : VimMarkServiceBase() {} }
  override val vimState: VimStateMachine by lazy { VimStateMachineImpl() }
  override val historyGroup: VimHistory by lazy { object : VimHistoryBase() {} }
  override val registerGroup: VimRegisterGroup by lazy { object : VimRegisterGroupBase() {} }
  override val registerGroupIfCreated: VimRegisterGroup? get() = registerGroup
  override val variableService: VariableService by lazy { object : VimVariableServiceBase() {} }
  override val yank: VimYankGroup by lazy { YankGroupBase() }
  override val listenersNotifier: VimListenersNotifier by lazy { VimListenersNotifier() }
  override val optionGroup: VimOptionGroup by lazy {
    object : VimOptionGroupBase() {}.also { it.initialiseOptions() }
  }

  /**
   * The engine's builtin functions. A VS Code host adds none of its own yet; when it does - a
   * `vscode#` namespace is the obvious shape - they join this list rather than replace it.
   */
  override val functionService: VimscriptFunctionService by lazy {
    object : VimScriptFunctionServiceBase() {
      override val functionProviders: List<VimscriptFunctionProvider> = listOf(engineFunctionProvider)
    }
  }

  /** Usage statistics are a product decision, and this host has made none. */
  override val statisticsService: VimStatistics by lazy { NoStatistics }

  override val enabler: VimEnabler by lazy {
    object : VimEnabler {
      override fun isEnabled(): Boolean = true
      override fun isNewIdeaVimUser(): Boolean = false
    }
  }

  // ---- Real VS Code, answered synchronously.

  /**
   * One thread and one event loop. JavaScript has no other thread to marshal onto, so "do this
   * later" is "do this now" - not a stand-in for something real, but the only accurate answer.
   *
   * That is a genuine difference from IntelliJ, where these exist because a write must happen on
   * the EDT under a write action. Nothing here needs a lock because nothing here can interleave.
   */
  override val application: VimApplication by lazy { SingleThreadedApplication }

  /**
   * The real platform, from Node's `process`. Option defaults branch on this - `'clipboard'`
   * consults `isXWindow`, `'shell'` reads the environment - so answering wrongly would give a
   * Linux user Windows defaults.
   */
  override val systemInfoService: SystemInfoService by lazy { NodeSystemInfo }

  override val editorGroup: VimEditorGroup by lazy {
    object : VimEditorGroup {
      override fun getEditorsRaw(): Collection<VimEditor> = openEditors.toList()
      override fun getEditors(): Collection<VimEditor> = openEditors.toList()
      override fun getEditors(buffer: VimDocument): Collection<VimEditor> = openEditors.toList()

      /** VS Code's active editor, as this host's wrapper for it. */
      override fun getFocusedEditor(): VimEditor? = openEditors.firstOrNull()
      override fun getSelectedEditor(): VimEditor? = getFocusedEditor()
      override fun getSelectedEditor(projectId: String): VimEditor? = getFocusedEditor()

      /** Caret shape by mode - a block in normal, a bar in insert - which is decoration work. */
      override fun updateCaretsVisualAttributes(editor: VimEditor) {}
      override fun updateCaretsVisualPosition(editor: VimEditor) {}

      /** IntelliJ's "join lines" notification. Nothing to notify in VS Code. */
      override fun notifyIdeaJoin(editor: VimEditor) {}
    }
  }

  /**
   * Per-window, per-buffer and per-tab data, keyed by editor.
   *
   * VS Code can distinguish the three - a document open in a split is one buffer in two editors -
   * but nothing yet tells them apart here, so they share a map. Local option values live in this,
   * which is why getting it wrong later shows up as `:setlocal` leaking across a split.
   */
  override val vimStorageService: VimStorageService by lazy { EditorKeyedStorage() }

  override val timerService: VimTimerService by lazy { NodeTimerService }

  override val messages: VimMessages by lazy { VsCodeMessages(messageSink) }

  // ---- Engine logic with a host-shaped hole in it.

  override val searchHelper: VimSearchHelper by lazy {
    object : VimSearchHelperBase() {
      // Both are IDE features rather than Vim ones, and VS Code exposes neither synchronously:
      // symbol navigation goes through a language server, and there is no spellchecker API at all.
      override fun findMethodStart(editor: VimEditor, caret: ImmutableVimCaret, count: Int): Int =
        TODO("VS Code host: findMethodStart needs a language server")

      override fun findMethodEnd(editor: VimEditor, caret: ImmutableVimCaret, count: Int): Int =
        TODO("VS Code host: findMethodEnd needs a language server")

      override fun findMisspelledWord(editor: VimEditor, caret: ImmutableVimCaret, count: Int): Int =
        TODO("VS Code host: findMisspelledWord needs a spellchecker")
    }
  }

  override val changeGroup: VimChangeGroup by lazy {
    object : VimChangeGroupBase() {
      /** `=` asks the editor to re-indent, which VS Code does through a command, asynchronously. */
      override fun reformatCode(editor: VimEditor, start: Int, end: Int) =
        TODO("VS Code host: reformatCode is an asynchronous command")

      override fun autoIndentRange(
        editor: VimEditor,
        context: ExecutionContext,
        ranges: List<TextRange>,
        carets: List<VimCaret>,
      ) = TODO("VS Code host: autoIndentRange is an asynchronous command")

      /**
       * Typed characters go straight into the buffer.
       *
       * IdeaVim hands each one to IntelliJ's own typed-action handler - what would have happened
       * with no Vim installed - so that auto-indent, bracket closing and completion all run. That
       * is a deliberate IdeaVim choice rather than Vim behaviour: Vim itself inserts the character
       * and nothing else, which is exactly what this does.
       *
       * VS Code's equivalent handler is the `default:type` command, and it is asynchronous - so
       * delegating would mean VS Code changing the document underneath the buffer mid-command, and
       * an insert-mode key path that cannot answer synchronously. Wiring it up is how the editor's
       * own typing features arrive later; it is a decision about the key handler, and it is not
       * needed for Vim's own behaviour.
       */
      override fun type(vimEditor: VimEditor, context: ExecutionContext, key: Char) {
        // Vim's `:abbreviate`, which fires on a non-keyword character. Engine behaviour, and the
        // IntelliJ host calls it from the same place.
        tryExpandAbbreviation(vimEditor, key)
        (vimEditor as VsCodeEditor).typeAtCarets(key.toString())
      }

      override fun type(vimEditor: VimEditor, context: ExecutionContext, string: String) {
        (vimEditor as VsCodeEditor).typeAtCarets(string)
      }

      override fun processBackspace(editor: VimEditor, context: ExecutionContext) {
        (editor as VsCodeEditor).deleteBeforeCarets()
      }
    }
  }

  override val searchGroup: VimSearchGroup by lazy {
    object : VimSearchGroupBase() {
      // Everything open here is about what the user *sees*: `'hlsearch'`, the `'incsearch'`
      // preview, the "3 of 12" count, the `:s///c` confirmation highlight. VS Code draws all of it
      // with `setDecorations`, which is real work and not yet done - and searching itself does not
      // need any of it, which is why the substitution is common code.
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
      ): SearchHighlight = TODO("VS Code host: decorations are not wired up")
    }
  }

  override val motion: VimMotionGroup by lazy {
    object : VimMotionGroupBase() {
      // Screen motions rather than buffer ones. VS Code can answer them - `visibleRanges` is the
      // viewport - and nothing has needed them yet, so they name themselves rather than guess.
      override fun moveCaretToFirstDisplayLine(editor: VimEditor, caret: ImmutableVimCaret, count: Int, normalizeToScreen: Boolean): Int =
        TODO("VS Code host: display-line motions need visibleRanges")

      override fun moveCaretToMiddleDisplayLine(editor: VimEditor, caret: ImmutableVimCaret): Int =
        TODO("VS Code host: display-line motions need visibleRanges")

      override fun moveCaretToLastDisplayLine(editor: VimEditor, caret: ImmutableVimCaret, count: Int, normalizeToScreen: Boolean): Int =
        TODO("VS Code host: display-line motions need visibleRanges")

      override fun moveCaretToCurrentDisplayLineStart(editor: VimEditor, caret: ImmutableVimCaret): Motion =
        TODO("VS Code host: display-line motions need visibleRanges")

      override fun moveCaretToCurrentDisplayLineStartSkipLeading(editor: VimEditor, caret: ImmutableVimCaret): Int =
        TODO("VS Code host: display-line motions need visibleRanges")

      override fun moveCaretToCurrentDisplayLineMiddle(editor: VimEditor, caret: ImmutableVimCaret): Motion =
        TODO("VS Code host: display-line motions need visibleRanges")

      override fun moveCaretToCurrentDisplayLineEnd(editor: VimEditor, caret: ImmutableVimCaret, allowEnd: Boolean): Motion =
        TODO("VS Code host: display-line motions need visibleRanges")

      override fun moveCaretGotoNextTab(editor: VimEditor, context: ExecutionContext, rawCount: Int): Int =
        TODO("VS Code host: tab motions need the editor group API")

      override fun moveCaretGotoPreviousTab(editor: VimEditor, context: ExecutionContext, rawCount: Int): Int =
        TODO("VS Code host: tab motions need the editor group API")
    }
  }

  override val keyGroup: VimKeyGroup by lazy {
    object : VimKeyGroupBase() {
      // These four are about VS Code's own keybindings: which of them a key is bound to, and which
      // conflict with Vim's. VS Code does not expose its resolved keymap to an extension at all -
      // `when` clauses and `vim.mode` contexts are how the conflict is actually settled there - so
      // "no conflicts" is closer to true than any list this could return.
      override fun getActions(editor: VimEditor, keyStroke: VimKeyStroke): List<NativeAction> = emptyList()
      override fun getKeymapConflicts(keyStroke: VimKeyStroke): List<NativeAction> = emptyList()
      override fun updateShortcutKeysRegistration() {}
      override val shortcutConflicts: MutableMap<VimKeyStroke, ShortcutOwnerInfo> get() = myShortcutConflicts

      /** Reading a character straight from the keyboard, as `r` and `f` do. */
      override fun getChar(editor: VimEditor): Char? = null
    }
  }

  override val jumpService: VimJumpService by lazy {
    object : VimJumpServiceBase() {
      /** VS Code's own go-back history, which this does not yet contribute to. */
      override fun includeCurrentCommandAsNavigation(editor: VimEditor) {}
      override var lastJumpTimeStamp: Long = 0
    }
  }

  override val engineEditorHelper: EngineEditorHelper by lazy {
    object : EngineEditorHelperBase() {
      /** IntelliJ's inline hints. VS Code's decorations take no part in offsets or columns. */
      override fun amountOfInlaysBeforeVisualPosition(editor: VimEditor, pos: VimVisualPosition): Int = 0

      override fun inlayAwareOffsetToVisualPosition(editor: VimEditor, offset: Int): VimVisualPosition =
        editor.offsetToVisualPosition(offset)

      // The viewport. `visibleRanges` is the real answer and folding makes it necessary; until
      // something needs it, the whole buffer is treated as on screen.
      override fun getVisualLineAtTopOfScreen(editor: VimEditor): Int = 0
      override fun getVisualLineAtBottomOfScreen(editor: VimEditor): Int = editor.lineCount() - 1

      /** `:registers` and `:marks` truncate to a width; 80 is the traditional answer. */
      override fun getApproximateScreenWidth(editor: VimEditor): Int = 80
      override fun getApproximateOutputPanelWidth(editor: VimEditor): Int = 80

      /** IntelliJ's guarded-block rejection. VS Code has no equivalent to be thrown. */
      override fun handleWithReadonlyFragmentModificationHandler(editor: VimEditor, exception: Exception) {}

      /** Vim's off-by-one: normal mode stops on the last character, insert may sit past it. */
      override fun normalizeVisualColumn(editor: VimEditor, visualLine: Int, col: Int, allowEnd: Boolean): Int {
        val length = getVisualLineLength(editor, visualLine)
        val max = if (allowEnd) length else (length - 1).coerceAtLeast(0)
        return col.coerceIn(0, max)
      }

      override fun getVisualLineLength(editor: VimEditor, visualLine: Int): Int =
        editor.getLineEndOffset(visualLine) - editor.getLineStartOffset(visualLine)

      /**
       * A marker that follows edits, which is what keeps a visual selection anchored across one.
       * IntelliJ has range markers; VS Code has nothing equivalent, so this will have to be built
       * on top of `onDidChangeTextDocument` rather than borrowed.
       */
      override fun createRangeMarker(editor: VimEditor, startOffset: Int, endOffset: Int): VimRangeMarker =
        TODO("VS Code host: range markers must be built on document change events")
    }
  }

  override val actionExecutor: VimActionExecutor by lazy { VimOnlyActionExecutor }

  // ---- Reachable only asynchronously, and therefore not yet reachable at all.

  /**
   * An in-memory clipboard, and a known lie about one register.
   *
   * This cannot be deferred the way the other asynchronous services can: Vim's registers and the
   * system clipboard are one mechanism, so the register group reaches here while `x` is deleting a
   * single character. Refusing to answer means nothing works at all.
   *
   * VS Code's `env.clipboard` is promise-only in *both* directions, and a read that must answer now
   * cannot wait for one. So `"*` and `"+` read this copy rather than the system clipboard, which
   * means text copied in another application is not visible to them. Writes are the tractable half
   * and can be mirrored outwards once the paste path is wired up; making reads correct means making
   * paste asynchronous, which is a decision about the key handler rather than about the clipboard.
   */
  override val clipboardManager: VimClipboardManager by lazy { InMemoryClipboard() }

  /**
   * Keeping the caret on screen, which is the one every command reaches after moving one.
   *
   * `revealRange` is the whole of VS Code's scrolling API for an extension: it takes a range and
   * puts it on screen, and there is no way to scroll by a line or a page. Vim's `<C-E>`, `<C-D>`
   * and `zt` are all defined the other way round - move the *view* and let the caret follow - so
   * they need `visibleRanges` to work out where the view currently is, and are left naming
   * themselves until something asks.
   */
  override val scroll: VimScrollGroup by lazy { RevealingScrollGroup }

  /**
   * Undo, in the two halves the engine asks about separately.
   *
   * The boundary calls are the ones every change makes: IntelliJ needs to be told where one
   * undoable unit ends, because a Vim command reaches its document as many small changes. This host
   * gives VS Code a whole command as a single edit, so the boundaries IntelliJ has to be told about
   * are already where they belong - which makes these no-ops rather than gaps.
   *
   * `u` and `<C-R>` themselves are the other half, and they are VS Code's `undo` command:
   * asynchronous, with no way to ask what it did.
   */
  override val undo: VimUndoRedo by lazy {
    // `VimUndoRedo` is sealed: a host picks key-based undo - IntelliJ's, where a keystroke group is
    // one document command - or timestamp-based. Key-based is the simpler contract.
    object : VimKeyBasedUndoService {
      /**
       * Dispatches VS Code's undo and says it worked.
       *
       * This is the one place where the answer is a guess. `undo` is a command: it resolves a
       * promise, it reports nothing about what it did, and the engine needs a boolean now. Vim uses
       * it to decide whether to beep and whether `3u` should keep going - so `false` on a
       * successful undo would be worse than `true` on an empty one, which is the case this gets
       * wrong: undoing with nothing left to undo is silent here where Vim says "Already at oldest
       * change".
       *
       * The keystroke after this one waits for the command to land - see [VimHost] - so the guess
       * is about the message, not about correctness of what follows.
       */
      override fun undo(editor: VimEditor, context: ExecutionContext): Boolean {
        hostCommands.run("undo")
        return true
      }

      override fun redo(editor: VimEditor, context: ExecutionContext): Boolean {
        hostCommands.run("redo")
        return true
      }

      override fun setMergeUndoKey() {}
      override fun updateNonMergeUndoKey() {}
      override fun setInsertNonMergeUndoKey(refresh: Boolean) {}
    }
  }

  override val lineChange: LineChange
    get() = TODO("VS Code host: U needs the same host history undo does")

  /**
   * No prompt is ever open, and none can be opened yet.
   *
   * The two halves are separate questions, and the engine asks the first on *every* keystroke -
   * `r`, `f` and digraph entry are what put one up, and `KeyHandler` has to know whether one is
   * waiting before it can interpret anything. "None is open" is simply true. Opening one is the
   * part VS Code makes hard: `showInputBox` resolves a promise, and Vim's prompt is synchronous.
   */
  override val modalInput: VimModalInputService by lazy {
    object : VimModalInputService {
      override fun getCurrentModalInput(): VimModalInput? = null

      override fun create(
        editor: VimEditor,
        context: ExecutionContext,
        label: String,
        inputInterceptor: VimInputInterceptor,
      ): VimModalInput = TODO("VS Code host: showInputBox is asynchronous")
    }
  }

  /**
   * The `:` and `/` prompts, likewise asked about on every keystroke and likewise not built.
   *
   * This does not stop `:` *commands* running - those are parsed and executed directly. What is
   * missing is the prompt, not the command.
   */
  override val commandLine: VimCommandLineService by lazy {
    object : VimCommandLineService {
      override fun isCommandLineSupported(editor: VimEditor): Boolean = false
      override fun getActiveCommandLine(): VimCommandLine? = null
      override fun getActiveCommandLineHeight(): Int = 0
      override fun fullReset() {}

      override fun readInputAndProcess(
        vimEditor: VimEditor,
        context: ExecutionContext,
        prompt: String,
        finishOn: Char?,
        processing: (String) -> Unit,
      ) = TODO("VS Code host: there is no command line to type into yet")

      override fun createSearchPrompt(
        editor: VimEditor,
        context: ExecutionContext,
        label: String,
        initialText: String,
      ): VimCommandLine = TODO("VS Code host: there is no command line to open yet")

      override fun createCommandPrompt(
        editor: VimEditor,
        context: ExecutionContext,
        count0: Int,
        initialText: String,
      ): VimCommandLine = TODO("VS Code host: there is no command line to open yet")
    }
  }

  /**
   * The host's own actions, all nullable - but one of them is not optional in practice.
   *
   * Null means "this host has no such action", and for most of them the engine then does the work
   * itself. `enterAction` is the exception: `o` and `O` do not insert their newline directly, they
   * move to the end of the line, enter insert mode, and *run the host's Enter*. With null there,
   * both commands enter insert mode on an unbroken line, so `otwo` on `one` gives `onetwo` - no
   * error, no missing member, just a missing newline. Worth knowing before writing a host, because
   * nothing about the interface says this one carries behaviour the engine relies on.
   *
   * The others stay null. VS Code has commands for several - `editor.action.joinLines` among them -
   * but commands are asynchronous, and the engine's own `J` is synchronous and already correct.
   */
  override val nativeActionManager: NativeActionManager by lazy {
    object : NativeActionManager {
      override val enterAction: NativeAction = InsertNewLineAction
      override val createLineAboveCaret: NativeAction? = null
      override val joinLines: NativeAction? = null
      override val indentLines: NativeAction? = null
      override val saveAll: NativeAction? = null
      override val saveCurrent: NativeAction? = null
      override val deleteAction: NativeAction? = null
    }
  }

  /**
   * Where `:registers`, `:marks` and `:!` output would go, asked about on the key path.
   *
   * The engine checks whether a panel is open while interpreting keys - a panel takes keys of its
   * own - so "none is open" has to be answerable even though opening one is not built. VS Code's
   * candidates are a webview or the output channel, and choosing between them is a design decision
   * rather than a wiring one.
   */
  override val outputPanel: VimOutputPanelService by lazy {
    object : VimOutputPanelService {
      override fun getCurrentOutputPanel(): VimOutputPanel? = null
      override fun getActiveOutputPanelHeight(): Int? = null

      override fun create(editor: VimEditor, context: ExecutionContext): VimOutputPanel =
        TODO("VS Code host: there is no output panel yet")

      override fun getOrCreate(editor: VimEditor, context: ExecutionContext): VimOutputPanel =
        TODO("VS Code host: there is no output panel yet")

      override fun output(
        editor: VimEditor,
        context: ExecutionContext,
        text: String,
        messageType: MessageType,
      ) = TODO("VS Code host: there is no output panel yet")

      override fun clear(editor: VimEditor, context: ExecutionContext) {}
    }
  }

  /** Silent. A host that wants the log can send it to the output channel. */
  override fun <T : Any> getLogger(clazz: KClass<T>): VimLogger = SilentLogger
}

/**
 * Where Vim's messages go.
 *
 * Separated from the injector so that a test can read what Vim said - "E486: Pattern not found" is
 * behaviour rather than decoration - and the extension can send the same text to an output channel
 * and the status bar.
 */
interface MessageSink {
  fun message(text: String?)
  fun error(text: String?)
  fun status(text: String?)

  object Discarding : MessageSink {
    override fun message(text: String?) {}
    override fun error(text: String?) {}
    override fun status(text: String?) {}
  }
}

private class VsCodeMessages(private val sink: MessageSink) : VimMessages {
  private var statusBar: String? = null
  private var error = false

  override fun showMessage(editor: VimEditor, message: String?) = sink.message(message)

  override fun showErrorMessage(editor: VimEditor, message: String?) {
    error = true
    sink.error(message)
  }

  override fun appendErrorMessage(editor: VimEditor, message: String?) {
    error = true
    sink.error(message)
  }

  override fun showStatusBarMessage(editor: VimEditor?, message: String?) {
    statusBar = message
    sink.status(message)
  }

  override fun getStatusBarMessage(): String? = statusBar

  override fun clearStatusBarMessage() {
    statusBar = null
    sink.status(null)
  }

  override fun indicateError() {
    error = true
  }

  override fun clearError() {
    error = false
  }

  override fun isError(): Boolean = error

  /** The engine's own bundle, so VS Code reports the text IntelliJ does. */
  override fun message(key: String, vararg params: Any): String = EngineMessageHelper.message(key, *params)

  override fun updateStatusBar(editor: VimEditor) {}
}

/**
 * See [VsCodeInjector.clipboardManager] for why this is not the system clipboard.
 *
 * "Transferable data" is IntelliJ's rich payload travelling with a copy - syntax-highlighted text,
 * imports to add on paste. VS Code copies plain text, so there is none.
 */
private class InMemoryClipboard : VimClipboardManager {
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
    clipboard = PlainCopiedText(text)
    return null
  }

  override fun collectCopiedText(
    editor: VimEditor,
    context: ExecutionContext,
    range: TextRange,
    text: String,
  ): VimCopiedText = PlainCopiedText(text)

  override fun dumbCopiedText(text: String): VimCopiedText = PlainCopiedText(text)

  override fun getTransferableData(vimEditor: VimEditor, textRange: TextRange): List<Any> = emptyList()

  override fun preprocessText(
    vimEditor: VimEditor,
    textRange: TextRange,
    text: String,
    transferableData: List<*>,
  ): String = text
}

private data class PlainCopiedText(override val text: String) : VimCopiedText {
  override fun updateText(newText: String): VimCopiedText = copy(text = newText)
}

private object SingleThreadedApplication : VimApplication {
  override fun isMainThread(): Boolean = true
  override fun invokeLater(editor: VimEditor, action: () -> Unit) = action()
  override fun invokeLater(action: () -> Unit) = action()
  override fun invokeAndWait(action: () -> Unit) = action()
  override fun isUnitTest(): Boolean = false
  override fun isInternal(): Boolean = false

  /** IntelliJ's read and write locks guard against other threads. There are none here. */
  override fun <T> runWriteAction(action: () -> T): T = action()
  override fun <T> runReadAction(action: () -> T): T = action()
  override fun runAfterGotFocus(runnable: () -> Unit) = runnable()

  override fun currentStackTrace(): String = Throwable().stackTraceToString()

  /** Feeding a key back into the handler, which needs the key dispatch loop this host lacks. */
  override fun postKey(stroke: VimKeyStroke, editor: VimEditor) =
    TODO("VS Code host: there is no key queue yet")
}

private object NodeSystemInfo : SystemInfoService {
  override val isWindows: Boolean = platform == "win32"

  /**
   * Linux and the BSDs, where `'clipboard'` distinguishes the selection from the clipboard. macOS
   * and Windows have one clipboard and no primary selection.
   */
  override val isXWindow: Boolean = platform != "win32" && platform != "darwin"

  override fun getenv(name: String): String? = process.env[name] as? String

  private val platform: String get() = process.platform as String
}

private object NoStatistics : VimStatistics {
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

private class EditorKeyedStorage : VimStorageService {
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

/** `'timeoutlen'`: an unfinished mapping waits, and executes as typed if nothing more arrives. */
private object NodeTimerService : VimTimerService {
  override fun createOneShotTimer(delayMillis: Int): VimTimer = NodeTimer(delayMillis)
}

private class NodeTimer(override var delayMillis: Int) : VimTimer {
  private var handle: Int? = null

  override val isRunning: Boolean get() = handle != null

  override fun start(delayMillis: Int, action: () -> Unit) {
    stop()
    this.delayMillis = delayMillis
    handle = setTimeout({
      handle = null
      action()
    }, delayMillis)
  }

  override fun stop() {
    handle?.let { clearTimeout(it) }
    handle = null
  }
}

/**
 * Runs Vim's own actions, and only those.
 *
 * `executeVimAction` is the one that matters: it is how `KeyHandler` runs the handler it found, and
 * it is pure engine code. The rest are about the *host's* actions, and VS Code's are commands -
 * `executeCommand` returns a promise, so whether one succeeded is not knowable in time to answer.
 * That is what `<Action>` mappings will have to solve.
 */
private object VimOnlyActionExecutor : VimActionExecutor {
  override val ACTION_EDITOR_NEXT_TEMPLATE_VARIABLE: String = ""
  override val ACTION_COLLAPSE_ALL_REGIONS: String = "editor.foldAll"
  override val ACTION_COLLAPSE_REGION: String = "editor.fold"
  override val ACTION_COLLAPSE_REGION_RECURSIVELY: String = "editor.foldRecursively"
  override val ACTION_EXPAND_ALL_REGIONS: String = "editor.unfoldAll"
  override val ACTION_EXPAND_REGION: String = "editor.unfold"
  override val ACTION_EXPAND_REGION_RECURSIVELY: String = "editor.unfoldRecursively"
  override val ACTION_EXPAND_COLLAPSE_TOGGLE: String = "editor.toggleFold"
  override val ACTION_UNDO: String = "undo"
  override val ACTION_REDO: String = "redo"

  override fun executeVimAction(
    editor: VimEditor,
    cmd: EditorActionHandlerBase,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ) {
    // IntelliJ wraps this in its CommandProcessor so the change becomes one undoable unit. VS Code
    // groups undo by edit, and the whole command reaches the document as a single edit, so the
    // grouping IntelliJ needs a wrapper for is what this host gets for free.
    cmd.execute(editor, context, operatorArguments)
  }

  override fun executeCommand(editor: VimEditor?, runnable: () -> Unit, name: String?, groupId: Any?) = runnable()

  override fun executeAction(editor: VimEditor?, action: NativeAction, context: ExecutionContext): Boolean =
    executeAction(editor, action)

  /**
   * The host's own actions, of which this host has exactly one - see
   * [VsCodeInjector.nativeActionManager] for why Enter has to be one of them.
   */
  override fun executeAction(editor: VimEditor?, action: NativeAction): Boolean {
    val vsCode = editor as? VsCodeEditor ?: return false
    return when (action) {
      is InsertNewLineAction -> {
        // Vim's own `o` would indent the new line to match; `'autoindent'` is not wired up yet, so
        // this is the newline and nothing else.
        vsCode.typeAtCarets("\n")
        true
      }

      else -> false
    }
  }

  override fun executeAction(editor: VimEditor, name: String, context: ExecutionContext): Boolean =
    TODO("VS Code host: running its commands is asynchronous")

  /**
   * Whether the *host* consumed Escape. IntelliJ runs its own Escape action first, so that closing
   * a completion popup does not also leave insert mode. VS Code settles that with `when` clauses in
   * its keybindings rather than by asking an extension, so nothing is consumed here and the engine
   * goes on to treat Escape as Vim's.
   */
  override fun executeEsc(editor: VimEditor, context: ExecutionContext): Boolean = false

  override fun getAction(actionId: String): NativeAction? = null
  override fun getActionIdList(idPrefix: String): List<String> = emptyList()

  /** Vim's own actions are findable, because the registry holding them is the engine's. */
  override fun findVimAction(id: String): EditorActionHandlerBase? =
    engineCommandProvider.getCommands().firstOrNull { it.actionId == id }?.instance

  override fun findVimActionOrDie(id: String): EditorActionHandlerBase =
    findVimAction(id) ?: error("no Vim action with id $id")
}

private object RevealingScrollGroup : VimScrollGroup {
  override fun scrollCaretIntoView(editor: VimEditor) {
    val vsCode = editor as? VsCodeEditor ?: return
    // From the buffer rather than from the document: this runs mid-command, before the flush, when
    // the document still has the old text and `positionAt` would answer about that.
    val position = vsCode.offsetToBufferPosition(vsCode.primaryCaret().offset)
    val at = Position(position.line, position.column)
    vsCode.nativeEditor.revealRange(Range(at, at), TextEditorRevealType.Default)
  }

  // Vim scrolls the view and lets the caret follow; VS Code only reveals a range. Working out where
  // the view is now - which is what makes "down half a page" meaningful - needs `visibleRanges`.
  override fun scrollFullPage(editor: VimEditor, caret: VimCaret, pages: Int): Boolean =
    TODO("VS Code host: page scrolling needs visibleRanges")

  override fun scrollHalfPage(editor: VimEditor, caret: VimCaret, rawCount: Int, down: Boolean): Boolean =
    TODO("VS Code host: page scrolling needs visibleRanges")

  override fun scrollLines(editor: VimEditor, lines: Int): Boolean =
    TODO("VS Code host: line scrolling needs visibleRanges")

  override fun scrollCurrentLineToDisplayTop(editor: VimEditor, rawCount: Int, start: Boolean): Boolean =
    TODO("VS Code host: zt needs visibleRanges")

  override fun scrollCurrentLineToDisplayMiddle(editor: VimEditor, rawCount: Int, start: Boolean): Boolean =
    TODO("VS Code host: zz needs visibleRanges")

  override fun scrollCurrentLineToDisplayBottom(editor: VimEditor, rawCount: Int, start: Boolean): Boolean =
    TODO("VS Code host: zb needs visibleRanges")

  override fun scrollColumns(editor: VimEditor, columns: Int): Boolean =
    TODO("VS Code host: horizontal scrolling needs visibleRanges")

  override fun scrollCaretColumnToDisplayLeftEdge(vimEditor: VimEditor): Boolean =
    TODO("VS Code host: horizontal scrolling needs visibleRanges")

  override fun scrollCaretColumnToDisplayRightEdge(editor: VimEditor): Boolean =
    TODO("VS Code host: horizontal scrolling needs visibleRanges")
}

/** Pressing Enter, which `o` and `O` reach for through the host rather than doing themselves. */
private object InsertNewLineAction : NativeAction {
  override val action: Any = "ideavim.insertNewLine"
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

/** Node's globals, which the VS Code extension host runs on. */
private external val process: dynamic
private external fun setTimeout(handler: () -> Unit, timeout: Int): Int
private external fun clearTimeout(handle: Int)

/**
 * How the engine asks VS Code to do something only VS Code can do.
 *
 * Every one of these is asynchronous - `undo`, `redo`, reformatting, running a command by name -
 * and none of them can report a result in time for the engine's synchronous contract. What a host
 * *can* do is hold the next keystroke until the command has landed, which is why this exists rather
 * than each service calling `executeCommand` on its own.
 */
interface HostCommandRunner {
  fun run(command: String)

  /** For a host that has no VS Code to run commands in. Nothing happens, and nothing pretends to. */
  object None : HostCommandRunner {
    override fun run(command: String) {}
  }
}
