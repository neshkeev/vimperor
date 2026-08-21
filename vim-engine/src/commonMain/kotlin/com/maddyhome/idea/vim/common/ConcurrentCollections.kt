/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.common

/**
 * A collection for listener lists: things that are iterated to notify, and added to or cleared at
 * arbitrary moments, including from inside a notification.
 *
 * Any implementation must provide both of these, because callers rely on both:
 *
 * 1. **Safe concurrent mutation**, on platforms that have threads. The JVM host adds and removes
 *    listeners from more than one thread, and this is deliberately not being narrowed to a plain
 *    list on the strength of an assumption that it does not.
 * 2. **Safe mutation during iteration.** A listener may remove itself, or register another, while
 *    being notified. That is reentrancy rather than concurrency, so it applies even to a
 *    single-threaded platform, and a plain `ArrayList` would throw.
 *
 * The JVM implementation is `java.util.concurrent.ConcurrentLinkedDeque`, exactly what these call
 * sites used before, so the JVM behaviour is unchanged. A single-threaded platform still needs
 * property 2 - a copy-on-write list, or a snapshot taken on iteration - and must not simply return
 * `mutableListOf()`.
 */
expect fun <T> concurrentCollectionOf(): MutableCollection<T>
