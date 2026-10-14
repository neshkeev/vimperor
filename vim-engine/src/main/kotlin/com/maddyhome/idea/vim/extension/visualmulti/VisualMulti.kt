/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.visualmulti

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getMappingInfo
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.common.EditorListener
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.key.MappingOwner
import com.maddyhome.idea.vim.key.VimKeyStroke

/**
 * Port of vim-visual-multi.
 *
 * See https://github.com/mg979/vim-visual-multi
 *
 * `<C-n>` starts a session on the word under the caret; inside one, `n` and `N` add the next and the
 * previous occurrence, `q` and `Q` skip and remove a region, `[` and `]` walk between them, `<Tab>`
 * switches between regions and plain cursors, `i`/`a`/`I`/`A` start Insert mode at every region, and
 * `<Esc>` exits - after which `\\gS` brings the regions back. `<C-Down>` and `<C-Up>` add a cursor a
 * line at a time, and a Visual selection becomes regions with `\\a` or cursors with `\\c`.
 *
 * ## Two kinds of mapping, which is the whole design
 *
 * The permanent mappings are live always and are what starts a session. The session mappings are
 * live only *inside* one, and outside it the keys have to keep their Vim meaning - `n` is still the
 * next search match. So they are not mapped to a `<Plug>` name and left at that: each is mapped to a
 * [SessionOnlyHandler] that picks between the real handler and whatever the key would otherwise have
 * done, which is [NativeKeysHandler] for a key with no mapping of its own, the permanent mapping
 * where the two share keys (`\\a` is both Visual Add and Align), and nothing at all for a key the
 * user has mapped - that mapping is left alone.
 *
 * ## What this port cost, and where it diverges
 *
 * **Nothing needed a new seam that `multiple-cursors` had not already needed** - `VimEditor` has had
 * `addCaret`, `removeCaret` and `removeSecondaryCarets` all along - with one exception that was found
 * by reading this extension and is fixed separately: `VimEditor.makePrimaryCaret`. "The current
 * region" is the primary caret, and `[`, `]`, Add Cursor Down and the occurrence search all move it.
 *
 * **Three handlers read input, and all three are written inside out.** IdeaVim's `inputKeyStroke` and
 * `inputString` block until the user types, which a runtime with one thread cannot do; `readCharacter`
 * and `injector.commandLine.readInputAndProcess` are the engine's non-blocking counterparts, which
 * `vim-surround` already uses. The cost is that Run Normal, Run Macro and the case-conversion menu
 * return before they know what was typed and do their work a keystroke or two later.
 *
 * **`g:VM_case_setting` does not touch the user's options.** vim-visual-multi *sets* `'ignorecase'`
 * and `'smartcase'` for the length of the session; [VmCaseSetting] resolves them the same way and
 * leaves the options alone, so a session that ends badly cannot leave them somewhere the user did not
 * put them.
 *
 * **A changed `g:VM_maps` is picked up when an editor takes the focus**, which is what IdeaVim's
 * Swing focus listener is for and what `NERDTree` does here too. The engine's own
 * `EditorListener.focusGained` is the same signal without the Swing.
 */
@VimPlugin(name = VISUAL_MULTI)
public fun VimInitApi.init(): Unit = registerVisualMulti()

/** Public because the plugin's extension-point adapter names it too. */
public const val VISUAL_MULTI: String = "visual-multi"

private val owner = MappingOwner.Plugin.get(VISUAL_MULTI)

/** `null` while the extension is off. */
private var registeredKeys: Map<String, MappingKeys>? = null

private val focusListener = object : EditorListener {
  override fun focusGained(editor: VimEditor) {
    reloadMappings()
  }
}

public fun registerVisualMulti() {
  registerMappings(readMappingKeys())
  injector.listenersNotifier.myEditorListeners.add(focusListener)
  injector.listenersNotifier.modeChangeListeners.add(VmSession.EndOnModeChange)
}

/** For `set novisual-multi`: the mappings go with the owner, and the rest is this extension's own state. */
public fun disposeVisualMulti() {
  injector.listenersNotifier.myEditorListeners.remove(focusListener)
  injector.listenersNotifier.modeChangeListeners.remove(VmSession.EndOnModeChange)
  VmSession.endAll()
  forgetLastRegions()
  registeredKeys = null
}

/**
 * Applies a changed `g:VM_maps` without a restart - after a config was reloaded, say. `NERDTree` does
 * the same thing for the same reason.
 */
internal fun reloadMappings() {
  if (registeredKeys == null) return

  val keys = readMappingKeys()
  if (keys == registeredKeys) return

  injector.keyGroup.removeKeyMapping(owner)
  registerMappings(keys)
}

private fun registerMappings(keys: Map<String, MappingKeys>) {
  registerPlugMappings()
  registerPermanentKeys(keys)
  registerSessionKeys(keys)
  registeredKeys = keys
}

private fun registerPlugMappings() {
  for (mapping in getPermanentMappings()) {
    putPlugMapping(mapping.modes, mapping.plug, StartSessionHandler(mapping.createHandler()))
  }
  for (mapping in getSessionMappings()) {
    putPlugMapping(mapping.modes, mapping.plug, mapping.createHandler())
  }
}

private fun registerPermanentKeys(keys: Map<String, MappingKeys>) {
  for (mapping in getPermanentMappings()) {
    val mappingKeys = keys[mapping.name] ?: continue
    if (mappingKeys.fromUser) {
      putMapping(mapping.modes, mappingKeys.keys, mapping.plug)
    } else {
      putDefaultMapping(mapping.modes, mappingKeys.keys, mapping.plug)
    }
  }
}

/**
 * The keys are mapped to the handler rather than to the `<Plug>` mapping, so that the handler is the
 * thing that knows what to do outside the session.
 *
 * vim-visual-multi does not override existing buffer mappings, and this does not override the user's.
 */
private fun registerSessionKeys(keys: Map<String, MappingKeys>) {
  for (mapping in getSessionMappings()) {
    val keysText = keys[mapping.name]?.keys ?: continue
    val fromKeys = injector.parser.parseKeys(keysText)
    for (mode in mapping.modes) {
      val outsideSession = outsideSessionHandler(keys, keysText, mode, fromKeys) ?: continue
      val handler = SessionOnlyHandler(mapping.createHandler(), outsideSession)
      putExtensionHandlerMapping(setOf(mode), fromKeys, owner, handler, false)
    }
  }
}

/**
 * Usually the keys keep their Vim meaning outside the session. Where a permanent mapping uses the
 * same keys - `\\a` is Visual Add as well as Align - it is the permanent mapping. Answers null for
 * keys the user has mapped, which are then left alone.
 */
private fun outsideSessionHandler(
  keys: Map<String, MappingKeys>,
  keysText: String,
  mode: MappingMode,
  fromKeys: List<VimKeyStroke>,
): ExtensionHandler? {
  val permanent = getPermanentMappings().firstOrNull { mode in it.modes && keys[it.name]?.keys == keysText }
  return when {
    permanent != null -> StartSessionHandler(permanent.createHandler())
    injector.keyGroup.getMappingInfo(fromKeys, mode) == null -> NativeKeysHandler(fromKeys)
    else -> null
  }
}

private fun putPlugMapping(modes: Set<MappingMode>, plug: String, handler: ExtensionHandler) {
  putExtensionHandlerMapping(modes, injector.parser.parseKeys(plug), owner, handler, false)
}

/** Maps [keys] to [plug] unless the user has mapped other keys to [plug] already. */
private fun putDefaultMapping(modes: Set<MappingMode>, keys: String, plug: String) {
  putKeyMappingIfMissing(modes, injector.parser.parseKeys(keys), owner, injector.parser.parseKeys(plug), true)
}

private fun putMapping(modes: Set<MappingMode>, keys: String, plug: String) {
  putKeyMapping(modes, injector.parser.parseKeys(keys), owner, injector.parser.parseKeys(plug), true)
}
