/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/*
 * Phase 3 / W4. `java.util.EnumSet` is the single largest JVM blocker in the engine - 82 files.
 *
 * The strategy is to widen the *declared* type from `EnumSet<T>` to `MutableSet<T>` while
 * leaving the *runtime* type alone: on the JVM these factories still hand back real `EnumSet`
 * instances, so ordinal iteration order and the bitset representation are exactly what they
 * were. Only the static type changes, which is what common code needs.
 *
 * That matters because iteration order here is not purely internal - mapping lists are rendered
 * to the user - so swapping in a `LinkedHashSet` on the JVM would be a silent behaviour change.
 * Non-JVM targets will get insertion order and must be re-checked when the js target lands;
 * the spec already expects collection-order divergence there.
 *
 * The old helpers used `T::class.java`, so they were W3-blocked as well as W4-blocked. Passing
 * `enumValues<T>()` in from an inline reified caller avoids reflection entirely.
 */

expect fun <T : Enum<T>> enumSetFrom(all: Array<T>, initial: Array<out T>): MutableSet<T>

inline fun <reified T : Enum<T>> noneOfEnum(): MutableSet<T> = enumSetFrom(enumValues<T>(), emptyArray())

inline fun <reified T : Enum<T>> enumSetOf(vararg value: T): MutableSet<T> = enumSetFrom(enumValues<T>(), value)

inline fun <reified T : Enum<T>> allOfEnum(): MutableSet<T> = enumSetFrom(enumValues<T>(), enumValues<T>())

/** Defensive copy that keeps the platform's enum-set representation. */
inline fun <reified T : Enum<T>> Set<T>.toEnumSet(): MutableSet<T> =
  enumSetFrom(enumValues<T>(), this.toTypedArray())
