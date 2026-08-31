/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
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
import com.maddyhome.idea.vim.key.VimKeyCodes
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.macro.VimMacro
import com.maddyhome.idea.vim.macro.VimMacroBase
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
import com.maddyhome.idea.vim.put.PutData
import com.maddyhome.idea.vim.put.ProcessedTextData
import com.maddyhome.idea.vim.put.VimPasteProvider
import com.maddyhome.idea.vim.put.VimPut
import com.maddyhome.idea.vim.put.VimPutBase
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
  private val commandLineDisplay: CommandLineDisplay = NoDisplay,
  private val highlighter: Highlighter = Highlighter.None,
  private val clipboard: SystemClipboard = SystemClipboard.InMemory(),
  private val outputPanelService: VimOutputPanelService = DiscardingOutputPanel,
) : VsCodeInjectorBase() {

  /** The editors this host knows about. VS Code's own list is of `TextEditor`, not of these. */
  private val openEditors: MutableList<VsCodeEditor> = mutableListOf()

  /**
   * The one with focus, which several services need and none can work out for themselves.
   *
   * Search highlighting is the reason this is not just "the first one open": `'hlsearch'` paints
   * the editor the user is looking at, and with two files open, whichever happened to be
   * registered first is not it.
   */
  private var activeEditor: VsCodeEditor? = null

  fun register(editor: VsCodeEditor) {
    if (openEditors.none { it === editor }) openEditors += editor
    activeEditor = editor
  }

  fun unregister(editor: VsCodeEditor) {
    openEditors.removeAll { it === editor }
    if (activeEditor === editor) activeEditor = openEditors.lastOrNull()
  }

  // ---- Pure engine. Nothing about a host in any of these; the `*Base` classes are complete.

  override val parser: VimStringParser by lazy { object : VimStringParserBase() {} }
  override val vimscriptParser: VimscriptParser by lazy { object : VimscriptParserBase() {} }

  /**
   * Running vimscript, which is what a `:` command *is* once it has been typed.
   *
   * `VimScriptExecutorBase` leaves one thing to a host: writing an open file to disk before it is
   * sourced, so `:source %` picks up what is on screen rather than what was last saved. VS Code can
   * do it - `workbench.action.files.save` - but it is a command and asynchronous, and nothing
   * sources a file yet.
   */
  override val vimscriptExecutor: VimscriptExecutor by lazy {
    object : VimScriptExecutorBase() {
      /**
       * Sourcing reads the file from disk, so an open copy with unsaved changes would be missed.
       *
       * IntelliJ saves the document first, which it can do synchronously. VS Code's `save` returns
       * a promise, and this is called *during* `:source` - so the choice is between sourcing what
       * is on disk and not sourcing at all. It sources what is on disk and says so, which matters
       * for exactly one case: `:source %` on a vimrc being edited right now.
       *
       * Every other path through here - the `.ideavimrc` at startup, `:source` of a file nobody has
       * open - has nothing to save and reaches none of this.
       */
      override fun ensureFileIsSaved(path: String) {
        val unsaved = editorGroup.getEditors().any { it.getPath()?.endsWith(path) == true && it.hasUnsavedChanges() }
        if (unsaved) {
          messages.showStatusBarMessage(null, "IdeaVim: sourcing $path from disk; it has unsaved changes")
        }
      }
    }
  }
  override val psiService: VimPsiService by lazy { TextOnlyPsiService }
  override val markService: VimMarkService by lazy { object : VimMarkServiceBase() {} }
  override val vimState: VimStateMachine by lazy { VimStateMachineImpl() }
  override val historyGroup: VimHistory by lazy { object : VimHistoryBase() {} }
  override val registerGroup: VimRegisterGroup by lazy { object : VimRegisterGroupBase() {} }
  override val registerGroupIfCreated: VimRegisterGroup? get() = registerGroup
  override val variableService: VariableService by lazy { object : VimVariableServiceBase() {} }

  /**
   * IdeaVim's bundled extensions - `surround`, `easymotion`, `commentary` - none of which are
   * ported. `set surround` therefore reports an unknown option rather than silently doing nothing,
   * which is the honest answer while the extensions do not exist here.
   */
  override val extensionRegistrator: VimExtensionRegistrator by lazy {
    object : VimExtensionRegistrator {
      override fun setOptionByPluginAlias(alias: String): Boolean = false
      override fun getExtensionNameByAlias(alias: String): String? = null
    }
  }

  /**
   * "Is a live template running?" - to which the answer here is no, and always will be.
   *
   * The engine asks before `<CR>` moves down a line and before an insert is recorded for `.`,
   * because in IntelliJ Enter inside a live template jumps to the next placeholder and belongs to
   * the template, not to Vim. VS Code has snippets, and its snippet session is not something an
   * extension can ask about synchronously - `editor.insertSnippet` returns a promise and there is
   * no "is a snippet active" to read.
   *
   * Answering null is not a placeholder for a better answer; it is the answer. Vim's `<CR>` moves
   * down a line, which is what a user pressing it expects. Before this it threw, which made Enter
   * in Normal mode - an ordinary key, pressed by accident constantly - take the whole plugin down.
   */
  override val templateManager: VimTemplateManager by lazy {
    object : VimTemplateManager {
      override fun getTemplateState(editor: VimEditor): VimTemplateState? = null
    }
  }

  /**
   * "Is the completion popup open?" - the same shape of question, with the same answer.
   *
   * IdeaVim asks so that `<C-N>`, `<C-P>`, `j` and `k` can drive IntelliJ's lookup when it is
   * showing instead of moving the caret. VS Code's suggest widget is not readable from an
   * extension either - `suggestWidgetVisible` is a context key, and context keys are write-only for
   * extensions, the same wall that made Tab's `when` clause the only way to yield to Copilot.
   *
   * That is why `package.json` guards Enter, Up and Down with `!suggestWidgetVisible`: those keys
   * are kept away from the engine while the widget is up, so the engine never needs to know. The
   * arrangement is the answer, and this reports it honestly rather than pretending a lookup exists.
   */
  override val lookupManager: VimLookupManager by lazy {
    object : VimLookupManager {
      override fun getActiveLookup(editor: VimEditor): IdeLookup? = null

      // `<C-X><C-L>` and `<C-X><C-F>` ask for a list to be shown and picked from, which needs a
      // widget rather than an answer. Saying so beats a crash and beats silence.
      override fun showCustomLookup(editor: VimEditor, values: List<String>, prefix: String) {
        injector.messages.showErrorMessage(editor, "IdeaVim: this completion needs a lookup, which this host does not have")
      }
    }
  }

  /**
   * `<C-K>` and `ga` - Vim's digraphs, which are a table and a bit of arithmetic. The engine
   * carries the whole table, so this is the engine's own implementation, unchanged.
   */
  override val digraphGroup: VimDigraphGroup by lazy { VimDigraphGroupBase() }

  /** `:abbreviate`, which is a map from a trigger to its expansion and nothing host-shaped. */
  override val abbreviationGroup: VimAbbreviationGroup by lazy { VimAbbreviationGroupBase() }

  /**
   * Recording and replaying keystrokes, which is engine work - `q` collects keys and `@` feeds them
   * back through the same handler they came from. Reached here because the command line records
   * what was typed at it.
   */
  override val macro: VimMacro by lazy {
    object : VimMacroBase() {
      /**
       * Feeds the recorded keys back through the handler they came from, [total] times.
       *
       * IntelliJ's version of this is mostly a progress dialog: macros there can run long enough to
       * need cancelling, and it also follows the user if the macro opens another file. Neither
       * applies yet - there is no progress UI to show, and a macro that switches editors is a
       * feature this host does not have. What is left is the loop, which is the whole of what a
       * macro is.
       *
       * Stops on an error, the way Vim does: `@q` that hits E486 halfway through does not keep
       * going and does not repeat.
       */
      override fun playbackKeys(editor: VimEditor, context: ExecutionContext, total: Int) {
        val handler = KeyHandler.getInstance()
        val keyStack = handler.keyStack
        if (!keyStack.hasStroke()) {
          keyStack.removeFirst()
          return
        }
        try {
          repeat(total) {
            try {
              while (keyStack.hasStroke()) {
                handler.handleKey(editor, keyStack.feedStroke(), context, handler.keyHandlerState)
                if (injector.messages.isError()) return
              }
            } finally {
              keyStack.resetFirst()
            }
          }
        } finally {
          keyStack.removeFirst()
        }
      }
    }
  }
  override val yank: VimYankGroup by lazy { YankGroupBase() }

  /**
   * Putting is reading a register and inserting its text, which `VimPutBase` does entirely.
   *
   * What it leaves open is IdeaVim's `ideaput`: pasting through the *IDE's* paste action so that
   * imports get added and the result is reindented. That is an IntelliJ feature with no VS Code
   * equivalent - its paste is a command, and asynchronous - so there is no provider to hand back
   * and the engine does the plain Vim paste, which is the behaviour Vim itself has.
   */
  override val put: VimPut by lazy {
    object : VimPutBase() {
      override fun getProviderForPasteViaIde(
        editor: VimEditor,
        typeInRegister: SelectionType,
        data: PutData,
      ): VimPasteProvider? = null

      override fun putTextViaIde(
        pasteProvider: VimPasteProvider,
        vimEditor: VimEditor,
        vimContext: ExecutionContext,
        text: ProcessedTextData,
        selectionType: SelectionType,
        data: PutData,
        additionalData: Map<String, Any>,
      ) = TODO("VS Code host: there is no IDE paste to put through")

      /** IdeaVim tells the user that `ideaput` is available. There is nothing to suggest here. */
      override fun notifyAboutIdeaPut(editor: VimEditor?) {}
    }
  }
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
      override fun getFocusedEditor(): VimEditor? = activeEditor ?: openEditors.lastOrNull()
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

  /** Files, for `:source` and for the `.ideavimrc` read at startup. */
  override val fileSystem: VimFileSystem by lazy { NodeFileSystem() }

  /**
   * Which file was loaded as the `.ideavimrc`, so `:source` can tell it apart from any other file.
   *
   * Compared as strings, which is right only because the host chose the path itself and hands the
   * same one back. A symlink or a relative path naming the same file would not match - a filesystem
   * question this does not yet ask.
   */
  override val vimrcFileState: VimrcFileState by lazy {
    object : VimrcFileState {
      override var filePath: String? = null

      override fun saveFileState(filePath: String) {
        this.filePath = filePath
      }

      override fun isVimRcFile(path: String): Boolean = path == filePath
    }
  }

  /**
   * How the engine gets a context to run something in when it was not handed one - sourcing a file
   * at startup, most of all. VS Code has no `DataContext` equivalent, so there is one context and
   * it carries nothing.
   */
  override val executionContextManager: ExecutionContextManager by lazy {
    object : ExecutionContextManager {
      override fun getEditorExecutionContext(editor: VimEditor): ExecutionContext = VsCodeExecutionContext
    }
  }

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
      /**
       * `'hlsearch'`: every match of the last pattern, painted.
       *
       * The base finds nothing for us - it hands over the pattern and the host does the searching,
       * which is how IntelliJ's does it too. That is the right split: *which* ranges to paint is a
       * screen question, and only the host knows which lines are worth searching.
       */
      override fun updateSearchHighlights(force: Boolean) {
        val editor = editorGroup.getFocusedEditor() as? VsCodeEditor ?: return
        val pattern = getLastUsedPattern()
        if (pattern == null || !shouldShowHighlights) {
          highlighter.clear(editor)
          return
        }
        val matches = searchHelper.findAll(editor, pattern, 0, -1, shouldIgnoreCase(pattern))
        highlighter.showMatches(editor, matches)
      }

      /** Whether `:nohlsearch` has anything to clear, and whether `n` should repaint. */
      override fun isSomeTextHighlighted(): Boolean = highlighter.isShowingAnything()

      override fun highlightSearchLines(editor: VimEditor, startLine: Int, endLine: Int) {
        val vsCode = editor as? VsCodeEditor ?: return
        val pattern = getLastUsedPattern() ?: return
        highlighter.showMatches(vsCode, searchHelper.findAll(editor, pattern, startLine, endLine, shouldIgnoreCase(pattern)))
      }

      override fun clearSearchHighlight() {
        shouldShowHighlights = false
        val editor = editorGroup.getFocusedEditor() as? VsCodeEditor ?: return
        highlighter.clear(editor)
      }

      override fun setShouldShowSearchHighlights() {
        shouldShowHighlights = true
      }

      override fun resetIncsearchHighlights() {
        updateSearchHighlights(true)
      }

      /**
       * The one range `:s///c` is asking about, painted while it waits for an answer.
       *
       * The handle unpaints it: VS Code has no way to remove a single decoration, so this sets the
       * confirmation style's ranges back to none.
       */
      override fun addSubstitutionConfirmationHighlight(
        editor: VimEditor,
        startOffset: Int,
        endOffset: Int,
      ): SearchHighlight {
        val vsCode = editor as? VsCodeEditor ?: return NoHighlight
        val remove = highlighter.showConfirmation(vsCode, TextRange(startOffset, endOffset))
        return object : SearchHighlight() {
          override fun remove() = remove()
        }
      }

      /**
       * `'incsearch'`: the match under the caret while a search is still being typed.
       *
       * Not painted yet. It needs the pattern *as typed so far*, which arrives on the command line
       * rather than through the search group, and drawing a preview that lags the typing by a
       * keystroke is worse than not drawing one.
       */
      override fun getCurrentIncsearchResultRange(editor: VimEditor): TextRange? = null

      /** Vim's "3 of 12" on the right of the command line, which needs the output panel. */
      override fun updateSearchCount(matchOffset: Int) {}

      private var shouldShowHighlights: Boolean = false

      /** `'ignorecase'`, softened by `'smartcase'` when the pattern has an uppercase letter. */
      private fun shouldIgnoreCase(pattern: String): Boolean {
        val options = globalOptions()
        if (!options.ignorecase) return false
        if (options.smartcase && pattern.any { it.isUpperCase() }) return false
        return true
      }

      private val NoHighlight = object : SearchHighlight() {
        override fun remove() {}
      }
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
      // These are about VS Code's own keybindings: which of them a key is bound to, and which
      // conflict with Vim's. VS Code does not expose its resolved keymap to an extension at all -
      // `when` clauses and `vim.mode` contexts are how the conflict is actually settled there - so
      // "no conflicts" is closer to true than any list this could return.
      //
      // Enter is the exception, and it is not really a question about keymaps. `processEnter` -
      // which is how `<CR>` in Insert mode reaches the document - asks this function what the host
      // has bound to Enter and runs the first answer, because in IntelliJ that is the IDE's smart
      // Enter with its indenting and its brace handling. Answering "nothing" there means Insert
      // mode cannot type a newline at all, which is what it meant here until this test caught it.
      override fun getActions(editor: VimEditor, keyStroke: VimKeyStroke): List<NativeAction> =
        if (keyStroke.keyCode == VimKeyCodes.VK_ENTER) listOf(InsertNewLineAction) else emptyList()
      override fun getKeymapConflicts(keyStroke: VimKeyStroke): List<NativeAction> = emptyList()
      override fun updateShortcutKeysRegistration() {}
      override val shortcutConflicts: MutableMap<VimKeyStroke, ShortcutOwnerInfo> get() = myShortcutConflicts

      /** Reading a character straight from the keyboard, as `r` and `f` do. */
      override fun getChar(editor: VimEditor): Char? = null
    }
  }

  /**
   * `VimVisualMotionGroupBase` leaves nothing abstract: what a selection covers is arithmetic over
   * offsets, and `'selection'` decides whether its end is inclusive. Making it *visible* is the
   * host's job, and that happens in the editor's flush rather than here.
   */
  override val visualMotionGroup: VimVisualMotionGroup by lazy { object : VimVisualMotionGroupBase() {} }

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
   * Vim's `"*` and `"+` registers, which *are* the selection and the clipboard.
   *
   * This is why the clipboard could not be deferred the way the other asynchronous services were:
   * the register group reaches here while `x` is deleting a single character, so refusing to answer
   * means nothing works at all. See [SystemClipboard] for how a promise-only clipboard is made to
   * answer a synchronous question, and what is still wrong about it.
   */
  override val clipboardManager: VimClipboardManager by lazy { RegisterBackedClipboard(clipboard) }

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
   * The `:` and `/` prompts.
   *
   * Not an input dialog: Vim's command line is a text buffer the engine drives keystroke by
   * keystroke, so a host supplies a string and somewhere to draw it. See [StatusBarCommandLine] -
   * the asynchronous `showInputBox` was the wrong shape for this, not a hard version of the right
   * one.
   */
  override val commandLine: VimCommandLineService by lazy { VsCodeCommandLineService(commandLineDisplay) }

  /**
   * Nothing to redraw on demand.
   *
   * VS Code repaints itself; an extension never asks it to. This exists because Vim's screen model
   * has a moment where everything is drawn again - `:redraw`, and entering command-line mode - and
   * a host with a retained-mode UI has no such moment. Each piece here updates when it changes
   * instead: the command line on every keystroke, the mode in the status bar when it moves.
   */
  override val redrawService: VimRedrawService by lazy {
    object : VimRedrawService {
      override fun redraw() {}
      override fun redrawStatusLine() {}
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
   * Where `:registers`, `:marks` and `:!` output go - an output channel, when the host gives one.
   *
   * See [OutputChannelPanel] for why the panel never holds keys, which is the deliberate difference
   * from Vim.
   */
  override val outputPanel: VimOutputPanelService by lazy { outputPanelService }

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
private class RegisterBackedClipboard(private val clipboard: SystemClipboard) : VimClipboardManager {

  /**
   * X11's primary selection - the one that middle-click pastes - which Vim exposes as `"*`.
   *
   * Kept separate and in memory. VS Code has one clipboard and no notion of a primary selection, so
   * a host that mapped `"*` onto it would make `"*` and `"+` the same register on every platform,
   * including the one where users rely on them differing.
   */
  private var primary: VimCopiedText? = null

  override fun getPrimaryContent(editor: VimEditor, context: ExecutionContext): VimCopiedText? = primary

  override fun getClipboardContent(editor: VimEditor, context: ExecutionContext): VimCopiedText? =
    clipboard.read()?.let { PlainCopiedText(it) }

  override fun setClipboardContent(
    editor: VimEditor,
    context: ExecutionContext,
    textData: VimCopiedText,
  ): Boolean {
    clipboard.write(textData.text)
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
    clipboard.write(text)
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

/**
 * For a host with nowhere to put output.
 *
 * `:registers` produces its text and it goes nowhere, which is what a host without a panel can
 * honestly do - the alternative is throwing, and losing the output of an informational command is
 * not worth stopping a session for.
 */
internal object DiscardingOutputPanel : VimOutputPanelService {
  override fun create(editor: VimEditor, context: ExecutionContext): VimOutputPanel = DiscardingPanel
  override fun getOrCreate(editor: VimEditor, context: ExecutionContext): VimOutputPanel = DiscardingPanel
  override fun getCurrentOutputPanel(): VimOutputPanel? = null
  override fun getActiveOutputPanelHeight(): Int? = null
  override fun output(editor: VimEditor, context: ExecutionContext, text: String, messageType: MessageType) {}
  override fun clear(editor: VimEditor, context: ExecutionContext) {}
}

private object DiscardingPanel : VimOutputPanel {
  private val content = StringBuilder()
  override val text: String get() = content.toString()
  override var statusText: String = ""
  override fun addText(text: String, isNewLine: Boolean, messageType: MessageType) {
    if (isNewLine && content.isNotEmpty()) content.append('\n')
    content.append(text)
  }
  override fun show(requireHitEnter: Boolean) { content.clear() }
  override fun close() { content.clear() }
  override fun clearText() { content.clear() }
}

/** For a host with nowhere to draw a command line. The text still exists; nobody sees it. */
private object NoDisplay : CommandLineDisplay {
  override fun show(text: String) {}
  override fun hide() {}
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
