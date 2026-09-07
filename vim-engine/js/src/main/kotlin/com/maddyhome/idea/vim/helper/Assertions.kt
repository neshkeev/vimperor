/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * A no-op, matching the JVM where assertions are disabled unless the JVM is started with `-ea`.
 * The alternative - always throwing - would turn checks that have never fired for a user into
 * crashes on this host only.
 */
actual fun vimAssert(value: Boolean) {
}
