/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.intellij.vim.api.VimInitApi
import com.maddyhome.idea.vim.api.VimExtensionRegistrator
import com.maddyhome.idea.vim.extension.abolish.init as abolishInit
import com.maddyhome.idea.vim.extension.argtextobj.init as argTextObjInit
import com.maddyhome.idea.vim.extension.camelcasemotion.init as camelCaseMotionInit
import com.maddyhome.idea.vim.extension.classtextobj.init as classTextObjInit
import com.maddyhome.idea.vim.extension.commentary.COMMENTARY_COMMAND
import com.maddyhome.idea.vim.extension.commentary.init as commentaryInit
import com.maddyhome.idea.vim.extension.exchange.disposeExchange
import com.maddyhome.idea.vim.extension.exchange.init as exchangeInit
import com.maddyhome.idea.vim.extension.functextobj.init as funcTextObjInit
import com.maddyhome.idea.vim.extension.highlightedyank.disposeHighlightedYank
import com.maddyhome.idea.vim.extension.highlightedyank.init as highlightedYankInit
import com.maddyhome.idea.vim.extension.indentwise.init as indentWiseInit
import com.maddyhome.idea.vim.extension.miniai.init as miniAiInit
import com.maddyhome.idea.vim.extension.multiplecursors.disposeMultipleCursors
import com.maddyhome.idea.vim.extension.nerdtree.disposeNerdTree
import com.maddyhome.idea.vim.extension.nerdtree.init as nerdTreeInit
import com.maddyhome.idea.vim.extension.multiplecursors.init as multipleCursorsInit
import com.maddyhome.idea.vim.extension.paragraphmotion.init as paragraphMotionInit
import com.maddyhome.idea.vim.extension.replacewithregister.init as replaceWithRegisterInit
import com.maddyhome.idea.vim.extension.sneak.disposeSneak
import com.maddyhome.idea.vim.extension.surround.init as surroundInit
import com.maddyhome.idea.vim.extension.sneak.init as sneakInit
import com.maddyhome.idea.vim.extension.targets.init as targetsInit
import com.maddyhome.idea.vim.extension.textobjentire.init as textObjEntireInit
import com.maddyhome.idea.vim.extension.textobjindent.init as textObjIndentInit
import com.maddyhome.idea.vim.extension.textobjuser.init as textObjUserInit
import com.maddyhome.idea.vim.extension.textobjuser.unregisterTextObjUserFunctions
import com.maddyhome.idea.vim.extension.yankring.disposeYankRing
import com.maddyhome.idea.vim.extension.yankring.init as yankRingInit
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.ListenerOwner
import com.maddyhome.idea.vim.extension.ExtensionBean
import com.maddyhome.idea.vim.extension.ExtensionLoader
import com.maddyhome.idea.vim.extension.JsonExtensionProvider
import com.maddyhome.idea.vim.key.MappingOwner
import com.maddyhome.idea.vim.api.setToggleOption
import com.maddyhome.idea.vim.api.unsetToggleOption
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.options.OptionDeclaredScope
import com.maddyhome.idea.vim.options.ToggleOption
import com.maddyhome.idea.vim.thinapi.VimApiImpl

/**
 * How a Vim extension reaches this host.
 *
 * IdeaVim has two extension systems and only one of them can come here. The old one is the
 * `VimExtension` interface registered through the IntelliJ extension point `IdeaVIM.vimExtension`,
 * which is IntelliJ by construction. The new one - the "thin API" - is a function annotated
 * `@VimPlugin` and written against `com.intellij.vim.api`, and *nothing about it is
 * IntelliJ-shaped*: the `api` module is Kotlin Multiplatform with a JS target, and `VimApiImpl`,
 * which is the whole implementation of what an extension is handed, already lives in
 * `vim-engine/src/commonMain` with not one IntelliJ import in it.
 *
 * So an extension written against the thin API is portable source, and this file is the last piece
 * that was missing: the host half. IntelliJ's is six files and 525 lines; most of that is a config
 * file on disk, with locking, so that extensions shipped as *separate IntelliJ plugins* survive a
 * restart. This host has no such thing - every extension it knows about is compiled into it - so
 * the whole of that machinery reduces to a map.
 *
 * ## Why a map rather than a class name
 *
 * `ExtensionBean` carries a `className`, and IntelliJ turns it into an instance by asking a
 * classloader. **Kotlin/JS has no reflection by name**, so this host cannot. It keeps the function
 * itself instead, looked up by extension name - which is the same answer the ex commands and the
 * Vimscript functions already reach for on this side, where the engine's registries are turned into
 * a generated table of constructors rather than a list of names to reflect on.
 *
 * The `className` on the bean is therefore ignored here rather than absent: it is what the engine's
 * own type carries, and a bean that lies about a class nothing will load is worse than one that
 * carries a name nothing reads.
 *
 * ## What is bundled
 *
 * One so far, and adding the next is one entry in [BUNDLED] plus a source file the engine can
 * compile - which is now the whole cost, where before it was this file.
 */
internal object VsCodeExtensions {

  /**
   * Every extension compiled into this host, by the name `:set <name>` and `:Plug` use.
   *
   * The value is the extension's `init` - the function `@VimPlugin` annotates - taking the
   * [VimInitApi] it registers its mappings and text objects through.
   */
  val BUNDLED: Map<String, (VimInitApi) -> Unit> = mapOf(
    // `gr{motion}`, `grr`, `gr` in visual: replace the text a motion covers with a register,
    // without the register being clobbered by what was replaced. The function is `init`, which is
    // what `@VimPlugin` annotates, and it is an extension function on `VimInitApi` rather than one
    // taking it - hence the lambda rather than a reference.
    "ReplaceWithRegisterNew" to { api -> api.replaceWithRegisterInit() },

    // `{` and `}` that treat a line of only whitespace as blank, which Vim's own do not.
    "vim-paragraph-motion" to { api -> api.paragraphMotionInit() },

    // `ae` and `ie`: the whole buffer as a text object, so `gUie` composes where `ggVG` does not.
    "textobj-entire" to { api -> api.textObjEntireInit() },

    // `a`/`i` text objects that search for their delimiter rather than needing the caret inside
    // it, so `ci(` works from anywhere on the line.
    "mini-ai" to { api -> api.miniAiInit() },

    // `,w` `,b` `,e` and their text objects, moving by the parts of an identifier rather than by
    // whitespace: `,w` on `getUserName` stops at `User`, where `w` jumps past the whole thing.
    "CamelCaseMotion" to { api -> api.camelCaseMotionInit() },

    // `[-` `]-` `[+` `]+` `[=` `]=` `[%` `]%`: move by indentation level, which in a language whose
    // blocks are indentation is structural movement without a parser.
    "indentwise" to { api -> api.indentWiseInit() },

    // `textobj#user#plugin(...)`, which is how a vimrc declares text objects of its own: a pattern
    // and the keys to select it with. The only bundled extension that registers no keys at all.
    "textobj-user" to { api -> api.textObjUserInit() },

    // `targets.vim`: seeking text objects. `ci(` when the caret is nowhere near a paren, `cin(` for
    // the next one, `cil(` for the last, and `I`/`A` for the whitespace-trimmed and -extended forms.
    "targets" to { api -> api.targetsInit() },

    // `vim-abolish`: `crs` `crc` `crm` and the rest, recasing the word under the caret between
    // snake, camel, Pascal and friends; plus `:Subvert`, a search and replace that carries the
    // case of what it replaced.
    "abolish" to { api -> api.abolishInit() },

    // `vim-indent-object`: `ai` and `ii` for a block at the caret's indentation level, which in
    // Python or YAML is the block itself.
    "textobj-indent" to { api -> api.textObjIndentInit() },

    // `argtextobj.vim`: `ia` and `aa` for one argument of a call, commas and nesting and quoted
    // strings all accounted for.
    "argtextobj" to { api -> api.argTextObjInit() },

    // `vim-commentary`: `gc{motion}`, `gcc`, `gcu` and `:Commentary`. The comment syntax is VS
    // Code's, from the language configuration - see `VsCodeCommentService`. `dgc`, the text object
    // over a run of comment lines, needs a syntax tree and does nothing here.
    "commentary" to { api -> api.commentaryInit() },

    // `vim-highlightedyank`: the text a yank covered flashes, so you can see what you took.
    // `g:highlightedyank_highlight_duration` and `..._color` set how long and what colour.
    "highlightedyank" to { api -> api.highlightedYankInit() },

    // `vim-exchange`: `cx{motion}` marks a region, `cx` on a second one swaps the two. `cxx` for
    // lines, `X` in visual, `cxc` to forget the mark.
    "exchange" to { api -> api.exchangeInit() },

    // `vim-sneak`: `s{char}{char}` jumps to the next occurrence of the pair, `S` backwards, `;` and
    // `,` repeat. The two characters arrive through the engine's modal input rather than a blocking
    // read - see `readCharacters`, which is what let this one leave the plugin at all.
    "sneak" to { api -> api.sneakInit() },

    // `vim-surround`: `ys{motion}{char}` wraps, `cs{from}{to}` changes what wraps, `ds{char}`
    // unwraps, `S` in visual. The last of the 26 that had to ask the user for something before it
    // could act, and the whole reason `readKeys` exists.
    "surround" to { api -> api.surroundInit() },

    // `vim-multiple-cursors`: `<C-n>` puts a caret on the next occurrence of the word under the
    // caret, `<C-x>` skips one, `<C-p>` takes the last one back.
    "multiple-cursors" to { api -> api.multipleCursorsInit() },

    // `YankRing.vim`: every yank, delete and change goes into a ring, and `<C-P>` / `<C-N>` walk
    // the text of the last paste back and forth through it. The walk undoes the paste and repeats
    // it with another entry, so it needs the host's undo to have landed before the re-paste - see
    // `VimApplication.runAfterHostCatchesUp`, which is what let this one be bundled here at all.
    "yankring" to { api -> api.yankRingInit() },

    // `vim-textobj-function`: `am` and `aM` for a function definition without and with its doc
    // comment, `im` for its body.
    "functextobj" to { api -> api.funcTextObjInit() },

    // `vim-textobj-class`: `ac` for the class, interface, struct or enum the caret is inside.
    //
    // Both of these ask `injector.psiService` where a function or a class is, and both were left
    // out until this host could answer. It answers from `DocumentSymbols` now - a cache of what
    // VS Code's own Outline view knows, refreshed in the background, because the question is asked
    // in the middle of a keystroke and the answer arrives over a promise.
    "classtextobj" to { api -> api.classTextObjInit() },

    // `NERDTree`, or the half of it that is not a Swing tree: `:NERDTree`, `:NERDTreeToggle`,
    // `:NERDTreeFind` and their friends, over the Explorer. The keys NERDTree maps *inside* the
    // tree are not here and cannot be - a key pressed in the sidebar never reaches an extension -
    // and what `package.json` binds there instead is listed with them.
    "NERDTree" to { api -> api.nerdTreeInit() },
  )

  /**
   * What disabling an extension has to undo *by name*, for the few that need it.
   *
   * The engine tracks mappings and listeners by owner, and [VsCodeExtensionLoader.disableExtension]
   * removes both without knowing anything about the extension. Two things escape that: a Vimscript
   * function handler, which the function service holds by name, and a command alias, which the
   * command group holds by name. So an extension that registers either has to name them again on
   * the way out, and this is where it says so.
   *
   * IntelliJ reaches the same code through `VimExtension.dispose`, which is why these call the same
   * engine functions the plugin's adapters do rather than repeating the logic.
   */
  val TEARDOWN: Map<String, () -> Unit> = mapOf(
    // `textobj#user#plugin` and `textobj#user#map`, held by the function service.
    "textobj-user" to { unregisterTextObjUserFunctions() },

    // `:Commentary`, held by the command group.
    "commentary" to { injector.commandGroup.removeAlias(COMMENTARY_COMMAND) },

    // Its yank and mode listeners, which go straight onto `listenersNotifier` rather than through
    // the thin API's listener scope - so no owner covers them - plus any highlight still showing.
    "highlightedyank" to { disposeHighlightedYank() },

    // A region marked and never exchanged, and the highlight showing where it is.
    "exchange" to { disposeExchange() },

    // The highlight on the pair it last jumped to, which fades on a timer nobody else owns.
    "sneak" to { disposeSneak() },

    // What the last cursor was added from, per buffer.
    "multiple-cursors" to { disposeMultipleCursors() },

    // `:YRShow`, `:YRClear` and `:YRReplace`, held by the command group, plus the register listener
    // that fills the ring, which goes onto `listenersNotifier` under no owner at all.
    "yankring" to { disposeYankRing() },

    // Its six ex commands, held by the command group.
    "NERDTree" to { disposeNerdTree() },
  )

  /** The id a bundled extension belongs to, which for this host is the extension itself. */
  const val PLUGIN_ID: String = "com.github.neshkeev.vimperor"

  fun beanFor(name: String): ExtensionBean =
    ExtensionBean(extensionName = name, pluginId = PLUGIN_ID, functionName = "init", className = "")
}

/**
 * `set surround`, which is how an `.ideavimrc` actually turns an extension on - and which did
 * nothing at all on this host until now.
 *
 * IdeaVim's own documentation enables every one of these with `set <name>`; `Plug` is the *other*
 * way in, for a config borrowed from Vim. Both hosts were reaching `enableExtension` through
 * `Plug`, so `Plug 'tpope/vim-surround'` worked here and `set surround` was `E518: Unknown option`
 * - silently, because a config runs with errors suppressed. Every bundled extension was affected
 * and nothing said so.
 *
 * What was missing is exactly what `VimExtensionRegistrar` does for IntelliJ: registering an
 * extension has to register a global toggle option named after it, and the option's listener is
 * what enables and disables it. That also makes `set nosurround` work, which nothing here could
 * express before, and makes `:set surround?` answer truthfully.
 *
 * `VimEverywhere` is the extension that made this impossible to leave: it has no repository and no
 * `Plug` line anywhere in the wild, so `set VimEverywhere` is the only way anyone turns it on.
 */
internal fun registerExtensionOptions() {
  VsCodeExtensions.BUNDLED.keys.forEach { name ->
    // The option and its listener have different lifetimes, and conflating them cost an afternoon.
    // `Options` is a Kotlin object, so an option declared by one host is still declared for the
    // next one in the same process; the *listener* lives on the option group, which is new with
    // every injector. Skipping the whole block when the option already existed left every host
    // after the first with an option nothing was listening to - which is `set surround` silently
    // doing nothing all over again, one layer down.
    val option = injector.optionGroup.getOption(name) as? ToggleOption
      ?: ToggleOption(name, OptionDeclaredScope.GLOBAL, abbreviationFor(name), false)
        .also { injector.optionGroup.addOption(it) }
    injector.optionGroup.addGlobalOptionChangeListener(option) {
      val on = injector.optionGroup.getOptionValue(option, OptionAccessScope.GLOBAL(null)).booleanValue
      if (on) {
        injector.jsonExtensionProvider.getExtension(name)?.let { injector.extensionLoader.enableExtension(it) }
      } else {
        injector.extensionLoader.disableExtension(name)
      }
    }
  }
}

/**
 * An option's short form, which for all but one of these is the name itself.
 *
 * `NERDTree` is the exception and it is IdeaVim's, kept because a config written for IdeaVim may
 * use it: an abbreviation that differs only in case is how `set nerdtree` is made to work at all,
 * since option names are matched case-sensitively.
 */
private fun abbreviationFor(name: String): String = if (name == "NERDTree") "nerdtree" else name

/**
 * The catalogue of extensions this host could enable.
 *
 * IntelliJ's implementation of this interface reads and writes a JSON file under the config
 * directory, under a file lock, because an IntelliJ plugin can add extensions at runtime and they
 * have to be remembered across restarts. Nothing can add an extension to this host at runtime: the
 * set is fixed when the `.vsix` is built. So this is that same catalogue held in memory, and
 * [addExtension] exists for the day a VS Code extension can contribute one.
 */
internal class VsCodeJsonExtensionProvider : JsonExtensionProvider {

  private val extensions: MutableMap<String, ExtensionBean> = mutableMapOf()

  override fun init() {
    extensions.clear()
    VsCodeExtensions.BUNDLED.keys.forEach { extensions[it] = VsCodeExtensions.beanFor(it) }
  }

  override fun getExtension(name: String): ExtensionBean? = extensions[name]

  override fun getAllExtensions(): List<ExtensionBean> = extensions.values.toList()

  /** Every extension here is bundled; there is no other kind on this host. */
  override fun getBundledExtensions(): List<ExtensionBean> = getAllExtensions()

  override fun getExtensionsForPlugin(pluginId: String): List<ExtensionBean> =
    extensions.values.filter { it.pluginId == pluginId }

  override fun addExtension(extension: ExtensionBean) {
    extensions[extension.extensionName] = extension
  }

  override fun addExtensions(extensions: Collection<ExtensionBean>) {
    extensions.forEach { addExtension(it) }
  }

  override fun removeExtensionForPlugin(pluginId: String) {
    extensions.entries.removeAll { it.value.pluginId == pluginId }
  }
}

/**
 * Turns an entry in the catalogue on and off.
 *
 * The same shape as `IjExtensionLoader`, with the one difference that matters: where IntelliJ asks
 * a classloader for `bean.className`, this looks the function up in [VsCodeExtensions.BUNDLED].
 *
 * Disabling has to undo what `init` did. Most of what an extension registers - mappings and
 * listeners - the engine tracks by owner, so both owners are derived from the extension's name
 * exactly as they are when it is enabled, and removing them is most of the teardown. What the
 * engine holds *by name* rather than by owner - a Vimscript function handler, a command alias - has
 * to be named again, and [VsCodeExtensions.TEARDOWN] is where an extension that needs it says so.
 * IntelliJ reaches the same functions through `VimExtension.dispose`.
 */
internal class VsCodeExtensionLoader : ExtensionLoader {

  private val enabled: MutableMap<String, ExtensionBean> = mutableMapOf()

  override fun getEnabledExtensions(): Collection<ExtensionBean> = enabled.values

  override fun enableExtension(extension: ExtensionBean) {
    val name = extension.extensionName
    if (name in enabled) return

    val init = VsCodeExtensions.BUNDLED[name] ?: return
    // Recorded before `init` runs, not after: an extension that registers a mapping which fires
    // during its own initialisation would otherwise look disabled to whatever the mapping calls.
    enabled[name] = extension
    init(VimInitApi(VimApiImpl(ListenerOwner.Plugin.get(name), MappingOwner.Plugin.get(name), null)))
    optionFor(name)?.let { injector.optionGroup.setToggleOption(it, OptionAccessScope.GLOBAL(null)) }
  }

  override fun disableExtension(name: String) {
    if (enabled.remove(name) == null) return
    injector.keyGroup.removeKeyMapping(MappingOwner.Plugin.get(name))
    injector.listenersNotifier.unloadListeners(ListenerOwner.Plugin.get(name))
    VsCodeExtensions.TEARDOWN[name]?.invoke()
    optionFor(name)?.let { injector.optionGroup.unsetToggleOption(it, OptionAccessScope.GLOBAL(null)) }
  }

  /**
   * The option is the state, and the loader keeps it in step - in both directions.
   *
   * Writing it back here rather than only in [VsCodeExtensionRegistrator] is what makes the two
   * ways in agree. The engine's own `:Plug` and `:packadd` call `enableExtension` straight, without
   * going near an alias, and a test calls `disableExtension` straight; each of those would
   * otherwise leave the option saying the opposite of the truth, and the next `set surround` would
   * be a no-op because the value it wanted was already there.
   *
   * The recursion this appears to invite does not happen: setting the option calls back into these
   * two, and both return at once when the extension is already in the state asked for.
   */
  private fun optionFor(name: String): ToggleOption? = injector.optionGroup.getOption(name) as? ToggleOption
}

/**
 * `:Plug 'author/name'`, and the `'name'` half of `:set name`.
 *
 * A `.vimrc` written for Vim names a plugin by its repository - `tpope/vim-surround` - and IdeaVim
 * answers by mapping the tail of that to the extension it bundles under a shorter name. That
 * mapping is the whole of this interface, and it is what lets a borrowed config enable an extension
 * without being rewritten.
 *
 * The aliases are the ones IdeaVim publishes, kept whole rather than trimmed to what is bundled
 * today: an alias for an extension this host does not have costs nothing - the lookup fails the
 * same way an unknown name does - and trimming it would mean adding it back with the extension,
 * which is exactly the kind of two-place edit that gets done once.
 */
internal class VsCodeExtensionRegistrator : VimExtensionRegistrator {

  /**
   * Straight to the loader, which sets the option on the way - see `VsCodeExtensionLoader`.
   *
   * `Plug 'tpope/vim-surround'` and `set surround` have to leave the same state behind, or
   * `set nosurround` after a `Plug` line would find the option still false and do nothing. That is
   * arranged in the loader rather than here, because the engine's own `:Plug` and `:packadd` reach
   * the loader without passing through this at all.
   */
  override fun setOptionByPluginAlias(alias: String): Boolean {
    val name = getExtensionNameByAlias(alias) ?: return false
    val bean = injector.jsonExtensionProvider.getExtension(name) ?: return false
    injector.extensionLoader.enableExtension(bean)
    return true
  }

  override fun getExtensionNameByAlias(alias: String): String? =
    ALIASES[alias.trim().removeSurrounding("'").removeSurrounding("\"").substringAfterLast('/')]

  private companion object {
    /**
     * By the repository tail, which is what `:Plug` and `Plugin` lines actually carry.
     *
     * Transcribed from the plugin's own `IdeaVIM.ideavim-frontend.xml`, where every bundled
     * extension declares its aliases, rather than written from the documentation - which is how the
     * first version of this table came to say `camelcasemotion` for an extension actually named
     * `CamelCaseMotion`, and `miniai` for one named `mini-ai`. A wrong alias fails the way an
     * unknown one does, silently, so nothing caught it.
     *
     * Kept whole rather than trimmed to what is bundled today: an alias for an extension this host
     * does not have costs nothing, and trimming it would mean adding it back with the extension,
     * which is exactly the kind of two-place edit that gets done once.
     *
     * The one divergence from the XML is `ReplaceWithRegister`. IdeaVim has two of them - the old
     * one on the extension point and `ReplaceWithRegisterNew` on the thin API - and only the second
     * can run here, so every alias the old one publishes resolves to the new one.
     */
    val ALIASES: Map<String, String> = mapOf(
      "vim-abolish" to "abolish",
      "argtextobj.vim" to "argtextobj",
      "script.php?script_id=2699" to "argtextobj",
      "CamelCaseMotion" to "CamelCaseMotion",
      "vim-textobj-class" to "classtextobj",
      "script.php?script_id=1173" to "commentary",
      "script.php?script_id=3695" to "commentary",
      "tcomment_vim" to "commentary",
      "vim-commentary" to "commentary",
      "vim-exchange" to "exchange",
      "vim-textobj-function" to "functextobj",
      "vim-highlightedyank" to "highlightedyank",
      "vim-indentwise" to "indentwise",
      "matchit" to "matchit",
      "vim-matchit" to "matchit",
      "mini.ai" to "mini-ai",
      "vim-multiple-cursors" to "multiple-cursors",
      "nerdtree" to "NERDTree",
      "ReplaceWithRegister" to "ReplaceWithRegisterNew",
      "script.php?script_id=2703" to "ReplaceWithRegisterNew",
      "vim-ReplaceWithRegister" to "ReplaceWithRegisterNew",
      "vim-sneak" to "sneak",
      "script.php?script_id=1697" to "surround",
      "vim-surround" to "surround",
      "targets.vim" to "targets",
      "script.php?script_id=2610" to "textobj-entire",
      "vim-textobj-entire" to "textobj-entire",
      "vim-indent-object" to "textobj-indent",
      "vim-textobj-user" to "textobj-user",
      "Improved-paragraph-motion" to "vim-paragraph-motion",
      "vim-paragraph-motion" to "vim-paragraph-motion",
      "VimEverywhere" to "VimEverywhere",
      "script.php?script_id=1234" to "yankring",
      "YankRing" to "yankring",
      "YankRing.vim" to "yankring",
    )
  }
}
