/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * A reference that does not keep what it points at alive.
 *
 * One user, and it is the reason this is not simply a strong reference: `:function` reports the
 * lambdas a script has made, and a lambda is wrapped in a `VimFuncref` and assigned to a variable -
 * so it stops existing when that variable is reassigned. Holding it strongly would list it for the
 * rest of the session; holding it weakly lists it until the runtime collects it, which is what Vim
 * does and what IdeaVim does, non-deterministically.
 *
 * `java.lang.ref.WeakReference` is JVM-only, so the engine cannot name it: `commonMain` compiles
 * for JS as well. Both runtimes have the thing, under different names and with no shared interface -
 * which is exactly what `expect`/`actual` is for.
 */
expect class VimWeakReference<T : Any>(referent: T) {
  /** What it points at, or null once the runtime has collected it. */
  fun get(): T?
}
