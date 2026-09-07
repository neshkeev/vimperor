/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

// The common tree has a file of this name too, and on the JVM a file's top-level
// declarations land in a facade class named after it - so both would generate
// `JetBrainsAnnotationsKt` and the compilation fails. The names are deliberately identical, an
// `actual` beside its `expect`, so the facade moves instead of the file.
@file:JvmName("JetBrainsAnnotationsJvm")

package com.maddyhome.idea.vim.annotations

actual typealias NonNls = org.jetbrains.annotations.NonNls

actual typealias TestOnly = org.jetbrains.annotations.TestOnly

actual typealias Contract = org.jetbrains.annotations.Contract

actual typealias Range = org.jetbrains.annotations.Range

actual typealias PropertyKey = org.jetbrains.annotations.PropertyKey

actual typealias Internal = org.jetbrains.annotations.ApiStatus.Internal

actual typealias Obsolete = org.jetbrains.annotations.ApiStatus.Obsolete

actual typealias ScheduledForRemoval = org.jetbrains.annotations.ApiStatus.ScheduledForRemoval
