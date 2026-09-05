/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.textobjuser

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.key.MappingOwner

/**
 * `vim-textobj-user`: the framework a user defines their *own* text objects with.
 *
 * It registers no keys of its own. It registers two Vimscript functions, and a `.vimrc` calls them:
 *
 * ```vim
 * call textobj#user#plugin('date', {
 * \   '-': {
 * \     'pattern': '\d\{4}-\d\{2}-\d\{2}',
 * \     'select': ['ad', 'id'],
 * \   },
 * \ })
 * ```
 *
 * after which `dad` deletes the date under the cursor. `textobj#user#map` adds further keys to an
 * object already defined that way. Everything else in this package is what those two functions
 * build: a `<Plug>(textobj-date--)` interface mapping bound to a handler that finds the pattern, and
 * the user's keys mapped onto that name so they stay remappable.
 *
 * ## What moving it cost
 *
 * One line. `TextObjUserHandler` moved the caret with
 * `(caret as IjVimCaret).caret.moveToInlayAwareOffset(...)`, and `moveToInlayAwareOffset` has been a
 * member of the engine's own `VimCaret` for as long as `IjVimCaret` has implemented it - so the cast
 * reached IntelliJ's `Caret` to call the same thing the engine already offered. Dropping it changes
 * no behaviour in either host, which is the point: the inlay-aware placement is the plugin's
 * override of a method the engine declares, and VS Code's caret gets its own.
 *
 * Nothing else in the six files was IntelliJ-shaped. `vimSetSelection` and
 * `SelectionVimListenerSuppressor` sound as though they would be and are both `commonMain`; the rest
 * is `VimRegex` and the Vimscript data types, which never left the engine.
 *
 * ## Why the plugin keeps a real adapter rather than a two-line one
 *
 * The other ported extensions register mappings, and the engine's loader tears those down by owner.
 * This one registers *function handlers*, which the engine does not track by owner, so disabling it
 * has to name them - which is [unregisterTextObjUserFunctions], and it is why the plugin's
 * `VimTextObjUserExtension` still has a `dispose` worth reading.
 */
@VimPlugin(name = TEXT_OBJ_USER)
public fun VimInitApi.init(): Unit = registerTextObjUserFunctions()

/**
 * Registers `textobj#user#plugin` and `textobj#user#map`.
 *
 * The owner is derived from the extension's name exactly as the loader derives it when enabling,
 * so the mappings those functions go on to create are torn down with the extension.
 */
public fun registerTextObjUserFunctions() {
  val owner = MappingOwner.Plugin.get(TEXT_OBJ_USER)
  injector.functionService.registerFunctionHandler(
    PLUGIN_FUNCTION_NAME,
    TextObjUserPluginFunctionHandler(PLUGIN_FUNCTION_NAME, owner),
  )
  injector.functionService.registerFunctionHandler(
    MAP_FUNCTION_NAME,
    TextObjUserMapFunctionHandler(MAP_FUNCTION_NAME, owner),
  )
}

/** Undoes [registerTextObjUserFunctions]. The mappings go with the owner; these do not. */
public fun unregisterTextObjUserFunctions() {
  injector.functionService.unregisterFunctionHandler(PLUGIN_FUNCTION_NAME)
  injector.functionService.unregisterFunctionHandler(MAP_FUNCTION_NAME)
}

/** Public because the plugin's extension-point adapter names them too. */
public const val TEXT_OBJ_USER: String = "textobj-user"
public const val PLUGIN_FUNCTION_NAME: String = "textobj#user#plugin"
public const val MAP_FUNCTION_NAME: String = "textobj#user#map"
