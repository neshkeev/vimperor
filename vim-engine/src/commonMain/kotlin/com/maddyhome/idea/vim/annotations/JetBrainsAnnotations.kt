/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.annotations

/*
 * Phase 3 / W4. `org.jetbrains.annotations` is a Java-only library, so referencing it directly
 * pins a file to `jvmMain`. These `expect` declarations let common code keep the annotations;
 * the JVM `actual`s are typealiases to the real thing, so IntelliJ's inspections (i18n,
 * test-only enforcement) behave exactly as before on the JVM side. Non-JVM targets get an
 * annotation that is simply inert.
 *
 * Deleting the annotations instead would have compiled just as well and silently dropped that
 * tooling.
 */

@Target(
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.FIELD,
  AnnotationTarget.VALUE_PARAMETER,
  AnnotationTarget.LOCAL_VARIABLE,
  AnnotationTarget.ANNOTATION_CLASS,
  AnnotationTarget.TYPE,
)
expect annotation class NonNls()

@Target(
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.FIELD,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.CLASS,
)
expect annotation class TestOnly()

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.CONSTRUCTOR)
expect annotation class Contract(val value: String = "", val pure: Boolean = false, val mutates: String = "")

@Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
expect annotation class Range(val from: Long, val to: Long)

@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.TYPE)
expect annotation class PropertyKey(val resourceBundle: String)

/*
 * `@ApiStatus.Internal` and friends are nested in the Java library. A Kotlin typealias cannot
 * reproduce the nesting, so usages become `@Internal` / `@Obsolete` / `@ScheduledForRemoval`
 * rather than `@ApiStatus.X`.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD)
expect annotation class Internal()

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD)
expect annotation class Obsolete()

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD)
expect annotation class ScheduledForRemoval()
