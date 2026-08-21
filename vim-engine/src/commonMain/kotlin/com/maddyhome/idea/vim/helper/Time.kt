/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

import com.maddyhome.idea.vim.helper.currentTimeMillis
import com.maddyhome.idea.vim.helper.nanoTime

/**
 * Milliseconds since the Unix epoch, as `currentTimeMillis()` reports them.
 *
 * Wall-clock time, so it can jump backwards; only use it for timestamps that are displayed or
 * persisted, never for measuring elapsed time.
 */
expect fun currentTimeMillis(): Long

/**
 * A monotonic timestamp in nanoseconds, as `nanoTime()`.
 *
 * Unrelated to [currentTimeMillis]: this one has no defined origin and cannot be turned into a
 * date, but it never jumps backwards, which is what measuring an elapsed interval needs.
 */
expect fun nanoTime(): Long
