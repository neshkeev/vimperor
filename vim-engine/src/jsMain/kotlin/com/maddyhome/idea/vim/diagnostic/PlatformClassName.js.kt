/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.diagnostic

/*
 * `this::class.simpleName` is what Kotlin/JS can offer. It is *not* the JVM's binary name, so
 * action ids derived from it will differ from the JVM's for a nested class - the `expect` says so.
 * A JS host that needs ids matching a JVM one has to declare them rather than derive them.
 */

actual fun platformClassName(instance: Any): String = instance::class.simpleName ?: "<anonymous>"

actual fun platformCanonicalName(instance: Any): String? = instance::class.simpleName

actual fun platformClassToString(instance: Any): String =
  "class " + (instance::class.simpleName ?: "<anonymous>")
