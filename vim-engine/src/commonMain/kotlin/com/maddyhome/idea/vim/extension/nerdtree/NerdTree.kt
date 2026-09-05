/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.nerdtree

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.CommandAliasHandler
import com.maddyhome.idea.vim.ex.ranges.Range
import com.maddyhome.idea.vim.extension.VimExtensionFacade

/** Public because the plugin's extension-point adapter names it too. */
public const val NERD_TREE: String = "NERDTree"

/**
 * NERDTree's ex commands: show the project's file tree, hide it, or find the current file in it.
 *
 * ## What this is and what it is not
 *
 * NERDTree in IdeaVim is two things sharing a name, and only one of them is portable.
 *
 * **The commands, which are here.** `:NERDTree`, `:NERDTreeFocus`, `:NERDTreeToggle`,
 * `:NERDTreeClose`, `:NERDTreeFind` and `:NERDTreeRefreshRoot` each say *do this to the file tree*
 * and nothing more. IdeaVim wrote them as IntelliJ action ids - `ActivateProjectToolWindow`,
 * `SelectInProjectView`, `Synchronize` - which is the only reason they could not travel; the
 * commands themselves are host-independent, and `injector.fileTree` is what they say instead.
 *
 * **The tree's own key mappings, which are not.** Real NERDTree maps some thirty keys that apply
 * while the cursor is *inside the tree*: `o` to open, `j` and `k` to move, `s` and `i` to open in a
 * split, `P` to jump to the root, `X` to close a node's children, and so on. IdeaVim implements
 * them by installing a shortcut set on the Project view's Swing `JTree`.
 *
 * There is no equivalent seam in VS Code, and this is not a gap waiting to be filled: a key pressed
 * in the sidebar never reaches an extension. VS Code's `type` command is the editor's, and the
 * Explorer is not an editor. The only mechanism it offers is `package.json` keybindings with a
 * `when` clause - declarative, fixed at install time, and unable to be turned on by `set NERDTree`
 * or remapped by `g:NERDTreeMapOpenSplit`. The VS Code host declares the subset that has honest
 * equivalents and says which NERDTree keys have none; see its `package.json`.
 *
 * So the plugin keeps its adapter and its dispatcher, this file holds what both hosts can run, and
 * the split is along the line the two mechanisms actually fall on rather than along the extension's
 * name.
 *
 * ## Two commands for one thing
 *
 * `:NERDTree` and `:NERDTreeFocus` do the same thing here, as they do in IdeaVim. In real NERDTree
 * they differ - `:NERDTree` opens a tree rooted at a directory you can name, `:NERDTreeFocus` moves
 * the cursor into the existing one - and neither host has a tree whose root Vim can set, so both
 * become "show it and focus it". That is IdeaVim's choice and this keeps it.
 */
@VimPlugin(name = NERD_TREE)
public fun VimInitApi.init(): Unit = registerNerdTree()

public fun registerNerdTree() {
  addCommand(NERD_TREE_COMMAND) { editor, context -> injector.fileTree.focus(editor, context) }
  addCommand(NERD_TREE_FOCUS) { editor, context -> injector.fileTree.focus(editor, context) }
  addCommand(NERD_TREE_TOGGLE) { editor, context -> injector.fileTree.toggle(editor, context) }
  addCommand(NERD_TREE_CLOSE) { editor, context -> injector.fileTree.close(editor, context) }
  addCommand(NERD_TREE_FIND) { editor, context -> injector.fileTree.revealCurrentFile(editor, context) }
  addCommand(NERD_TREE_REFRESH) { editor, context -> injector.fileTree.refresh(editor, context) }
}

/**
 * Called on `set noNERDTree`.
 *
 * A command alias is held by the command group under its name, and the loader's teardown removes
 * mappings and listeners by owner and knows nothing about names - the same reason `commentary` and
 * `textobj-user` need one of these. Without it `:NERDTreeToggle` keeps working after the user has
 * turned the extension off.
 */
public fun disposeNerdTree() {
  COMMANDS.forEach(injector.commandGroup::removeAlias)
}

/**
 * The same shape as `:command`, which is what these are: an ex command name and something to run.
 *
 * None of them takes a range or an argument, so the handler ignores both. Real NERDTree's
 * `:NERDTree` takes an optional directory to root the tree at, which neither host has a tree for -
 * see the note above.
 */
private fun addCommand(name: String, action: (VimEditor, ExecutionContext) -> Unit) {
  VimExtensionFacade.addCommand(
    name,
    object : CommandAliasHandler {
      override fun execute(command: String, range: Range, editor: VimEditor, context: ExecutionContext) {
        action(editor, context)
      }
    },
  )
}

internal const val NERD_TREE_COMMAND = "NERDTree"
internal const val NERD_TREE_FOCUS = "NERDTreeFocus"
internal const val NERD_TREE_TOGGLE = "NERDTreeToggle"
internal const val NERD_TREE_CLOSE = "NERDTreeClose"
internal const val NERD_TREE_FIND = "NERDTreeFind"
internal const val NERD_TREE_REFRESH = "NERDTreeRefreshRoot"

private val COMMANDS = listOf(
  NERD_TREE_COMMAND,
  NERD_TREE_FOCUS,
  NERD_TREE_TOGGLE,
  NERD_TREE_CLOSE,
  NERD_TREE_FIND,
  NERD_TREE_REFRESH,
)
