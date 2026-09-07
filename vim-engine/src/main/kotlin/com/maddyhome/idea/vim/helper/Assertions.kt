/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * An internal invariant check, with the same semantics as Kotlin's JVM-only `assert`.
 *
 * Deliberately not `check`: `assert` is disabled unless the JVM is started with `-ea`, so it is a
 * no-op in production, whereas `check` always throws. Swapping one for the other would turn
 * assertions that have never fired for a user into crashes.
 */
expect fun vimAssert(value: Boolean)
