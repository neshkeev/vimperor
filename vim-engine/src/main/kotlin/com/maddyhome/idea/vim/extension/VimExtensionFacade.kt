/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.extension

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.ImmutableVimCaret
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.hasMapTo
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.common.CommandAlias
import com.maddyhome.idea.vim.common.CommandAliasHandler
import com.maddyhome.idea.vim.helper.enumSetOf
import com.maddyhome.idea.vim.key.KeySource
import com.maddyhome.idea.vim.key.MappingOwner
import com.maddyhome.idea.vim.key.OperatorFunction
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.vimscript.model.expressions.Expression
import com.maddyhome.idea.vim.vimscript.model.expressions.Scope
import com.maddyhome.idea.vim.vimscript.model.statements.FunctionFlag
import kotlin.jvm.JvmStatic

/**
 * Vim API facade that defines functions similar to the built-in functions and statements of the
 * original Vim.
 *
 * See `:help eval`.
 *
 * ## Why this is in the engine
 *
 * It was in the IntelliJ plugin, and it was the single largest thing standing between the bundled
 * extensions and the VS Code host. An extension that registers a mapping calls
 * `putExtensionHandlerMapping` and nothing else IntelliJ-shaped, so a facade in the plugin meant
 * a *portable* extension could not move - which is what stopped `commentary`, `textobjuser` and
 * `camelcasemotion`.
 *
 * Nothing here needed rewriting to move, only re-pointing: the bodies called `VimPlugin.getKey()`,
 * `VimPlugin.getRegister()` and `VimPlugin.getCommand()`, which are the plugin's accessors for
 * services the engine already exposes as `injector.keyGroup`, `injector.registerGroup` and
 * `injector.commandGroup`. Same objects, reached the host-independent way.
 *
 * ## What stayed behind
 *
 * The functions that take an IntelliJ `Editor` or `DataContext` are extension functions on this
 * object in the plugin, so `VimExtensionFacade.inputString(editor, context, ...)` still resolves at
 * every call site that passes IntelliJ types. `inputKeyStroke` is one of those, and it is the only
 * one with a real reason to stay: its unit-test branch reads IntelliJ's `TestInputModel`.
 *
 * @author vlan
 */
object VimExtensionFacade {

  /** The 'map' command for mapping keys to handlers defined in extensions. */
  @JvmStatic
  fun putExtensionHandlerMapping(
    modes: Set<MappingMode>,
    fromKeys: List<VimKeyStroke>,
    pluginOwner: MappingOwner,
    extensionHandler: ExtensionHandler,
    recursive: Boolean,
  ) {
    injector.keyGroup.putKeyMapping(modes, fromKeys, pluginOwner, extensionHandler, recursive)
  }

  /** The 'map' command for mapping keys to other keys. */
  @JvmStatic
  fun putKeyMapping(
    modes: Set<MappingMode>,
    fromKeys: List<VimKeyStroke>,
    pluginOwner: MappingOwner,
    toKeys: List<VimKeyStroke>,
    recursive: Boolean,
  ) {
    injector.keyGroup.putKeyMapping(modes, fromKeys, pluginOwner, toKeys, recursive)
  }

  /** The 'map' command for mapping keys to other keys if there is no other mapping to these keys */
  @JvmStatic
  fun putKeyMappingIfMissing(
    modes: Set<MappingMode>,
    fromKeys: List<VimKeyStroke>,
    pluginOwner: MappingOwner,
    toKeys: List<VimKeyStroke>,
    recursive: Boolean,
  ) {
    val filteredModes = modes.filterTo(mutableSetOf()) {
      !injector.keyGroup.hasMapTo(toKeys, enumSetOf(it))
    }
    injector.keyGroup.putKeyMapping(filteredModes, fromKeys, pluginOwner, toKeys, recursive)
  }

  /** Equivalent to calling 'command' to set up a user-defined command or alias */
  fun addCommand(
    name: String,
    handler: CommandAliasHandler,
  ) {
    addCommand(name, 0, 0, handler)
  }

  /** Equivalent to calling 'command' to set up a user-defined command or alias */
  @JvmStatic
  fun addCommand(
    name: String,
    minimumNumberOfArguments: Int,
    maximumNumberOfArguments: Int,
    handler: CommandAliasHandler,
  ) {
    injector.commandGroup
      .setAlias(name, CommandAlias.Call(minimumNumberOfArguments, maximumNumberOfArguments, name, handler))
  }

  /**
   * Runs normal mode commands similar to ':normal! {commands}'.
   * Mappings doesn't work with this function
   *
   * XXX: Currently it doesn't make the editor enter the normal mode, it doesn't recover from
   * incomplete commands, it leaves the editor in the insert mode if it's been activated.
   */
  @JvmStatic
  fun executeNormalWithoutMapping(keys: List<VimKeyStroke>, editor: VimEditor) {
    val context = injector.executionContextManager.getEditorExecutionContext(editor)
    val keyHandler = KeyHandler.getInstance()
    keys.forEach {
      keyHandler.handleKey(editor, it, KeySource.NORMAL_COMMAND_NOT_MAPPED, context, keyHandler.keyHandlerState)
    }
  }

  /** Returns a string typed in the input box similar to 'input()'. */
  @JvmStatic
  fun inputString(editor: VimEditor, context: ExecutionContext, prompt: String, finishOn: Char?): String {
    @Suppress("DEPRECATION")
    return injector.commandLine.inputString(editor, context, prompt, finishOn) ?: ""
  }

  /** Returns the current contents of the given register similar to 'getreg()'. */
  @JvmStatic
  fun getRegister(editor: VimEditor, register: Char): List<VimKeyStroke>? {
    val reg = injector.registerGroup
      .getRegister(editor, injector.executionContextManager.getEditorExecutionContext(editor), register) ?: return null
    return reg.keys
  }

  @JvmStatic
  fun getRegisterForCaret(
    editor: VimEditor,
    context: ExecutionContext,
    register: Char,
    caret: VimCaret,
  ): List<VimKeyStroke>? {
    val reg = caret.registerStorage.getRegister(editor, context, register) ?: return null
    return reg.keys
  }

  /** Set the current contents of the given register */
  @JvmStatic
  fun setRegister(register: Char, keys: List<VimKeyStroke?>?) {
    injector.registerGroup.setKeys(register, keys?.filterNotNull() ?: emptyList())
  }

  /** Set the current contents of the given register */
  @JvmStatic
  fun setRegisterForCaret(
    editor: VimEditor,
    context: ExecutionContext,
    register: Char,
    caret: ImmutableVimCaret,
    keys: List<VimKeyStroke?>?,
  ) {
    caret.registerStorage.setKeys(editor, context, register, keys?.filterNotNull() ?: emptyList())
  }

  /** Set the current contents of the given register */
  @JvmStatic
  fun setRegister(register: Char, keys: List<VimKeyStroke?>?, type: SelectionType) {
    injector.registerGroup.setKeys(register, keys?.filterNotNull() ?: emptyList(), type)
  }

  /**
   * Declares a Vimscript function with a Kotlin body.
   *
   * The implementation is `ScriptFunctions.export` in `vim-engine`. It never had any IntelliJ in
   * it, and the thin API's `VimPluginService` needs it from a host that has no IntelliJ at all.
   */
  @JvmStatic
  fun exportScriptFunction(
    scope: Scope?,
    name: String,
    args: List<String>,
    defaultArgs: List<Pair<String, Expression>>,
    hasOptionalArguments: Boolean,
    flags: MutableSet<FunctionFlag>,
    function: ScriptFunction,
  ) {
    ScriptFunctions.export(scope, name, args, defaultArgs, hasOptionalArguments, flags, function)
  }
}

fun VimExtensionFacade.exportOperatorFunction(name: String, function: OperatorFunction) {
  ScriptFunctions.exportOperatorFunction(name, function)
}
