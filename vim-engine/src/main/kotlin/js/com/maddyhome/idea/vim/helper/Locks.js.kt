/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * JS is single-threaded, so there is nothing to hold. The lock argument is ignored and the block
 * runs directly.
 */
actual inline fun <R> withLock(lock: Any, block: () -> R): R = block()
