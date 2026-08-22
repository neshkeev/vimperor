/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.api.VimStringParser
import com.maddyhome.idea.vim.api.VimStringParserBase
import com.maddyhome.idea.vim.diagnostic.VimLogger
import kotlin.reflect.KClass

/**
 * The headless host, as far as it goes.
 *
 * Grown one service at a time, each because something asked for it. See [HeadlessInjectorBase] for
 * the ones that still throw.
 */
class HeadlessInjector : HeadlessInjectorBase() {

  /**
   * Whole-cloth from `VimStringParserBase`, which leaves nothing abstract: turning `"<C-A>"` into
   * keystrokes is string work with no editor in it. This is what `getCommands()` needs to build the
   * command list, so supplying it is what lets the generated registry be exercised rather than
   * merely compiled.
   */
  override val parser: VimStringParser = object : VimStringParserBase() {}

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
