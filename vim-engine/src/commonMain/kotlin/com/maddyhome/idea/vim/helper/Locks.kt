/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * Runs [block] holding [lock], where the platform has locks.
 *
 * The JVM actual is `synchronized`, unchanged. Kotlin/JS rejects `synchronized` outright rather
 * than treating it as a no-op, so the choice cannot be dodged - and a single-threaded runtime has
 * nothing to exclude, so its actual runs the block directly.
 *
 * Inline so that `return` inside [block] still returns from the enclosing function. The call sites
 * wrap whole method bodies and return from the middle of them.
 */
expect inline fun <R> withLock(lock: Any, block: () -> R): R
