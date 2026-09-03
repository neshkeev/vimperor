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
import com.maddyhome.idea.vim.api.VimCommandLine
import com.maddyhome.idea.vim.api.VimCommandLineService
import com.maddyhome.idea.vim.api.VimModalInput
import com.maddyhome.idea.vim.api.VimModalInputService
import com.maddyhome.idea.vim.key.interceptors.VimInputInterceptor
import com.maddyhome.idea.vim.api.AutoCmdService
import com.maddyhome.idea.vim.changelist.VimChangeList
import com.maddyhome.idea.vim.diff.Diff
import com.maddyhome.idea.vim.directory.WorkingDirectory
import com.maddyhome.idea.vim.group.VimWindowGroup
import com.maddyhome.idea.vim.group.WindowGroupBase
import com.maddyhome.idea.vim.api.VimPathExpansion
import com.maddyhome.idea.vim.api.SpellcheckerService
import com.maddyhome.idea.vim.api.VimMatchHighlighter
import com.maddyhome.idea.vim.highlight.HighlightGroup
import com.maddyhome.idea.vim.highlight.Highlights
import com.maddyhome.idea.vim.match.Matches
import com.maddyhome.idea.vim.message.MessageHistory
import com.maddyhome.idea.vim.sign.PlacedSign
import com.maddyhome.idea.vim.sign.Signs
import com.maddyhome.idea.vim.sign.VimSignDisplay
import com.maddyhome.idea.vim.redirect.Redirection
import com.maddyhome.idea.vim.profile.Profile
import com.maddyhome.idea.vim.quickfix.Quickfix
import com.maddyhome.idea.vim.script.SourcedScripts
import com.maddyhome.idea.vim.tags.Tags
import com.maddyhome.idea.vim.api.VimCommandGroup
import com.maddyhome.idea.vim.api.ExecutionContext as ExecutionContextApi
import com.maddyhome.idea.vim.api.VimBuffer
import com.maddyhome.idea.vim.api.VimFile
import com.maddyhome.idea.vim.api.VimFileReadException
import com.maddyhome.idea.vim.api.VimFileSystem
import com.maddyhome.idea.vim.api.VimFileBase
import com.maddyhome.idea.vim.api.VimCommandGroupBase
import com.maddyhome.idea.vim.api.VimRedrawService
import com.maddyhome.idea.vim.api.VimRegexServiceBase
import com.maddyhome.idea.vim.api.VimRegexpService
import com.maddyhome.idea.vim.api.ExecutionContextManager
import com.maddyhome.idea.vim.api.ExecutionContextManagerBase
import com.maddyhome.idea.vim.autocmd.AutoCmdImpl
import com.maddyhome.idea.vim.api.VimMessages
import com.maddyhome.idea.vim.api.VimMessagesBase
import com.maddyhome.idea.vim.api.VimOutputPanel
import com.maddyhome.idea.vim.api.VimOutputPanelService
import com.maddyhome.idea.vim.api.VimOutputPanelServiceBase
import com.maddyhome.idea.vim.api.VimScriptExecutorBase
import com.maddyhome.idea.vim.api.VimscriptExecutor
import com.maddyhome.idea.vim.api.MessageType
import com.maddyhome.idea.vim.helper.EngineMessageHelper
import com.maddyhome.idea.vim.api.VimKeyGroup
import com.maddyhome.idea.vim.action.change.LazyVimCommand
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.EngineEditorHelper
import com.maddyhome.idea.vim.api.EngineEditorHelperBase
import com.maddyhome.idea.vim.api.VimRangeMarker
import com.maddyhome.idea.vim.api.VimVisualPosition
import com.maddyhome.idea.vim.api.NativeAction
import com.maddyhome.idea.vim.yank.VimYankGroup
import com.maddyhome.idea.vim.yank.YankGroupBase
import com.maddyhome.idea.vim.common.VimListenersNotifier
import com.maddyhome.idea.vim.undo.LineChange
import com.maddyhome.idea.vim.undo.VimKeyBasedUndoService
import com.maddyhome.idea.vim.undo.VimUndoRedo
import com.maddyhome.idea.vim.api.VimEnabler
import com.maddyhome.idea.vim.api.VimActionExecutor
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.handler.EditorActionHandlerBase
import com.maddyhome.idea.vim.api.VimKeyGroupBase
import com.maddyhome.idea.vim.api.VimTimer
import com.maddyhome.idea.vim.api.VimTimerService
import com.maddyhome.idea.vim.handler.Motion
import com.maddyhome.idea.vim.key.ShortcutOwnerInfo
import com.maddyhome.idea.vim.api.VimMotionGroup
import com.maddyhome.idea.vim.api.VimMotionGroupBase
import com.maddyhome.idea.vim.api.VimScrollGroup
import com.maddyhome.idea.vim.api.VimJumpService
import com.maddyhome.idea.vim.api.VimJumpServiceBase
import com.maddyhome.idea.vim.api.VimClipboardManager
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.common.VimCopiedText
import com.maddyhome.idea.vim.put.PutData
import com.maddyhome.idea.vim.put.ProcessedTextData
import com.maddyhome.idea.vim.put.VimPasteProvider
import com.maddyhome.idea.vim.put.VimPut
import com.maddyhome.idea.vim.put.VimPutBase
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
  override val editorGroup: VimEditorGroup by lazy { HeadlessEditorGroup() }

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

  /**
   * Putting text, which is entirely the engine's - `VimPutBase` has no abstract members at all.
   *
   * It was a `TODO()` here for as long as nothing headless put anything, and that made a whole
   * family of commands untestable without saying so: `:put`, `:copy`, `:move`, `:read` and now
   * `:append` all reach the buffer through this and all reported "Not implemented yet :(" instead
   * of failing. The VS Code host builds it the same way, from the same nothing.
   */
  override val put: VimPut by lazy {
    object : VimPutBase() {
      /** There is no IDE here, so there is no IDE paste to route a put through. */
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
      ) = TODO("headless host: there is no IDE paste to put through")

      /**
       * Leaves the pasted lines exactly as they were, which is what plain Vim does.
       *
       * The base leaves this a `TODO`, and the executor turns a `NotImplementedError` into
       * "Not implemented yet :(" - so a `:copy` would insert its text and then abandon the command
       * half way. The VS Code host learned that from three of IdeaVim's own `:copy` fixtures.
       */
      override fun doIndent(
        editor: VimEditor,
        caret: VimCaret,
        context: ExecutionContext,
        startOffset: Int,
        endOffset: Int,
      ): Int = endOffset

      override fun notifyAboutIdeaPut(editor: VimEditor?) {}
    }
  }

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

  /**
   * A timer that never fires.
   *
   * `'timeoutlen'` is what this is for: an unfinished mapping sequence waits, and if nothing more
   * arrives the prefix is executed as typed. A test types every key it means to, so a timer that
   * never fires is the behaviour it wants - and firing on a wall clock would make tests depend on
   * how fast the machine is.
   */
  override val timerService: VimTimerService by lazy { HeadlessTimerService }

  /**
   * `VimKeyGroupBase` holds the mapping tries and the builtin command lookup; a host supplies only
   * the parts about keyboard shortcuts the IDE has to be told about.
   */
  override val keyGroup: VimKeyGroup by lazy {
    object : VimKeyGroupBase() {
      // All four are about the *host's* keyboard: which of its own actions a key is bound to, and
      // which of those conflict with Vim's. A host with no keymap has no conflicts to report.
      override fun getActions(editor: VimEditor, keyStroke: VimKeyStroke): List<NativeAction> = emptyList()
      override fun getKeymapConflicts(keyStroke: VimKeyStroke): List<NativeAction> = emptyList()
      override fun updateShortcutKeysRegistration() {}
      override val shortcutConflicts: MutableMap<VimKeyStroke, ShortcutOwnerInfo> get() = myShortcutConflicts

      /** Reading a character straight from the keyboard, as `r` and `f` do. Nothing types here. */
      override fun getChar(editor: VimEditor): Char? = null
    }
  }

  /**
   * Messages are recorded rather than shown, so a test can assert what Vim said - `:s` reporting
   * "E486: Pattern not found" is behaviour, not decoration. [HeadlessMessages.lastMessage] and
   * [HeadlessMessages.lastError] are the readable ends.
   */
  override val messages: VimMessages by lazy { HeadlessMessages() }

  /**
   * The panel Vim writes tables and `:echo` to, recorded rather than drawn.
   *
   * `VimOutputPanelServiceBase` is the engine's own, and taking it rather than writing a stub is
   * the point: it is where `:silent` decides whether output is hidden, so a stand-in here would
   * have tested the stand-in.
   */
  override val outputPanel: VimOutputPanelService by lazy { HeadlessOutputPanelService() }

  /**
   * `VimRegexServiceBase` is the engine's own regex engine with nothing host-shaped in it.
   *
   * Asked whenever a Vim pattern is matched against a string rather than against a buffer, which
   * `:filter` does for every line it is about to print.
   */
  override val regexpService: VimRegexpService by lazy { VimRegexServiceBase() }

  /**
   * Opening a file is recorded rather than done, since there is nowhere to open one.
   *
   * Everything that jumps somewhere - `:cnext`, `:buffer`, `:bmodified` - goes through
   * `openFile`, so recording the calls is what lets a test say a command went to the right place
   * without a host that has places. Every other member is still a `TODO`, so a test that starts
   * needing one says which.
   */
  override val file: VimFile by lazy { HeadlessFile() }
  override val window: VimWindowGroup by lazy { HeadlessWindowGroup() }
  override val spellcheckerService: SpellcheckerService by lazy { HeadlessSpellchecker() }
  override val matchHighlighter: VimMatchHighlighter by lazy { HeadlessMatchHighlighter() }
  override val signDisplay: VimSignDisplay by lazy { HeadlessSignDisplay() }

  /**
   * Paths, unexpanded.
   *
   * A headless host has no environment and no home directory, so `$VAR` and `~` have nothing to
   * become. Returning the path untouched is not a stub standing in for the real thing: it is what
   * expansion *does* when there is nothing to expand, and it keeps the commands that resolve a path
   * - `:diffsplit`, `:source`, `:e` - testable without inventing an environment for them.
   */
  override val pathExpansion: VimPathExpansion = object : VimPathExpansion {
    override fun expandPath(path: String): String = path
    override fun expandForOption(value: String): String = value
  }
  override val fileSystem: VimFileSystem by lazy { HeadlessFileSystem() }

  init {
    // A new injector is a new session, and the quickfix list belongs to a session. Without this a
    // list one test filled is still there for the next one.
    Quickfix.reset()
    // And so does the change list, for the same reason. So does the tag stack.
    VimChangeList.reset()
    Tags.reset()
    SourcedScripts.reset()
    Diff.reset()
    Matches.reset()
    Highlights.reset()
    Redirection.reset()
    MessageHistory.reset()
    Signs.reset()
    Profile.reset()
    WorkingDirectory.reset()
  }

  /**
   * The user's `:command` aliases, of which a headless host has none.
   *
   * `VimCommandGroupBase` leaves nothing abstract - an alias is a name and a replacement, kept in a
   * map. It is here because an *unknown* command is looked up among the aliases before it is
   * reported, so without it every unknown command was "Not implemented yet" instead of `E492`.
   */
  override val commandGroup: VimCommandGroup by lazy { object : VimCommandGroupBase() {} }

  /**
   * Redraws are counted rather than drawn, since nothing is displayed.
   *
   * `:redraw` and `:redrawstatus` are the two commands that ask, and counting is what lets a test
   * say they reached the service rather than merely returning without an error.
   */
  override val redrawService: VimRedrawService by lazy { HeadlessRedrawService() }

  /** `AutoCmdImpl` is the engine's own and needs no host behind it: it stores commands and runs them. */
  override val autoCmd: AutoCmdService by lazy { AutoCmdImpl() }

  /**
   * One context, because there is one of everything here.
   *
   * In a real host this carries the project and the data the IDE hands an action; a headless run
   * has neither, and the commands that ask for one only ever pass it back to the engine.
   */
  override val executionContextManager: ExecutionContextManager by lazy {
    object : ExecutionContextManagerBase() {
      override fun getEditorExecutionContext(editor: VimEditor) = HeadlessExecutionContext
    }
  }

  /**
   * Vimscript, executed - the engine's own `VimScriptExecutorBase`, with the two hooks a host owns
   * stubbed out. Nothing here saves files and nothing here loads extensions.
   */
  override val vimscriptExecutor: VimscriptExecutor by lazy {
    object : VimScriptExecutorBase() {
      override fun ensureFileIsSaved(path: String) {}
      override fun enableDelayedExtensions() {}
    }
  }

  /**
   * No prompt is ever open. `KeyHandler` asks on every keystroke whether one is - `r`, `f` and the
   * digraph entry put one up - and "none" is the answer for a host that never draws one.
   */
  override val modalInput: VimModalInputService by lazy { HeadlessModalInput }

  /**
   * No command line. `KeyHandler` asks whether one is active on every keystroke, so this has to
   * answer; opening one needs a widget to type into, which is a host with a screen.
   *
   * Note that this does not stop `:` commands running - those are parsed and executed directly, as
   * `HeadlessSubstituteTest` does. What is missing is the *prompt*, not the command.
   */
  override val commandLine: VimCommandLineService by lazy { HeadlessCommandLineService }

  /**
   * Runs Vim's own actions and nothing else.
   *
   * `executeVimAction` is the one that matters - it is how `KeyHandler` runs the handler it found -
   * and it is pure engine code. Everything else on this interface is about the *IDE's* actions:
   * running one by id, mapping `<Action>` to it, undo and redo. A host with no action system has
   * none to run, which is exactly what `<Action>` mappings will need from a VS Code host later.
   */
  override val actionExecutor: VimActionExecutor by lazy { HeadlessActionExecutor }

  /** Vim is on. The host-level "IdeaVim is disabled" switch has no meaning without a UI to flip it. */
  override val enabler: VimEnabler by lazy {
    object : VimEnabler {
      override fun isEnabled(): Boolean = true
      override fun isNewIdeaVimUser(): Boolean = false
    }
  }

  /**
   * No undo stack. Vim delegates undo to the host - IntelliJ's own document history is what `u`
   * drives - so a host without one has nothing to offer, and `u` reports failure rather than
   * pretending.
   */
  override val undo: VimUndoRedo by lazy {
    // `VimUndoRedo` is sealed: a host picks key-based undo - IntelliJ's, where a keystroke group is
    // one document command - or timestamp-based. Key-based is the simpler of the two contracts.
    object : VimKeyBasedUndoService {
      override fun undo(editor: VimEditor, context: ExecutionContext): Boolean = false
      override fun redo(editor: VimEditor, context: ExecutionContext): Boolean = false
      override fun setMergeUndoKey() {}
      override fun updateNonMergeUndoKey() {}
      override fun setInsertNonMergeUndoKey(refresh: Boolean) {}
    }
  }

  /** `U` restores a whole line, and needs the same host history that [undo] does. */
  override val lineChange: LineChange by lazy {
    object : LineChange {
      override fun snapshotLine(line: Int, editor: VimEditor): Boolean = false
      override fun undoLineChange(editor: VimEditor, context: ExecutionContext): Boolean = false
    }
  }

  /**
   * The engine's own listener registry - `VimListenersNotifier` is common code - so a host provides
   * an instance and nothing else. Extensions and the IDE subscribe to mode changes and yanks
   * through it.
   */
  override val listenersNotifier: VimListenersNotifier by lazy { VimListenersNotifier() }

  /** `EngineEditorHelperBase` leaves nothing abstract; it is arithmetic over offsets and lines. */
  override val engineEditorHelper: EngineEditorHelper by lazy {
    object : EngineEditorHelperBase() {
      /** Inlays are IntelliJ's inline hints; there are none, so nothing shifts a visual column. */
      override fun amountOfInlaysBeforeVisualPosition(editor: VimEditor, pos: VimVisualPosition): Int = 0

      // With no viewport, the whole buffer is "on screen".
      override fun getVisualLineAtTopOfScreen(editor: VimEditor): Int = 0
      override fun getVisualLineAtBottomOfScreen(editor: VimEditor): Int = editor.lineCount() - 1

      // A width has to be *some* number: `:registers` and `:marks` truncate their output to it,
      // and 80 is the terminal's traditional answer.
      override fun getApproximateScreenWidth(editor: VimEditor): Int = 80
      override fun getApproximateOutputPanelWidth(editor: VimEditor): Int = 80

      /** IntelliJ's guarded-block rejection; nothing here is guarded, so nothing throws it. */
      override fun handleWithReadonlyFragmentModificationHandler(editor: VimEditor, exception: Exception) {}

      /** No inlays, so this is the plain offset-to-position conversion. */
      override fun inlayAwareOffsetToVisualPosition(editor: VimEditor, offset: Int): VimVisualPosition =
        editor.offsetToVisualPosition(offset)

      /**
       * Clamps a column to the line, with Vim's off-by-one: normal mode stops on the last
       * character, and insert or visual mode may sit one past it.
       */
      override fun normalizeVisualColumn(editor: VimEditor, visualLine: Int, col: Int, allowEnd: Boolean): Int {
        val length = getVisualLineLength(editor, visualLine)
        val max = if (allowEnd) length else (length - 1).coerceAtLeast(0)
        return col.coerceIn(0, max)
      }

      /** Visual lines are buffer lines here, so a line's length is its buffer length. */
      override fun getVisualLineLength(editor: VimEditor, visualLine: Int): Int =
        editor.getLineEndOffset(visualLine) - editor.getLineStartOffset(visualLine)

      /**
       * A marker that does not track edits. IntelliJ's range markers move as the document changes,
       * which is what keeps a visual selection anchored across an edit; nothing here needs that yet,
       * and pretending to it would be worse than saying so.
       */
      override fun createRangeMarker(editor: VimEditor, startOffset: Int, endOffset: Int): VimRangeMarker =
        TODO("headless host has no range markers that follow edits")
    }
  }

  /** `YankGroupBase` is concrete already - yanking is reading a range and storing it. */
  override val yank: VimYankGroup by lazy { YankGroupBase() }

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

/**
 * The editors this host has, which is however many a test made.
 *
 * It used to answer "none", and that was cheap until something walked the list: the mark service
 * adjusts a mark by asking which editors show the file, so with no editors listed no mark ever
 * moved and `:lockmarks` had nothing to stop. [TestVimEditor] adds itself here as it is built.
 */
class HeadlessEditorGroup : VimEditorGroup {
  private val editors = mutableListOf<VimEditor>()

  fun register(editor: VimEditor) {
    if (editors.none { it === editor }) editors += editor
  }

  override fun notifyIdeaJoin(editor: VimEditor) {}
  override fun getEditorsRaw(): Collection<VimEditor> = editors.toList()
  override fun getEditors(): Collection<VimEditor> = editors.toList()

  /** One buffer per headless host, so every editor is showing it. */
  override fun getEditors(buffer: VimDocument): Collection<VimEditor> = editors.toList()

  override fun updateCaretsVisualAttributes(editor: VimEditor) {}
  override fun updateCaretsVisualPosition(editor: VimEditor) {}

  /** The most recently built one, there being no focus and no window order to consult. */
  override fun getFocusedEditor(): VimEditor? = editors.lastOrNull()
  override fun getSelectedEditor(projectId: String): VimEditor? = getFocusedEditor()
  override fun getSelectedEditor(): VimEditor? = getFocusedEditor()
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

private object HeadlessTimerService : VimTimerService {
  override fun createOneShotTimer(delayMillis: Int): VimTimer = HeadlessTimer(delayMillis)
}

private class HeadlessTimer(override var delayMillis: Int) : VimTimer {
  override var isRunning: Boolean = false
    private set

  override fun start(delayMillis: Int, action: () -> Unit) {
    this.delayMillis = delayMillis
    isRunning = true
  }

  override fun stop() {
    isRunning = false
  }
}

/**
 * Everything written to the output panel, kept in a list.
 *
 * One panel for the life of the injector, because there is no screen for a second one to replace
 * the first on. [lines] is what a test reads.
 */
/** Every file this host was asked to open, in order. See [HeadlessInjector.file]. */
class HeadlessFile : VimFileBase() {
  val opened: MutableList<String> = mutableListOf()

  /** The buffer list `:ls` and `:ball` read, which a test arranges by name. */
  var buffers: List<String> = emptyList()

  override fun openFile(filename: String, context: ExecutionContextApi, focusEditor: Boolean): String? {
    opened += filename
    return null
  }

  /** A path is taken at its word: there is no filesystem to check it against. */
  override fun findFile(filename: String, context: ExecutionContextApi): String = filename

  override fun displayFileInfo(vimEditor: VimEditor, fullPath: Boolean): String? = null
  override fun getBuffers(context: ExecutionContextApi): List<VimBuffer> =
    buffers.mapIndexed { index, name ->
      VimBuffer(
        name = name,
        displayPath = name,
        isCurrent = index == 0,
        isAlternate = false,
        isReadOnly = false,
        isModified = false,
        line = 1,
      )
    }
  override fun selectPreviousTab(context: ExecutionContextApi): Boolean = false
  override fun saveFile(editor: VimEditor, context: ExecutionContextApi) {}
  override fun saveFiles(editor: VimEditor, context: ExecutionContextApi) {}
  override fun closeFile(editor: VimEditor, context: ExecutionContextApi) {}
  override fun closeFile(number: Int, context: ExecutionContextApi) {}
  override fun selectFile(count: Int, context: ExecutionContextApi): Boolean = false
  override fun selectNextFile(count: Int, context: ExecutionContextApi) {}
  override fun createFile(filename: String, context: ExecutionContextApi, content: String?, editor: VimEditor) {}

  /** One, so that a relative path in `:mkvimrc` has something to be relative to. */
  override fun getWorkingDirectory(context: ExecutionContextApi): String = "/work"

  override fun getProjectId(project: Any): String = "headless"
  override fun selectEditor(projectId: String, documentPath: String, protocol: String): VimEditor? = null
}

/**
 * Files, in memory.
 *
 * `:mkvimrc` writes one and `:source` reads one, and a test that wrote to the machine running it
 * would be a test that behaves differently on a laptop with a `.ideavimrc` already in the
 * directory. Writing to a map keeps `E189` - the refusal to overwrite - assertable rather than
 * dangerous.
 */
class HeadlessFileSystem : VimFileSystem {
  val written: MutableMap<String, String> = mutableMapOf()

  /** Set to a message to make the next write fail, which is the only way to reach `E212`. */
  var writeFailure: String? = null

  override fun readText(path: String): String =
    written[path] ?: throw VimFileReadException(path, "no such file or directory")

  override fun writeText(path: String, content: String): String? {
    writeFailure?.let { return it }
    written[path] = content
    return null
  }

  override fun exists(path: String): Boolean = path in written || isDirectory(path)

  /** A path is a directory when something is written under it - there is nothing else to ask. */
  override fun isDirectory(path: String): Boolean =
    written.keys.any { it.startsWith("$path/") }

  override fun listDirectory(path: String): List<String> =
    written.keys
      .filter { it.startsWith("$path/") }
      .map { it.substring(path.length + 1).substringBefore("/") }
      .distinct()
}

/**
 * Windows, of which a headless host has one - so what this is for is recording what was asked.
 *
 * `:diffthis` and its relatives are the callers: the whole of the engine's share of diff mode is
 * deciding which two files go into the host's diff view, and that decision is what a test can check.
 */
class HeadlessWindowGroup : WindowGroupBase() {
  val diffs: MutableList<Pair<String, String>> = mutableListOf()

  /** Set to false to make the host refuse, which is the only way to reach the failure message. */
  var canShowDiff: Boolean = true

  override fun showDiff(context: ExecutionContextApi, leftPath: String, rightPath: String): Boolean {
    if (!canShowDiff) return false
    diffs += leftPath to rightPath
    return true
  }

  override fun selectWindowInRow(caret: VimCaret, context: ExecutionContextApi, relativePosition: Int, vertical: Boolean) {}
  override fun selectNextWindow(context: ExecutionContextApi) {}
  override fun selectWindow(context: ExecutionContextApi, index: Int) {}
  override fun selectPreviousWindow(context: ExecutionContextApi) {}
  override fun closeAllExceptCurrent(context: ExecutionContextApi) {}
  override fun splitWindowVertical(context: ExecutionContextApi, filename: String, focusNew: Boolean) {}
  override fun splitWindowHorizontal(context: ExecutionContextApi, filename: String, focusNew: Boolean) {}
  override fun closeCurrentWindow(context: ExecutionContextApi) {}
  override fun closeAll(context: ExecutionContextApi) {}
  override fun openNewBuffer(context: ExecutionContextApi) {}
}

/**
 * A dictionary that is a pair of lists, so a test can read back what was added and removed.
 *
 * The engine's whole share of spell checking is the three calls `zg`, `zw` and `z=` make, and this
 * records them. The IDE's real dictionary is the host's business; what belongs here is that
 * `:spellgood` reaches "add" with the word the reader typed.
 */
class HeadlessSpellchecker : SpellcheckerService {
  val added: MutableList<String> = mutableListOf()
  val removed: MutableList<String> = mutableListOf()
  val suggested: MutableList<String> = mutableListOf()

  override fun addWordToDictionary(word: String, editor: VimEditor) {
    added += word
  }

  override fun removeWordFromDictionary(word: String, editor: VimEditor) {
    removed += word
  }

  override fun selectSuggestion(word: String, editor: VimEditor, caret: VimCaret) {
    suggested += word
  }
}

/**
 * What `:match` painted, per channel, so a test can read the ranges back.
 *
 * The ranges are what matters: `:match` is a standing highlight and the whole question about it is
 * whether the right text is lit after the buffer has changed, which is a question about ranges and
 * not about colours.
 */
class HeadlessMatchHighlighter : VimMatchHighlighter {
  val shown: MutableMap<Int, Pair<HighlightGroup, List<TextRange>>> = mutableMapOf()
  val cleared: MutableList<Int> = mutableListOf()

  override fun showMatches(editor: VimEditor, channel: Int, group: HighlightGroup, ranges: List<TextRange>) {
    shown[channel] = group to ranges
  }

  override fun clearMatches(editor: VimEditor, channel: Int) {
    shown.remove(channel)
    cleared += channel
  }
}

/**
 * What `:sign` handed over, per file, so a test can read it back.
 *
 * [calls] counts them as well as keeping the last, because the interesting question about signs is
 * not only what was painted but *how often*: the engine is supposed to hand a host a list only when
 * the list changed, and a count is the only way to see that it did not hand one over on every key.
 */
class HeadlessSignDisplay : VimSignDisplay {
  val shown: MutableMap<String, List<PlacedSign>> = mutableMapOf()
  var calls: Int = 0
    private set

  override fun showSigns(editor: VimEditor, signs: List<PlacedSign>) {
    calls++
    shown[editor.getPath() ?: ""] = signs
  }
}

class HeadlessRedrawService : VimRedrawService {
  var redraws: Int = 0
    private set
  var statusLineRedraws: Int = 0
    private set

  override fun redraw() {
    redraws++
  }

  override fun redrawStatusLine() {
    statusLineRedraws++
  }
}

class HeadlessOutputPanelService : VimOutputPanelServiceBase() {
  private val panel = HeadlessOutputPanel()

  val lines: List<String> get() = panel.lines

  override fun create(editor: VimEditor, context: ExecutionContext): VimOutputPanel = panel
  override fun getCurrentOutputPanel(): VimOutputPanel = panel
  override fun getActiveOutputPanelHeight(): Int = panel.lines.size
}

class HeadlessOutputPanel : VimOutputPanel {
  val lines: MutableList<String> = mutableListOf()

  override val text: String get() = lines.joinToString("\n")
  override var statusText: String = ""

  override fun addText(text: String, isNewLine: Boolean, messageType: MessageType) {
    lines += text
  }

  override fun show(requireHitEnter: Boolean) {}
  override fun close() {}

  override fun clearText() {
    lines.clear()
  }
}

class HeadlessMessages : VimMessagesBase() {
  var lastMessage: String? = null
    private set
  var lastError: String? = null
    private set
  private var statusBar: String? = null
  private var error = false

  override fun displayMessage(editor: VimEditor, message: String?) {
    if (isSilent) return
    lastMessage = message
  }

  override fun displayErrorMessage(editor: VimEditor, message: String?) {
    error = true
    if (isSilentAboutErrors) return
    lastError = message
  }

  override fun appendDisplayedErrorMessage(editor: VimEditor, message: String?) {
    error = true
    if (isSilentAboutErrors) return
    lastError = (lastError ?: "") + (message ?: "")
  }

  override fun displayStatusBarMessage(editor: VimEditor?, message: String?) {
    if (isSilent) return
    statusBar = message
  }

  override fun getStatusBarMessage(): String? = statusBar

  override fun clearStatusBarMessage() {
    statusBar = null
  }

  override fun indicateError() {
    error = true
  }

  override fun clearError() {
    error = false
  }

  override fun isError(): Boolean = error

  /** The engine's own bundle, so a headless host reports the same text the IDE does. */
  override fun message(key: String, vararg params: Any): String =
    EngineMessageHelper.message(key, *params)

  override fun updateStatusBar(editor: VimEditor) {}
}

private object HeadlessModalInput : VimModalInputService {
  override fun getCurrentModalInput(): VimModalInput? = null

  override fun create(
    editor: VimEditor,
    context: ExecutionContext,
    label: String,
    inputInterceptor: VimInputInterceptor,
  ): VimModalInput = TODO("headless host cannot prompt for input")
}

private object HeadlessCommandLineService : VimCommandLineService {
  override fun isCommandLineSupported(editor: VimEditor): Boolean = false

  override fun getActiveCommandLine(): VimCommandLine? = null

  override fun readInputAndProcess(
    vimEditor: VimEditor,
    context: ExecutionContext,
    prompt: String,
    finishOn: Char?,
    processing: (String) -> Unit,
  ) = TODO("headless host cannot prompt on the command line")

  override fun createSearchPrompt(
    editor: VimEditor,
    context: ExecutionContext,
    label: String,
    initialText: String,
  ): VimCommandLine = TODO("headless host has no command line to open")

  override fun createCommandPrompt(
    editor: VimEditor,
    context: ExecutionContext,
    count0: Int,
    initialText: String,
  ): VimCommandLine = TODO("headless host has no command line to open")

  override fun fullReset() {}

  override fun getActiveCommandLineHeight(): Int = 0
}

private object HeadlessActionExecutor : VimActionExecutor {
  override val ACTION_EDITOR_NEXT_TEMPLATE_VARIABLE: String = ""
  override val ACTION_COLLAPSE_ALL_REGIONS: String = ""
  override val ACTION_COLLAPSE_REGION: String = ""
  override val ACTION_COLLAPSE_REGION_RECURSIVELY: String = ""
  override val ACTION_EXPAND_ALL_REGIONS: String = ""
  override val ACTION_EXPAND_REGION: String = ""
  override val ACTION_EXPAND_REGION_RECURSIVELY: String = ""
  override val ACTION_EXPAND_COLLAPSE_TOGGLE: String = ""
  override val ACTION_UNDO: String = ""
  override val ACTION_REDO: String = ""
  override val ACTION_GOTO_DECLARATION: String = ""

  /** The engine running its own handler - the only one of these that is not about the IDE. */
  override fun executeVimAction(
    editor: VimEditor,
    cmd: EditorActionHandlerBase,
    context: ExecutionContext,
    operatorArguments: OperatorArguments,
  ) {
    // IntelliJ wraps this in its CommandProcessor so the change is one undoable unit; with no undo
    // stack to group into, the call itself is all that is left.
    cmd.execute(editor, context, operatorArguments)
  }

  override fun executeCommand(editor: VimEditor?, runnable: () -> Unit, name: String?, groupId: Any?) =
    runnable()

  override fun executeAction(editor: VimEditor?, action: NativeAction, context: ExecutionContext): Boolean = false
  override fun executeAction(editor: VimEditor?, action: NativeAction): Boolean = false
  override fun executeAction(editor: VimEditor, name: String, context: ExecutionContext): Boolean = false
  override fun executeEsc(editor: VimEditor, context: ExecutionContext): Boolean = false
  override fun getAction(actionId: String): NativeAction? = null
  override fun getActionIdList(idPrefix: String): List<String> = emptyList()

  /** Vim's own actions are findable, because the registry that holds them is the engine's. */
  override fun findVimAction(id: String): EditorActionHandlerBase? =
    engineCommandProvider.getCommands().firstOrNull { it.actionId == id }?.instance

  override fun findVimActionOrDie(id: String): EditorActionHandlerBase =
    findVimAction(id) ?: error("no Vim action with id $id")
}
