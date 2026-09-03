/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.change.LazyVimCommand
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.autocmd.AutoCmdEvent
import com.maddyhome.idea.vim.autocmd.AutoCmdImpl
import com.maddyhome.idea.vim.api.SpellcheckerService
import com.maddyhome.idea.vim.api.VimMatchHighlighter
import com.maddyhome.idea.vim.api.*
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.common.VimCopiedText
import com.maddyhome.idea.vim.common.VimListenersNotifier
import com.maddyhome.idea.vim.diagnostic.VimLogger
import com.maddyhome.idea.vim.handler.EditorActionHandlerBase
import com.maddyhome.idea.vim.handler.Motion
import com.maddyhome.idea.vim.helper.EngineMessageHelper
import com.maddyhome.idea.vim.group.VimWindowGroup
import com.maddyhome.idea.vim.group.TabService
import com.maddyhome.idea.vim.history.VimHistory
import com.maddyhome.idea.vim.history.VimHistoryBase
import com.maddyhome.idea.vim.impl.state.VimStateMachineImpl
import com.maddyhome.idea.vim.key.ShortcutOwnerInfo
import com.maddyhome.idea.vim.key.VimKeyCodes
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.directory.WorkingDirectory
import com.maddyhome.idea.vim.highlight.Highlights
import com.maddyhome.idea.vim.message.MessageHistory
import com.maddyhome.idea.vim.redirect.Redirection
import com.maddyhome.idea.vim.quickfix.Quickfix
import com.maddyhome.idea.vim.macro.VimMacro
import com.maddyhome.idea.vim.macro.VimMacroBase
import com.maddyhome.idea.vim.key.interceptors.VimInputInterceptor
import com.maddyhome.idea.vim.register.VimRegisterGroup
import com.maddyhome.idea.vim.thinapi.VimPluginService
import com.maddyhome.idea.vim.thinapi.VimPluginServiceBase
import com.maddyhome.idea.vim.register.VimRegisterGroupBase
import com.maddyhome.idea.vim.state.VimStateMachine
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.undo.LineChange
import com.maddyhome.idea.vim.undo.VimKeyBasedUndoService
import com.maddyhome.idea.vim.undo.VimUndoRedo
import com.maddyhome.idea.vim.vimscript.model.commands.ExCommandProvider
import com.maddyhome.idea.vim.vimscript.model.commands.engineExCommandProvider
import com.maddyhome.idea.vim.vimscript.model.functions.VimscriptFunctionProvider
import com.maddyhome.idea.vim.vimscript.model.functions.engineFunctionProvider
import kotlin.math.max
import kotlin.math.min
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
open class VsCodeInjector(
  private val messageSink: MessageSink = MessageSink.Discarding,
  private val hostCommands: HostCommandRunner = HostCommandRunner.None,
  private val commandLineDisplay: CommandLineDisplay = NoDisplay,
  private val highlighter: Highlighter = Highlighter.None,
  private val clipboard: SystemClipboard = SystemClipboard.InMemory(),
  private val outputPanelService: VimOutputPanelService = DiscardingOutputPanel,
  /** How `:!` runs a shell command. Injectable so a test can assert on one without a shell. */
  private val processes: VimProcessGroup = NodeProcessGroup(),
  /** How `gx` opens a URL. Injectable so a test can assert on one without opening a browser. */
  private val opener: VimExternalOpener = VsCodeExternalOpener(),
  /** Where `:match` is painted. Injectable so a test can read the ranges instead of the colours. */
  private val matchPainter: VimMatchHighlighter = VsCodeMatchHighlighter(),
) : VsCodeInjectorBase() {

  /** The editors this host knows about. VS Code's own list is of `TextEditor`, not of these. */
  private val openEditors: MutableList<VsCodeEditor> = mutableListOf()

  init {
    // The quickfix list belongs to the session, and this object is built once per session. In a
    // running window that is a no-op at activation; in a test run it is what keeps one test's list
    // out of the next one's.
    Quickfix.reset()
    // And the current directory, which is a session's and not a window's - without this a `:cd` in
    // one test is still in force in the next one.
    WorkingDirectory.reset()
    // And the highlight groups: `:highlight` defines them for the session, so a group one test
    // defined would otherwise still be painting in the next.
    Highlights.reset()
    Redirection.reset()
    MessageHistory.reset()
  }

  /**
   * The one with focus, which several services need and none can work out for themselves.
   *
   * Search highlighting is the reason this is not just "the first one open": `'hlsearch'` paints
   * the editor the user is looking at, and with two files open, whichever happened to be
   * registered first is not it.
   */
  private var activeEditor: VsCodeEditor? = null

  fun register(editor: VsCodeEditor) {
    // Before `activeEditor` is moved: the window a new one is opened *from* is the one it takes its
    // window-local options from, and that is the window the user was looking at a moment ago.
    val opening = activeEditor
    if (openEditors.none { it === editor }) openEditors += editor
    activeEditor = editor

    // Local-to-window options exist per editor and start out unset, which is not the same as
    // starting out at their default: an option that has never been stored has no previous value,
    // and the overrides that watch for a *change* - `'foldlevel'` is the one that made this
    // visible, since `zR` only sets it - see the first set as initialisation and skip it. IntelliJ
    // does this when a window opens; this host has to do it when an editor is registered, which is
    // the same moment.
    //
    // Which *scenario* is the part that took a bug report. Every editor used to be initialised to
    // the option defaults, so `set nu rnu` in a `~/.vimrc` numbered the window it was read in and
    // no other: open a second file and the gutter was bare. Vim carries window-local options from
    // the window you opened from, and it evaluates the config in the context of the first window -
    // which this host does not have at activation, so the config runs against [fallbackWindow]
    // instead. `FALLBACK` is the scenario written for exactly that: it copies what the config set
    // into the first real window. `NEW` carries them on from there, which is Vim's `:new`, and is
    // what IdeaVim uses for every editor after the first.
    if (initialisedFirstEditor) {
      optionGroup.initialiseLocalOptions(editor, opening ?: fallbackWindow, LocalOptionInitialisationScenario.NEW)
    } else {
      initialisedFirstEditor = true
      optionGroup.initialiseLocalOptions(editor, fallbackWindow, LocalOptionInitialisationScenario.FALLBACK)
    }
  }

  /**
   * Whether any editor has been initialised, which decides the scenario for the next one.
   *
   * Not "is this editor initialised" - the option group answers that itself and returns early. This
   * is only about which window the *first* one inherits from, and there is one first window.
   */
  private var initialisedFirstEditor: Boolean = false

  fun unregister(editor: VsCodeEditor) {
    openEditors.removeAll { it === editor }
    if (activeEditor === editor) activeEditor = openEditors.lastOrNull()
  }

  // ---- Engine, or nearly. Nothing about a host in these beyond the ex commands this one adds.

  override val parser: VimStringParser by lazy { object : VimStringParserBase() {} }

  /**
   * Almost pure engine: the base class is complete, but `commandProviders` is the hook it leaves
   * open for a host with ex commands of its own, and this one has [VsCodeExCommandProvider].
   */
  override val vimscriptParser: VimscriptParser by lazy {
    object : VimscriptParserBase() {
      override val commandProviders: List<ExCommandProvider> =
        listOf(engineExCommandProvider, VsCodeExCommandProvider)
    }
  }

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
          messages.showStatusBarMessage(null, "Vimperor: sourcing $path from disk; it has unsaved changes")
        }
      }
    }
  }
  override val psiService: VimPsiService by lazy { TextOnlyPsiService }

  /** There is no spell checker in VS Code, and this is what says so. See [NoSpellchecker]. */
  override val spellcheckerService: SpellcheckerService get() = NoSpellchecker

  /** `:match` and its two twins, over decorations. See [VsCodeMatchHighlighter]. */
  override val matchHighlighter: VimMatchHighlighter get() = matchPainter
  /**
   * `~` and `$VAR` in a path, which `:sp`, `:e` and `:source` all hand to this first.
   *
   * The engine's own implementation, which used to live in the JVM source set for one line: it read
   * `user.home` for the tilde. Reading the environment instead is what a JavaScript runtime can do,
   * and is what Vim documents.
   */
  override val pathExpansion: VimPathExpansion by lazy { VimPathExpansionImpl() }

  /** `:tabclose`, `:tabonly`, `:tabmove` - see [VsCodeTabs]. */
  override val tabService: TabService by lazy { VsCodeTabs(hostCommands) }

  /** `:w`, `:q`, `<C-G>` - see [VsCodeFile], and the two of these that need more API than exists. */
  override val file: VimFile by lazy { VsCodeFile(hostCommands) }

  /** `<C-W>` - see [VsCodeWindowGroup], and the ways a VS Code editor group is not a Vim window. */
  override val window: VimWindowGroup by lazy { VsCodeWindowGroup(hostCommands) }

  override val markService: VimMarkService by lazy { object : VimMarkServiceBase() {} }
  override val vimState: VimStateMachine by lazy { VimStateMachineImpl() }
  override val historyGroup: VimHistory by lazy { object : VimHistoryBase() {} }
  override val registerGroup: VimRegisterGroup by lazy {
    // The listener is what makes `'clipboard'` do anything at all. See [VsCodeRegisterGroup].
    VsCodeRegisterGroup().also { it.initClipboardOptionListener() }
  }
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
        injector.messages.showErrorMessage(editor, "Vimperor: this completion needs a lookup, which this host does not have")
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
   * `:command`, which is a name and the line it stands for. Engine work, like the digraph table:
   * the aliases live in a map and expanding one is a string substitution.
   */
  override val commandGroup: VimCommandGroup by lazy { object : VimCommandGroupBase() {} }

  /**
   * `:loadkeymap`, which registers a table of `:lmap`s. Also engine work, and it took being asked
   * for to notice - it had been sitting in the IntelliJ module with nothing IntelliJ-shaped in it.
   */
  override val keymapGroup: VimKeymapGroup by lazy { object : VimKeymapGroupBase() {} }

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

      /**
       * Leaves the pasted lines exactly as they were, which is what Vim does.
       *
       * `:copy`, `:move` and `<C-R>` ask for the inserted range to be reindented. IdeaVim does it
       * through IntelliJ's code style, because pasting in an IDE is expected to fix the indent;
       * plain Vim reindents none of them, and IdeaVim itself skips this in Rider, CLion Nova and
       * the JetBrains client. VS Code's reindent is a command and therefore asynchronous, so this
       * host could not do it here even if it wanted to.
       *
       * The base class leaves this a `TODO`, and the ex command executor catches `NotImplementedError`
       * and turns it into "Not implemented yet :(" - so `:copy` inserted the text and then abandoned
       * the command half way, leaving the caret after the inserted lines instead of on them. Three
       * of IdeaVim's own `:copy` fixtures said so.
       */
      override fun doIndent(
        editor: VimEditor,
        caret: VimCaret,
        context: ExecutionContext,
        startOffset: Int,
        endOffset: Int,
      ): Int = endOffset

      /** IdeaVim tells the user that `ideaput` is available. There is nothing to suggest here. */
      override fun notifyAboutIdeaPut(editor: VimEditor?) {}
    }
  }
  override val listenersNotifier: VimListenersNotifier by lazy { VimListenersNotifier() }
  override val optionGroup: VimOptionGroup by lazy {
    object : VimOptionGroupBase() {
      init {
        // `zR` and `zM` do not run a command - they set `'foldlevel'`, and this is what makes that
        // mean anything. The mapper is the engine's, shared with the IntelliJ host, because
        // deciding that level zero closes everything is Vim's rule rather than an editor's.
        addOptionValueOverride(Options.foldlevel, FoldLevelOptionMapper())
      }
    }.also { it.initialiseOptions() }
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
      // `H`, `M` and `L`: the caret to the top, middle or bottom of what is on screen. See
      // [displayLine], which does the arithmetic, and `'startofline'`, which decides the column.
      // A count is a line in from the edge, so `1H` is `H` and the offset it names is one less.
      override fun moveCaretToFirstDisplayLine(editor: VimEditor, caret: ImmutableVimCaret, count: Int, normalizeToScreen: Boolean): Int =
        moveToDisplayLine(editor, caret, ScreenLocation.TOP, count - 1, normalizeToScreen)

      override fun moveCaretToMiddleDisplayLine(editor: VimEditor, caret: ImmutableVimCaret): Int =
        moveToDisplayLine(editor, caret, ScreenLocation.MIDDLE, 0, false)

      override fun moveCaretToLastDisplayLine(editor: VimEditor, caret: ImmutableVimCaret, count: Int, normalizeToScreen: Boolean): Int =
        moveToDisplayLine(editor, caret, ScreenLocation.BOTTOM, count - 1, normalizeToScreen)

      private fun moveToDisplayLine(
        editor: VimEditor,
        caret: ImmutableVimCaret,
        location: ScreenLocation,
        lineOffset: Int,
        normalizeToScreen: Boolean,
      ): Int {
        val vsCode = editor as? VsCodeEditor ?: return caret.offset
        val line = vsCode.displayLine(location, lineOffset, normalizeToScreen)
        return moveCaretToLineWithStartOfLineOption(editor, line, caret)
      }

      /**
       * `g0`, `g^`, `g$` and `gm` - the same motions, but across the *screen* line.
       *
       * They differ from `0`, `^` and `$` only when a buffer line is shown as more than one screen
       * line, or when the view has been scrolled sideways. `visibleRanges` carries no columns, so
       * this host cannot see either, and answers as if the line starts at column 0 and ends where
       * the buffer line ends - which is the right answer whenever the line fits on screen, and the
       * same answer Vim gives with `'nowrap'` and no horizontal scroll.
       *
       * `gm` is the exception that can be done properly: it is defined in terms of the width of the
       * window rather than of the line, and [EngineEditorHelper.getApproximateScreenWidth] is the
       * same 80 columns Vim's own default assumes.
       */
      override fun moveCaretToCurrentDisplayLineStart(editor: VimEditor, caret: ImmutableVimCaret): Motion =
        moveCaretToColumn(editor, caret, 0, false)

      override fun moveCaretToCurrentDisplayLineStartSkipLeading(editor: VimEditor, caret: ImmutableVimCaret): Int =
        editor.getLeadingCharacterOffset(caret.getLine(), 0)

      override fun moveCaretToCurrentDisplayLineEnd(editor: VimEditor, caret: ImmutableVimCaret, allowEnd: Boolean): Motion =
        moveCaretToColumn(editor, caret, editor.lineLength(caret.getLine()) - 1, allowEnd)

      override fun moveCaretToCurrentDisplayLineMiddle(editor: VimEditor, caret: ImmutableVimCaret): Motion {
        val width = injector.engineEditorHelper.getApproximateScreenWidth(editor) / 2
        val length = editor.lineLength(caret.getLine())
        return moveCaretToColumn(editor, caret, max(0, min(length - 1, width)), false)
      }

      /**
       * `gt` and `gT` - Vim's tabs, which are VS Code's editors within a group.
       *
       * A count in Vim means "go to tab number N" for `gt` and "back N tabs" for `gT`. VS Code has
       * a command for each direction and none that takes a number, so a count repeats the step -
       * right for `gT`, and for `gt` the nearest thing rather than the same thing.
       *
       * The caret does not move: this changes which editor is in front, and the offset returned is
       * the one in the editor the key was pressed in.
       */
      override fun moveCaretGotoNextTab(editor: VimEditor, context: ExecutionContext, rawCount: Int): Int {
        repeat(maxOf(1, rawCount)) { hostCommands.run(VsCodeCommands.NEXT_EDITOR, waitForIt = false) }
        return editor.currentCaret().offset
      }

      override fun moveCaretGotoPreviousTab(editor: VimEditor, context: ExecutionContext, rawCount: Int): Int {
        repeat(maxOf(1, rawCount)) { hostCommands.run(VsCodeCommands.PREVIOUS_EDITOR, waitForIt = false) }
        return editor.currentCaret().offset
      }
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
        when (keyStroke.keyCode) {
          VimKeyCodes.VK_ENTER -> listOf(InsertNewLineAction)
          // Delete and Backspace in Select mode, which the engine also settles by asking the host
          // what it has bound to them - see [DeleteSelectionAction]. Nowhere else asks.
          VimKeyCodes.VK_DELETE, VimKeyCodes.VK_BACK_SPACE -> listOf(DeleteSelectionAction)
          else -> emptyList()
        }
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

      // The viewport, read from `visibleRanges`. Visual lines rather than buffer lines is a
      // distinction this host does not yet have to make: it cannot fold, so they are the same.
      override fun getVisualLineAtTopOfScreen(editor: VimEditor): Int =
        (editor as? VsCodeEditor)?.screenTopLine ?: 0

      override fun getVisualLineAtBottomOfScreen(editor: VimEditor): Int =
        (editor as? VsCodeEditor)?.screenBottomLine ?: (editor.lineCount() - 1)

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
       * A marker that follows edits, which is what `:g` uses to remember the lines it matched.
       *
       * The comment here used to say VS Code has nothing equivalent and that this would have to be
       * built on `onDidChangeTextDocument`. It was already built, forty lines away: `DocumentBuffer`
       * tracks markers over the engine's own mutations, and says in its own comment that doing it
       * there is *more* exact than deriving it from VS Code's change events, which arrive after the
       * fact. Two names for one thing - `LiveRange` and `VimRangeMarker` - were enough to hide that
       * one of them was done.
       *
       * `:g/pattern/command` was the whole of what this cost. It reported "Not implemented yet :("
       * and no sweep caught it, because a bare `:g` is a Vim error before it reaches this.
       */
      override fun createRangeMarker(editor: VimEditor, startOffset: Int, endOffset: Int): VimRangeMarker {
        val buffer = (editor as VsCodeEditor).buffer
        val range = buffer.createMarker(startOffset, endOffset)
        return object : VimRangeMarker {
          private var disposed = false
          override val startOffset: Int get() = range.startOffset
          override val endOffset: Int get() = range.endOffset
          override val isValid: Boolean get() = !disposed && !editor.isDisposed()

          override fun dispose() {
            disposed = true
            buffer.removeMarker(range)
          }
        }
      }
    }
  }

  /**
   * `<Action>` mappings, `:action` and `:actionlist` - and the folds, `gd` and Vim's own actions.
   *
   * Held at its own type as well as the interface's, because [rememberActions] has to reach past
   * `VimActionExecutor`: the list of what VS Code can do arrives over a promise at activation and
   * there is nowhere in the engine's interface to put it.
   */
  private val actions: VsCodeActionExecutor by lazy { VsCodeActionExecutor(hostCommands) }
  override val actionExecutor: VimActionExecutor get() = actions

  /** What VS Code answered when asked what commands it has. See [VsCodeActionExecutor]. */
  internal fun rememberActions(ids: Collection<String>) = actions.remember(ids)

  /** What VS Code has bound to those commands, for `:actionlist`. See [KeybindingTable]. */
  internal val keybindings: KeybindingTable get() = actions.keybindings

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
   * Vim's `<C-E>`, `<C-D>` and `zt` all move the *view* and let the caret follow, so they need
   * `visibleRanges` to work out where the view currently is and `editorScroll` to move it. See
   * [Viewport.kt] for why the reveal API that looks written for this is not used.
   */
  override val scroll: VimScrollGroup by lazy { VsCodeScrollGroup }

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
        hostCommands.run(VsCodeCommands.UNDO)
        return true
      }

      override fun redo(editor: VimEditor, context: ExecutionContext): Boolean {
        hostCommands.run(VsCodeCommands.REDO)
        return true
      }

      override fun setMergeUndoKey() {}
      override fun updateNonMergeUndoKey() {}
      override fun setInsertNonMergeUndoKey(refresh: Boolean) {}
    }
  }

  /** `U`. See [VsCodeLineChange] for why the sweep's description of what it needed was wrong. */
  override val lineChange: LineChange by lazy { VsCodeLineChange() }

  /**
   * Vim's `=~`, `:catch /pattern/` and `split()`, which match a Vim regex against a plain string.
   *
   * `VimRegexServiceBase` is the engine's own class over the engine's own regex engine, and the
   * IntelliJ host does no more than name it. This host had it down as "does not provide
   * regexpService yet" - which was true of the line and not of the work.
   */
  override val regexpService: VimRegexpService by lazy { VimRegexServiceBase() }

  /**
   * `:autocmd` and `:augroup`. The registry is `AutoCmdImpl`, which is the engine's now.
   *
   * A host supplies the *events* rather than the bookkeeping - see `Extension.kt`, which fires
   * `BufEnter`/`BufLeave` when VS Code changes the active editor and `FocusGained`/`FocusLost` when
   * the window gains or loses focus. IdeaVim fires the same set from its own listeners.
   */
  override val autoCmd: AutoCmdService by lazy { AutoCmdImpl() }

  /**
   * The editor the engine asks about when there is no window to ask about.
   *
   * This was on the list of services this host does not provide, described there as being for "no
   * window at all" - which made it sound like a corner nobody reaches. Every scope in the thin API
   * resolves its editor as `projectId?.let { getSelectedEditor(it) } ?: injector.fallbackWindow`,
   * and a plugin's `init` runs with a null project id by construction, because there is no editor
   * yet while a plugin is declaring its mappings. The option group needs it for a second reason:
   * the global values of window-local options have to be stored against *some* window when none is
   * open.
   *
   * One editor, made once and kept, because option values are stored against the editor object - a
   * fresh one each time would be a fresh set of options each time.
   *
   * Its local options are initialised the same way [register] initialises a real editor's, and for
   * a reason that is easy to miss: an option that has never been stored has no value to read, and
   * the per-window "global" values of window-local options live in exactly this editor when no
   * window is open. IdeaVim's own fallback window does the same thing on the line after it is made.
   */
  override val fallbackWindow: VimEditor by lazy {
    VsCodeEditor(DetachedTextEditor()).also {
      optionGroup.initialiseLocalOptions(it, null, LocalOptionInitialisationScenario.DEFAULTS)
    }
  }

  /**
   * What a plugin asks the host for, which turns out not to need one.
   *
   * The reason recorded against this was that it was blocked behind `modalInput.activate`, and that
   * was about a different part of the extension system: nothing here reads a key. Running
   * normal-mode keys is the key handler, exporting an operator function is a Vimscript declaration,
   * and adding a command is an alias - all three the engine's, and shared with IdeaVim now.
   */
  override val pluginService: VimPluginService by lazy { object : VimPluginServiceBase() {} }

  /**
   * Prompts that answer one keystroke at a time - `:s///c` is the one the engine opens.
   *
   * See [StatusBarModalInput]. What is still missing is the *other* half of the interface,
   * `activate`, which blocks on a modal event loop until a key arrives: that is what `getchar()`
   * and IdeaVim's bundled extensions are built on, and JavaScript has one thread and no way to stop
   * it. The default implementation does nothing, which is the honest answer.
   */
  override val modalInput: VimModalInputService by lazy { VsCodeModalInputService(commandLineDisplay) }

  /**
   * `:!cmd`, and the filter form `:%!sort`.
   *
   * These had been missing without ever appearing on a list, which is the interesting part: `:!`
   * and `:read` lived in IdeaVim's *IntelliJ* module, and the ex command sweep walks the engine's
   * registry - so a command that was never registered there is not a hole in the sweep's eyes, it
   * is an absence. Both are now in vim-engine, where nothing about them needed IntelliJ.
   */
  override val processGroup: VimProcessGroup get() = processes

  /** `gx`, and `:help`. See [VsCodeExternalOpener] for why no sweep had reported it missing. */
  override val externalOpener: VimExternalOpener by lazy { opener }

  /**
   * The `:` and `/` prompts.
   *
   * Not an input dialog: Vim's command line is a text buffer the engine drives keystroke by
   * keystroke, so a host supplies a string and somewhere to draw it. See [StatusBarCommandLine] -
   * the asynchronous `showInputBox` was the wrong shape for this, not a hard version of the right
   * one.
   */
  override val commandLine: VimCommandLineService by lazy { VsCodeCommandLineService(commandLineDisplay, highlighter) }

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

private class VsCodeMessages(private val sink: MessageSink) : VimMessagesBase() {
  private var statusBar: String? = null
  private var error = false

  override fun displayMessage(editor: VimEditor, message: String?) {
    if (isSilent) return
    sink.message(message)
  }

  override fun displayErrorMessage(editor: VimEditor, message: String?) {
    // The flag is still set either way: `:silent!` hides the report, not the fact that the command
    // failed. `:silent` on its own hides nothing here - an error is what it is meant to let through.
    error = true
    if (isSilentAboutErrors) return
    sink.error(message)
  }

  override fun appendDisplayedErrorMessage(editor: VimEditor, message: String?) {
    error = true
    if (isSilentAboutErrors) return
    sink.error(message)
  }

  override fun displayStatusBarMessage(editor: VimEditor?, message: String?) {
    if (isSilent) return
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
 * The registers, plus the two decisions `'clipboard'` leaves to a host.
 *
 * [initClipboardOptionListener] is what makes the option do anything at all: it sets the default
 * register to `*` or `+` when `'clipboard'` names `unnamed` or `unnamedplus`, and it is a call
 * `VimRegisterGroupBase` leaves to whoever builds one. IdeaVim makes it from its register group's
 * constructor; this host had no register group of its own to make it from, so
 * `set clipboard^=unnamed,unnamedplus` was parsed, stored, reported back by `:set clipboard?` - and
 * ignored. Every yank went to `"` and the system clipboard never heard about it.
 *
 * [isPrimaryRegisterSupported] is the other. The engine asks it to tell X11's PRIMARY selection -
 * the one middle-click pastes, which Vim calls `"*` - from the clipboard every platform has, and it
 * answers from `$DISPLAY` and the platform name, which is right for a process that can talk to an X
 * server. This one cannot. `env.clipboard` is the whole of VS Code's clipboard API and it is the
 * CLIPBOARD selection on every platform, so on a Linux with `$DISPLAY` set the engine would route
 * `"*` to a selection this host has no way to reach - and `set clipboard=unnamed`, whose whole
 * effect is to make the default register `"*`, would write to nothing. False here says the true
 * thing, and the engine then does what it does on macOS and Windows: `"*` and `"+` name the one
 * clipboard there is.
 *
 * What that costs is `autoselect`, `autoselectml` and `autoselectplus`, which ask for the visual
 * selection to be published as it is made. They describe owning a selection *separate* from the
 * clipboard - that is the whole point of PRIMARY, and why publishing to it on every `v` is
 * harmless. Doing the same to the clipboard would destroy the user's clipboard on every visual
 * motion, so they are accepted and do nothing, which is what Vim does on a build without `+X11`.
 * `html` (a GUI-only paste format) and `exclude:{pattern}` (whether to connect to an X server at
 * all) are no-ops here for the same reason, and `ideaput` is IntelliJ's.
 */
private class VsCodeRegisterGroup : VimRegisterGroupBase() {
  override fun isPrimaryRegisterSupported(): Boolean = false
}

/**
 * See [VsCodeInjector.clipboardManager] for why this is not the system clipboard.
 *
 * "Transferable data" is IntelliJ's rich payload travelling with a copy - syntax-highlighted text,
 * imports to add on paste. VS Code copies plain text, so there is none.
 */
private class RegisterBackedClipboard(private val clipboard: SystemClipboard) : VimClipboardManager {

  /**
   * `"*`, which on this host is the same clipboard as `"+` - see [VsCodeRegisterGroup].
   *
   * These two used to hold an in-memory value of their own, so that `"*` and `"+` could differ the
   * way they do under X11. Nothing could ever read that value: VS Code has one clipboard API, and
   * a `"*` nobody outside the process can see is not a primary selection, it is a lost yank.
   */
  override fun getPrimaryContent(editor: VimEditor, context: ExecutionContext): VimCopiedText? =
    getClipboardContent(editor, context)

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
  ): Boolean = setClipboardContent(editor, context, textData)

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

/**
 * An application that can be asked to replay a key later. See `postKey`.
 *
 * Named separately from the service interface because [VimHost] is the thing that drains it, and
 * `VimApplication` has no idea a key queue exists - it only has the end that puts keys in.
 */
internal interface PostingApplication {
  fun takePostedKeys(): List<VimKeyStroke>
}

internal object SingleThreadedApplication : VimApplication, PostingApplication {
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

  /**
   * A key the engine wants handled *after* the one being handled now.
   *
   * One caller: `<C-V>` in Insert mode collects a numeric literal - `<C-V>065` types `A` - and the
   * key that ends the number is not part of it, so it has to be replayed. IdeaVim posts it to
   * Swing's event queue and skips the whole thing under test because it needs one.
   *
   * A queue rather than a nested `handleKey`: the engine is still inside the digraph when it calls
   * this, and re-entering the handler there would interleave two commands. [VimHost] drains this
   * after the stroke it is on, which is what "post" means and is what Swing was being used for.
   */
  override fun postKey(stroke: VimKeyStroke, editor: VimEditor) {
    posted += stroke
  }

  private val posted: MutableList<VimKeyStroke> = mutableListOf()

  /** Takes what was posted, leaving the queue empty. */
  override fun takePostedKeys(): List<VimKeyStroke> {
    if (posted.isEmpty()) return emptyList()
    val keys = posted.toList()
    posted.clear()
    return keys
  }
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
  override fun show(text: String, caret: Int?) {}
  override fun showMatches(line: String?) {}
  override fun hide() {}
}

/** Pressing Enter, which `o` and `O` reach for through the host rather than doing themselves. */
internal object InsertNewLineAction : NativeAction {
  override val action: Any = "ideavim.insertNewLine"
}

/**
 * Pressing Delete or Backspace, which Select mode reaches for the same way.
 *
 * `SelectDeleteBackspaceActionBase` runs whatever the host has bound to the key and then leaves
 * Select mode, rather than deleting the selection itself - because a host might have something else
 * bound there. This host has not, so the action is the deletion.
 */
internal object DeleteSelectionAction : NativeAction {
  override val action: Any = "ideavim.deleteSelection"
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
  /**
   * @param waitForIt whether the user's keys should be held until the command lands. True for
   *   anything that rewrites the document behind the engine's back - undo, redo, reformatting -
   *   and false for anything that only changes what is on screen.
   */
  fun run(command: String, waitForIt: Boolean = true) = run(command, emptyArray(), waitForIt)

  /**
   * The same, for the few VS Code commands that take an argument.
   *
   * `vscode.open` is why this exists: opening a file by name is a command like the folds and the
   * splits, except that the file has to be named in the call. Keeping it in this lane rather than
   * calling `showTextDocument` directly means it inherits the queue, the rejection branch and the
   * id check, none of which a second path would have.
   */
  fun run(command: String, arguments: Array<Any?>, waitForIt: Boolean = true)

  /** For a host that has no VS Code to run commands in. Nothing happens, and nothing pretends to. */
  object None : HostCommandRunner {
    override fun run(command: String, arguments: Array<Any?>, waitForIt: Boolean) {}
  }
}
