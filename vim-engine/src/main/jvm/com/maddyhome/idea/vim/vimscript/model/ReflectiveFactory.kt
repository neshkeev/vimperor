/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Builds the no-argument constructor call for a class named at runtime.
 *
 * This is where the JVM's dynamic class loading now lives, having moved out of [LazyInstance] so
 * that the lazy-instance abstraction itself is platform-neutral. It is called from the providers,
 * which are JVM-bound anyway - they read their class lists from classpath resources.
 *
 * A host without a class loader supplies its factories directly instead, from a generated registry;
 * that is the seam this exists to create.
 */
internal fun <T> reflectiveFactory(className: String, classLoader: ClassLoader): () -> T = {
  val aClass = classLoader.loadClass(className)
  val lookup = MethodHandles.privateLookupIn(aClass, MethodHandles.lookup())
  @Suppress("UNCHECKED_CAST")
  lookup.findConstructor(aClass, MethodType.methodType(Void.TYPE)).invoke() as T
}
