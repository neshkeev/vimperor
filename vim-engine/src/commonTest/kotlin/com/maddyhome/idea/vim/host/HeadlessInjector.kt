/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.VimApplication
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimStringParser
import com.maddyhome.idea.vim.api.VimStringParserBase
import com.maddyhome.idea.vim.api.VimscriptParser
import com.maddyhome.idea.vim.api.VimscriptParserBase
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.diagnostic.VimLogger
import kotlin.reflect.KClass

/**
 * The headless host, as far as it goes.
 *
 * Grown one service at a time, each because something asked for it. See [HeadlessInjectorBase] for
 * the ones that still throw.
 */
class HeadlessInjector : HeadlessInjectorBase() {

  // Every service is `by lazy`, and that is a requirement rather than a style choice. Engine
  // services read the global `injector` while constructing - `VimscriptParserBase` builds a logger
  // in its initialiser - so a host that builds its services eagerly touches `injector` before the
  // assignment that installs the host has completed. IntelliJ never hits this because its services
  // are created on first use by the platform.

  /**
   * Whole-cloth from `VimStringParserBase`, which leaves nothing abstract: turning `"<C-A>"` into
   * keystrokes is string work with no editor in it. This is what `getCommands()` needs to build the
   * command list, so supplying it is what lets the generated registry be exercised rather than
   * merely compiled.
   */
  override val parser: VimStringParser by lazy { object : VimStringParserBase() {} }

  /** Also whole-cloth: `VimscriptParserBase` leaves nothing abstract either. */
  override val vimscriptParser: VimscriptParser by lazy { object : VimscriptParserBase() {} }

  override val application: VimApplication by lazy { HeadlessApplication }

  /** Silent. A test that needs to read the log can swap this out; nothing does yet. */
  override fun <T : Any> getLogger(clazz: KClass<T>): VimLogger = SilentLogger
}

private object SilentLogger : VimLogger {
  override fun isTrace(): Boolean = false
  override fun isDebug(): Boolean = false
  override fun info(message: String) {}
  override fun warn(message: String) {}
  override fun error(message: String) {}
  override fun debug(message: String) {}
  override fun trace(data: String) {}
  override fun warn(message: String, e: Throwable) {}
  override fun error(message: String, e: Throwable) {}
}

/**
 * There is one thread and no event loop, so every "do this later" is "do this now".
 *
 * Not a stub standing in for something real: a headless host genuinely has nothing to marshal
 * between, and running the action immediately is the honest answer rather than a convenient one.
 */
private object HeadlessApplication : VimApplication {
  override fun isMainThread(): Boolean = true
  override fun invokeLater(editor: VimEditor, action: () -> Unit) = action()
  override fun invokeLater(action: () -> Unit) = action()
  override fun invokeAndWait(action: () -> Unit) = action()
  override fun isUnitTest(): Boolean = true
  override fun isInternal(): Boolean = false
  override fun <T> runWriteAction(action: () -> T): T = action()
  override fun <T> runReadAction(action: () -> T): T = action()
  override fun runAfterGotFocus(runnable: () -> Unit) = runnable()

  /** No stack traces without a host to attribute them to; only diagnostics read this. */
  override fun currentStackTrace(): String = ""

  override fun postKey(stroke: VimKeyStroke, editor: VimEditor) =
    TODO("headless host has no key queue yet")
}
