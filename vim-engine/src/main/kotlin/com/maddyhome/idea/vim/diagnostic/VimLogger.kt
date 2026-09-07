/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.diagnostic

import com.maddyhome.idea.vim.api.injector

interface VimLogger {
  fun isTrace(): Boolean
  fun trace(data: String)

  fun isDebug(): Boolean
  fun debug(data: String)

  fun warn(message: String)
  fun warn(message: String, e: Throwable)
  fun error(message: String)
  fun error(message: String, e: Throwable)
  fun info(message: String)
}

inline fun VimLogger.trace(message: () -> String) {
  if (isTrace()) {
    trace(message())
  }
}

inline fun VimLogger.debug(message: () -> String) {
  if (isDebug()) {
    debug(message())
  }
}

/**
 * A logger for [T], resolved from the injector the *first time it is written to* rather than now.
 *
 * The deferral is the whole point. `injector` is a `lateinit` global that a host assigns while it
 * starts up, and the overwhelmingly common shape of a call site is
 *
 * ```
 * companion object { private val logger = vimLogger<Something>() }
 * ```
 *
 * On the JVM that is safe by accident: a companion initialises on first use, which is long after a
 * host has installed its injector. Kotlin/JS gives no such guarantee - a top-level `val` runs at
 * module load, and anything it touches initialises with it - so whether this reads an injector that
 * exists depends on the order the compiler happens to emit files in.
 *
 * That order is not something anyone controls. It changed when the source directories were renamed
 * from KMP's `src/commonMain` to Maven's `src/main`, with not one line of engine code touched, and
 * `EditorActionHandlerBase`'s companion went from initialising after a test installed its injector
 * to initialising at module load. What that produced was not an error naming the cause: the failed
 * initialiser left the companion half-built, and seven unrelated tests died on `logger` being
 * `undefined`, thousands of lines from anything to do with logging.
 *
 * So the deferral belongs here rather than at the call sites. Writing `by lazy` at each of them
 * fixes whichever one is being debugged and leaves the other forty-eight armed.
 */
inline fun <reified T : Any> vimLogger(): VimLogger = DeferredVimLogger { injector.getLogger(T::class) }

/**
 * A [VimLogger] that asks for the real one on first use. See [vimLogger].
 *
 * Public because [vimLogger] is inline, and not intended to be named directly.
 */
class DeferredVimLogger(resolve: () -> VimLogger) : VimLogger {
  private val delegate: VimLogger by lazy(resolve)

  override fun isTrace(): Boolean = delegate.isTrace()
  override fun trace(data: String) = delegate.trace(data)
  override fun isDebug(): Boolean = delegate.isDebug()
  override fun debug(data: String) = delegate.debug(data)
  override fun warn(message: String) = delegate.warn(message)
  override fun warn(message: String, e: Throwable) = delegate.warn(message, e)
  override fun error(message: String) = delegate.error(message)
  override fun error(message: String, e: Throwable) = delegate.error(message, e)
  override fun info(message: String) = delegate.info(message)
}
