/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.annotations

/*
 * There is no JS equivalent of org.jetbrains.annotations, and nothing to point one at: these carry
 * information for IntelliJ's inspections, which only ever run against the JVM sources. Inert
 * declarations keep the annotations readable in common code and cost nothing at runtime.
 */

actual annotation class NonNls

actual annotation class TestOnly

actual annotation class Contract(
  actual val value: String = "",
  actual val pure: Boolean = false,
  actual val mutates: String = "",
)

actual annotation class Range(actual val from: Long, actual val to: Long)

actual annotation class PropertyKey(actual val resourceBundle: String)

actual annotation class Internal

actual annotation class Obsolete

actual annotation class ScheduledForRemoval
