/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package org.jetbrains.plugins.ideavim.ex

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.host.HeadlessExecutionContext
import com.maddyhome.idea.vim.host.HeadlessInjector
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.expressions.CurlyBracesName
import com.maddyhome.idea.vim.vimscript.model.expressions.Expression

/**
 * Evaluates an expression with no host in the way, which is what these tests always meant.
 *
 * The version that came with them built a `TextComponentEditorImpl` over a Swing `JTextArea` and
 * evaluated against that - the smallest editor IntelliJ has, chosen because *some* editor was
 * needed. It was the only thing tying a parser test to the plugin, and the engine has had its own
 * answer for a while: `HeadlessInjector`, which is what the rest of `commonTest` runs on.
 *
 * That is also what lets these run on JS. They have only ever run on the JVM.
 */
fun Expression.evaluate(vimContext: VimLContext = CommandLineVimLContext): VimDataType =
  evaluate(headlessEditor(), HeadlessExecutionContext, vimContext)

fun CurlyBracesName.evaluate(vimContext: VimLContext = CommandLineVimLContext): VimString =
  evaluate(headlessEditor(), HeadlessExecutionContext, vimContext)

/**
 * An injector and an empty buffer. Expressions with no editor in them still take one and never look
 * at it; the builtins have to be registered by hand, because reading the providers is a host's job.
 */
private fun headlessEditor(): TestVimEditor {
  injector = HeadlessInjector()
  injector.functionService.registerHandlers()
  return TestVimEditor("", listOf(TestVimCaret(0)))
}
