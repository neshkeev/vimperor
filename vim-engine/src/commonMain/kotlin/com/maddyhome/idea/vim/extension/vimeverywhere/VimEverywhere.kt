/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.vimeverywhere

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin

/** Public because the plugin's extension-point adapter names it too. */
public const val VIM_EVERYWHERE: String = "VimEverywhere"

/**
 * Vim keys outside the editor: in lists and trees, and for moving between panes.
 *
 * ## This extension registers nothing, and that is what it is
 *
 * Every other extension here installs mappings, text objects or ex commands, and this one installs
 * none, because on this host it *cannot*. **A key pressed outside a text editor never reaches an
 * extension in VS Code.** `type` is the editor's own command and the sidebar, the panel, the
 * Problems list and the Source Control tree are not editors, so there is no keystroke for the
 * engine to be handed and nothing for a mapping to intercept.
 *
 * What VS Code offers instead is a keybinding in `package.json` with a `when` clause: declarative,
 * fixed at install time, and evaluated by VS Code rather than by anything here. So the keys live in
 * the manifest, and this exists to be the switch that turns them on - registering the extension is
 * what creates the `VimEverywhere` option, and the host reads that back as the context key
 * `vimperor.vimeverywhere` which every one of those bindings is gated on.
 *
 * A switch is a thin thing to call an extension, and saying so plainly is better than inventing
 * engine work to make it look thicker. `NERDTree` is the same shape with a body: six ex commands
 * that *are* the engine's, plus manifest keys that are not.
 *
 * ## What it does on IntelliJ, and why none of it moved
 *
 * The plugin's `VimHintsExtension` is four things, and this is the rare case where nothing at all
 * was portable - unlike NERDTree, where half was portable and the "needs a tool window" story had
 * been hiding it.
 *
 *  - `NerdTreeEverywhere` and `TableEverywhere` extend `h` `j` `k` `l` to any Swing `Tree` or
 *    `JTable` by listening to `KeyboardFocusManager` and installing a shortcut set on whatever has
 *    focus. VS Code has one generic list widget and a `listFocus` context key, and the manifest
 *    covers it; the mechanism has nothing in common.
 *  - `ToolWindowNavEverywhere` extends `<C-W>h` and friends to a component inside a tool window.
 *    Same again: the manifest has it, as a chord.
 *  - `ToggleHintsAction` is the one piece with **no VS Code equivalent of any kind**. It paints
 *    labelled hints over every clickable component in the IDE window and clicks the one you type,
 *    using an IntelliJ glass pane to draw on. An extension cannot draw over VS Code's workbench -
 *    there is no overlay API and no seam that would provide one - so this is not a gap waiting to
 *    be filled, it is a thing this host does not do.
 *
 * `h` and `l` for a *column*, which `TableEverywhere` gives a `JTable`, have no equivalent either:
 * VS Code's lists have rows and nesting, not columns, so `h` and `l` collapse and expand instead.
 */
@VimPlugin(name = VIM_EVERYWHERE)
public fun VimInitApi.init() {
  // Deliberately empty. See above: the keys are in the host's manifest, and this is the switch.
}
