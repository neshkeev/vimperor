/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package org.jetbrains.plugins.ideavim

import org.junit.jupiter.params.provider.Arguments

/**
 * Every combination of the given lists, as JUnit arguments.
 *
 * Came with the two parser tests that use it, out of the plugin's test fixtures - which are going,
 * and which this was the only engine-shaped thing in. `@MethodSource` is why those two tests are
 * here and not in `commonTest` with the rest: `kotlin.test` has no parameterised tests, so a
 * conversion would have meant writing the combinations out by hand.
 */
fun productForArguments(vararg elements: List<String>): List<Arguments> =
  product(*elements).map { Arguments.of(*it.toTypedArray()) }

fun <T> product(vararg elements: List<T>): List<List<T>> =
  elements.fold(listOf(emptyList<T>())) { acc, items ->
    acc.flatMap { combination -> items.map { combination + it } }
  }
