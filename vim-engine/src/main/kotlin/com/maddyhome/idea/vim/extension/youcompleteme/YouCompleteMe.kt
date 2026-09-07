/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.youcompleteme

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin

/** Public because the plugin's extension point names it too. */
public const val YOU_COMPLETE_ME: String = "youcompleteme"

/**
 * `<Tab>` cycles the completion popup instead of accepting from it. SuperTab / YouCompleteMe style.
 *
 * The whole extension is one sentence: while the completion list is showing, `<Tab>` moves to the
 * next entry and `<S-Tab>` to the previous one; with nothing showing, `<Tab>` indents as usual.
 *
 * ## This registers nothing, and here that is the whole feature rather than a fraction of it
 *
 * A completion popup is not a text editor, so on VS Code the keys pressed while one is open never
 * reach an extension - the same wall `NERDTree`'s in-tree keys hit. Worse than that, VS Code will
 * not even *tell* an extension the popup is open: `suggestWidgetVisible` is a context key, and
 * context keys are write-only for extensions, which is why `VsCodeInjector.lookupManager` answers
 * `null` and says so. So the engine cannot do this work, and a mapping installed here would be one
 * the engine is never handed a key for.
 *
 * What VS Code *does* do is evaluate `suggestWidgetVisible` in a `when` clause. So the two keys are
 * declared in the host's `package.json`, gated on that and on `vimperor.youcompleteme`, and they
 * run `selectNextSuggestion` and `selectPrevSuggestion`. This exists to be the switch that turns
 * that gate on - registering the extension is what creates the `youcompleteme` option.
 *
 * `VimEverywhere` had the same shape and was taken out again, so the difference is worth stating.
 * There, the manifest could carry two of four features and not the one the extension is named for.
 * Here it carries **the only feature there is**, exactly: cycling forwards, cycling backwards, and
 * an ordinary `<Tab>` when nothing is showing - which needs no binding at all, because the clause
 * simply does not match and the key goes to the engine as it always did.
 *
 * ## What the IntelliJ half does that this does not need to
 *
 * `YouCompleteMeExtension` has a body because IntelliJ's popup *can* be read: it asks
 * `injector.lookupManager` and drives the lookup itself. Before it can, it has to take `<Tab>` out
 * of `'lookupkeys'`, an IntelliJ-only option listing the keys the IDE handles while a lookup is
 * open - otherwise the key never reaches IdeaVim either. That option is the reason the body cannot
 * move here, and the reason it does not need to: this host's equivalent of `'lookupkeys'` is the
 * `when` clause, and the clause is in the manifest.
 */
@VimPlugin(name = YOU_COMPLETE_ME)
public fun VimInitApi.init() {
  // Deliberately empty. See above: the two keys are in the host's manifest, and this is the switch.
}
