/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.maddyhome.idea.vim.ex.ranges.Range
import kotlin.reflect.KClass
import kotlin.reflect.full.createType

/**
 * Resolves an ex-command class named at runtime, and the constructor used to build it.
 *
 * The JVM's class loading and `kotlin.reflect.full` live here rather than in
 * [LazyExCommandInstance], because the providers that call this are JVM-bound anyway - they read
 * their command lists from classpath resources. A host without a class loader builds the
 * [LazyExCommandInstance] from a generated registry instead.
 */
internal fun lazyExCommand(className: String, classLoader: ClassLoader): LazyExCommandInstance {
  @Suppress("UNCHECKED_CAST")
  val kClass = classLoader.loadClass(className).kotlin as KClass<out Command>
  val constructor = kClass.constructors
    .filter { it.parameters.size == 3 }
    .firstOrNull {
      it.parameters[0].type == Range::class.createType() &&
        it.parameters[1].type == CommandModifier::class.createType() &&
        it.parameters[2].type == String::class.createType()
    }
  val factory = constructor?.let { ctor ->
    { range: Range, modifier: CommandModifier, argument: String ->
      ctor.call(range, modifier, argument)
    }
  }
  return LazyExCommandInstance(kClass, factory)
}
