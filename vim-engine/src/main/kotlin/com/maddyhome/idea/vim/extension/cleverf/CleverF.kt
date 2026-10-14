/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.cleverf

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.key.MappingOwner

/**
 * Port of clever-f.vim (https://github.com/rhysd/clever-f.vim).
 *
 * Extends `f`, `F`, `t` and `T` so that pressing the same key again repeats the last search, making `;` and `,`
 * unnecessary. A search is repeated only if the cursor is still where the previous search left it; any other cursor
 * movement, text change or entering Insert mode starts a new search. Unlike the builtin motions, searches cross line
 * boundaries by default. The characters the search can jump to on the cursor line are highlighted.
 *
 * ## What moving it cost, against what the triage said it would
 *
 * This file's own estimate, recorded in CLAUDE.md for two passes, was that `clever-f` was blocked behind an engine
 * change: the command builder would have to decide at runtime whether a motion needs its character argument.
 * Both halves of that were wrong, and both were settled by reading rather than by trying.
 *
 *  - **Nothing is decided at runtime, upstream either.** `CleverFMotionAction` leaves `argumentType` at its `null`
 *    default and closes the character over inside its `CharSearch`; its `motionType` is a `val`. See that file.
 *  - **The deferred read still reaches the operator**, and needs nothing but `addAction`. A `readKeys` callback
 *    runs inside the keystroke that delivered the character, and that keystroke's own
 *    `finishedCommandPreparation` runs a command builder that has just become ready. See [FindHandler] for the two
 *    mechanisms that looked load-bearing and are not.
 *
 * What it did cost was one new seam and one that turned out to exist. `SearchFinalizer` wants Vim's `CursorMoved`
 * and `TextChanged`; the first is `VimCaretMovedListener`, written here, and the second is `ChangesListener`, which
 * has been in the engine all along one level below the notifier. The rest was mechanical: `WeakReference<Editor>`
 * became a path, `System.currentTimeMillis` became the engine's monotonic `nanoTime`, and IntelliJ's
 * `RangeHighlighter`s became `injector.highlightingService` - the `easymotion` precedent, down to the red that had
 * to stop being red.
 *
 * ## It will not take `f` from `sneak`
 *
 * `sneak` remaps `f`, `F`, `t` and `T` too, to keep track of the direction `;` should repeat in. Both extensions use
 * `putKeyMappingIfMissing`, so whichever is enabled first keeps the keys - which is IdeaVim's behaviour as well.
 * Anyone who wants both can map the `<Plug>` names by hand.
 */
@VimPlugin(name = CLEVER_F)
public fun VimInitApi.init(): Unit = registerCleverF()

/** Public because the plugin's extension-point adapter names it too. */
public const val CLEVER_F: String = "clever-f"

private val session = CleverFSession()
private val finalizer = SearchFinalizer(session)

/** `set noclever-f`, and the host's teardown: no listener and no highlight outlives the extension. */
public fun disposeCleverF() {
  finalizer.detach()
  session.reset()
}

public fun registerCleverF() {
  val owner = MappingOwner.Plugin.get(CLEVER_F)
  val executor = SearchExecutor(session)
  // Turning the extension on starts from nothing, which upstream gets for free by constructing a `CleverFSession`
  // per `CleverFExtension` - here the session is a file-level `val` and outlives any one of them. Two of IdeaVim's
  // own fixtures said so: `f<CR>` with nothing to repeat found the previous fixture's character, and `falfb`
  // repeated a search that should not have existed, which left the `a` to be read as an append and typed `lfb` into
  // the buffer. Same shape as `VimRedoBuffer` and `VimSearchGroupBase`'s companion - see CLAUDE.md.
  finalizer.detach()
  session.reset()
  finalizer.attach()

  fun plug(plug: String, handler: ExtensionHandler) {
    putExtensionHandlerMapping(MappingMode.NXO, injector.parser.parseKeys(plug), owner, handler, false)
  }

  for (map in FIND_KEYS) {
    val plugName = "<Plug>(clever-f-$map)"
    plug(plugName, FindHandler(map, session, executor))
    if (!CleverFSettings.notOverwritesStandardMappings) {
      putKeyMappingIfMissing(
        MappingMode.NXO,
        injector.parser.parseKeys(map.toString()),
        owner,
        injector.parser.parseKeys(plugName),
        true,
      )
    }
  }
  plug("<Plug>(clever-f-repeat-forward)", RepeatHandler(back = false, session, executor))
  plug("<Plug>(clever-f-repeat-back)", RepeatHandler(back = true, session, executor))
  plug("<Plug>(clever-f-reset)", ResetHandler(session))
}

private val FIND_KEYS = listOf('f', 'F', 't', 'T')
