/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
@file:JvmName("IjVimExtensionFacade")

package com.maddyhome.idea.vim.extension

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.action.change.Extension
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.helper.TestInputModel
import com.maddyhome.idea.vim.helper.inRepeatMode
import com.maddyhome.idea.vim.key.MappingOwner
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.newapi.vim
import java.awt.event.KeyEvent

/**
 * What is left of `VimExtensionFacade` after it moved to `vim-engine`: the part that speaks
 * IntelliJ.
 *
 * The facade itself is in the engine now, because it was the single largest thing standing between
 * the bundled extensions and the VS Code host - an extension that only registers mappings calls
 * `putExtensionHandlerMapping` and nothing else IntelliJ-shaped, so a facade in the plugin meant a
 * portable extension could not move.
 *
 * These are extension functions rather than members, because Kotlin cannot add a member to an
 * object from another module. A call site reaches them by importing
 * `com.maddyhome.idea.vim.extension.executeNormalWithoutMapping` instead of
 * `...executeNormalWithoutMapping` - one import line, no change to the call.
 *
 * All of this goes when the plugin does.
 */
private val LOG = logger<VimExtension>()

/** The 'map' command, for the deprecated handler type that speaks IntelliJ. */
@Deprecated(
  "Use VimPlugin.getKey().putKeyMapping(modes, fromKeys, pluginOwner, extensionHandler, recursive)",
  ReplaceWith(
    "VimPlugin.getKey().putKeyMapping(modes, fromKeys, pluginOwner, extensionHandler, recursive)",
    "com.maddyhome.idea.vim.VimPlugin",
  ),
)
fun putExtensionHandlerMapping(
  modes: Set<MappingMode>,
  fromKeys: List<VimKeyStroke>,
  pluginOwner: MappingOwner,
  extensionHandler: VimExtensionHandler,
  recursive: Boolean,
) {
  VimPlugin.getKey().putKeyMapping(modes, fromKeys, pluginOwner, extensionHandler, recursive)
}

/** The engine's, taking IntelliJ's editor. */
fun executeNormalWithoutMapping(keys: List<VimKeyStroke>, editor: Editor) {
  VimExtensionFacade.executeNormalWithoutMapping(keys, editor.vim)
}

/** The engine's, taking IntelliJ's editor and data context. */
fun inputString(editor: Editor, context: DataContext, prompt: String, finishOn: Char?): String =
  VimExtensionFacade.inputString(editor.vim, context.vim, prompt, finishOn)

/**
 * Waits for a keystroke, which is the one thing here that genuinely cannot move.
 *
 * Not because of the modal input - `injector.modalInput` is an engine seam any host can supply -
 * but because of the branch above it: under test, IdeaVim feeds keys from IntelliJ's
 * `TestInputModel`, which is IntelliJ's test infrastructure rather than Vim's.
 *
 * This is also the function the standing story about the extensions rested on - "they are blocked
 * on `getchar()`". Of the twenty-six, one reaches it.
 */
fun inputKeyStroke(editor: Editor): VimKeyStroke {
  if (editor.vim.inRepeatMode) {
    val input = Extension.consumeKeystroke()
    LOG.trace("inputKeyStroke: dot repeat in progress. Input: $input")
    return input ?: error("Not enough keystrokes saved: ${Extension.lastExtensionHandler}")
  }

  val key: VimKeyStroke? = if (ApplicationManager.getApplication().isUnitTestMode) {
    LOG.trace("Unit test mode is active")
    val mappingStack = KeyHandler.getInstance().keyStack
    mappingStack.feedSomeStroke() ?: TestInputModel.getInstance(editor).nextKeyStroke()?.also {
      if (injector.registerGroup.isRecording) {
        KeyHandler.getInstance().modalEntryKeys += it
      }
    }
  } else {
    LOG.trace("Getting char from the modal entry...")
    var ref: VimKeyStroke? = null
    injector.modalInput.activate(editor.vim) { stroke: VimKeyStroke ->
      ref = stroke
      false
    }
    LOG.trace("Got char $ref")
    ref
  }
  val result = key ?: VimKeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE.toChar())
  Extension.addKeystroke(result)
  return result
}
