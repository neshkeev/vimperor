/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

import kotlin.js.Date

actual fun currentTimeMillis(): Long = Date.now().toLong()

/**
 * `performance.now()` is the monotonic clock in a browser or Node; scaled to nanoseconds so the
 * unit matches the JVM. The resolution is coarser than a nanosecond - callers measure intervals,
 * they do not depend on the granularity.
 */
actual fun nanoTime(): Long = (js("performance.now()") as Double * 1_000_000).toLong()
