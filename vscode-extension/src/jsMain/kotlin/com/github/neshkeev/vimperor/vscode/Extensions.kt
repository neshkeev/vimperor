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
import com.maddyhome.idea.vim.extension.replacewithregister.init as replaceWithRegisterInit
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.ListenerOwner
import com.maddyhome.idea.vim.extension.ExtensionBean
import com.maddyhome.idea.vim.extension.ExtensionLoader
import com.maddyhome.idea.vim.extension.JsonExtensionProvider
import com.maddyhome.idea.vim.key.MappingOwner
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
  )

  /** The id a bundled extension belongs to, which for this host is the extension itself. */
  const val PLUGIN_ID: String = "com.github.neshkeev.vimperor"

  fun beanFor(name: String): ExtensionBean =
    ExtensionBean(extensionName = name, pluginId = PLUGIN_ID, functionName = "init", className = "")
}

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
 * Disabling has to undo what `init` did, and an extension does two things that outlive it -
 * mappings and listeners - both of which the engine tracks by owner. So both owners are derived
 * from the extension's name, exactly as they are when it is enabled, and removing them is the whole
 * of the teardown. An extension that registers something the engine does not track by owner would
 * leak, which is a fact about the engine's ownership model rather than about this host.
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
  }

  override fun disableExtension(name: String) {
    if (enabled.remove(name) == null) return
    injector.keyGroup.removeKeyMapping(MappingOwner.Plugin.get(name))
    injector.listenersNotifier.unloadListeners(ListenerOwner.Plugin.get(name))
  }
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

  override fun setOptionByPluginAlias(alias: String): Boolean {
    val name = getExtensionNameByAlias(alias) ?: return false
    val bean = injector.jsonExtensionProvider.getExtension(name) ?: return false
    injector.extensionLoader.enableExtension(bean)
    return true
  }

  override fun getExtensionNameByAlias(alias: String): String? =
    ALIASES[alias.trim().removeSurrounding("'").removeSurrounding("\"").substringAfterLast('/')]

  private companion object {
    /** By the repository tail, which is what `:Plug` and `Plugin` lines actually carry. */
    val ALIASES: Map<String, String> = mapOf(
      "vim-easymotion" to "easymotion",
      "vim-surround" to "surround",
      "vim-commentary" to "commentary",
      "vim-multiple-cursors" to "multiple-cursors",
      "argtextobj.vim" to "argtextobj",
      "vim-textobj-entire" to "textobj-entire",
      // IdeaVim has two of these: `ReplaceWithRegister` on the old extension point and
      // `ReplaceWithRegisterNew` on the thin API. Only the second can run here, so the name a
      // `.vimrc` writes resolves to it.
      "ReplaceWithRegister" to "ReplaceWithRegisterNew",
      "vim-exchange" to "exchange",
      "vim-highlightedyank" to "highlightedyank",
      "vim-paragraph-motion" to "vim-paragraph-motion",
      "vim-indent-object" to "textobj-indent",
      "matchit.zip" to "matchit",
      "vim-matchit" to "matchit",
      "vim-sneak" to "sneak",
      "camelcasemotion" to "camelcasemotion",
      "vim-abolish" to "vim-abolish",
      "nerdtree" to "NERDTree",
      "vim-textobj-function" to "functextobj",
      "vim-indentwise" to "indentwise",
      "mini.ai" to "miniai",
      "vim-targets" to "targets",
      "targets.vim" to "targets",
      "YouCompleteMe" to "youcompleteme",
    )
  }
}
