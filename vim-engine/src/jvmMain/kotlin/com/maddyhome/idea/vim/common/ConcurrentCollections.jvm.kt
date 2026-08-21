/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.common

import java.util.concurrent.ConcurrentLinkedDeque

actual fun <T> concurrentCollectionOf(): MutableCollection<T> = ConcurrentLinkedDeque()
