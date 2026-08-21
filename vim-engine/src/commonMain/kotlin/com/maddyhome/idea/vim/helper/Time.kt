/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * Milliseconds since the Unix epoch, as `System.currentTimeMillis()` reports them.
 *
 * Wall-clock time, so it can jump backwards; only use it for timestamps that are displayed or
 * persisted, never for measuring elapsed time.
 */
expect fun currentTimeMillis(): Long
