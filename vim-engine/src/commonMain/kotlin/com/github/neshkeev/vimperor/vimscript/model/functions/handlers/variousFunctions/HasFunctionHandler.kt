/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vimscript.model.functions.handlers.variousFunctions
import com.intellij.vim.annotations.VimscriptFunction
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.asVimInt
import com.maddyhome.idea.vim.vimscript.model.functions.BuiltinFunctionHandler

/**
 * `has({feature})` - the function every portable `~/.vimrc` is built out of.
 *
 * It was the IntelliJ plugin's alone until now, which meant that in the VS Code host `has('unix')`
 * was `E117: Unknown function`, and a config whose first ten lines are `if has(...)` blocks did not
 * merely take the wrong branch - it stopped.
 *
 * What is claimed here is only what is true, and the list is worth reading as an inventory rather
 * than as a lookup table. `has('signs')` became 1 the week `:sign` was written; `has('quickfix')`
 * because `:copen` and `:cnext` are real; `has('folding')` because folds are the editor's and the
 * commands reach them. Nothing is claimed because a config would prefer it: there is no
 * `has('python')` here, no `has('terminal')`, no `has('timers')` while `timer_start()` does not
 * exist, and no `has('patch-9.1.0')`, because this is not Vim and a patch number is a claim about
 * Vim's source.
 *
 * `gui_running` is the interesting no. Both hosts are unmistakably graphical, so the honest-looking
 * answer is 1 - and it would be the wrong one. A config guards `set guifont=...` and
 * `set guioptions-=T` behind it, and neither option exists here, so claiming the feature turns one
 * skipped block into two `E518`s. The feature Vim means by it is "this is the GUI build, with the
 * GUI's options", and that half is what is missing.
 *
 * `spell` is host business for the same kind of reason and comes from the host: IntelliJ has a
 * spellchecker and VS Code has none at all, and this fork already says so where `z=` reaches it.
 *
 * The operating system, and anything one host has and the other does not, come from
 * [com.maddyhome.idea.vim.api.VimInjector.hostFeatures]. The engine has no way to ask what platform
 * it is on and no business guessing.
 *
 * Vim's second argument asks whether a feature *could* be supported rather than whether it is.
 * Answered the same way as the first, because the honest answer needs Vim's own list of every
 * feature name that has ever existed, and inventing a shorter one would make `has('x', 1)` mean
 * "this fork has heard of x" rather than what Vim means.
 *
 * see "h has()"
 */
@VimscriptFunction(name = "has")
internal class HasFunctionHandler : BuiltinFunctionHandler<VimInt>(minArity = 1, maxArity = 2) {

  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimInt {
    val feature = arguments.getString(0).value
    if (feature == "ide") injector.statisticsService.setIdeSpecificConfigurationUsed(true)

    // `vim_starting` is the one feature that is not a property of the build: it is true only while
    // the configuration file is being read, which is what makes it useful for telling a first load
    // from a `:source` of the same file.
    if (feature == "vim_starting") {
      return injector.vimscriptExecutor.executingIdeaVimRcConfiguration.asVimInt()
    }

    return (feature in ENGINE_FEATURES || feature in injector.hostFeatures).asVimInt()
  }

  private companion object {
    /**
     * What the engine provides, which is the same in both hosts by construction.
     *
     * Every name here is one this fork can point at a command, an option or a mode for. The
     * absences are as deliberate as the entries - see the class documentation.
     */
    val ENGINE_FEATURES: Set<String> = setOf(
      // The language itself, and the things a config writes in it.
      "eval",
      "user_commands",
      "cmdline_hist",
      "wildmenu",
      "autocmd",
      "digraphs",
      // Editing.
      "visual",
      "visualextra",
      "textobjects",
      "virtualedit",
      "folding",
      "syntax",
      "multi_byte",
      "multi_byte_encoding",
      "clipboard",
      // Places and lists.
      "jumplist",
      "quickfix",
      "signs",
      "mksession",
      // Windows.
      "windows",
      "vertsplit",
      // This fork's own marker, and IdeaVim's before it.
      "ide",
    )
  }
}
