/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.annotations

/*
 * The @Target lists below are derived from the Java annotations the JVM actuals alias, not from
 * where the engine happens to use them today. An `expect` narrower than its `actual` compiles
 * everywhere except common code, so the mismatch only surfaces when a file moves into commonMain -
 * which is exactly how the missing CLASS target on NonNls was found, long after it was introduced.
 *
 * Java to Kotlin: TYPE -> CLASS, ANNOTATION_TYPE -> ANNOTATION_CLASS, METHOD -> FUNCTION/PROPERTY,
 * PARAMETER -> VALUE_PARAMETER, TYPE_USE -> TYPE. PACKAGE has no Kotlin equivalent and is dropped.
 */

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
  // The JVM actual is a Java TYPE_USE annotation and so accepts far more positions than these.
  // The engine annotates enum classes, which only failed once those files compiled as common: in
  // jvmMain `NonNls` resolves through the typealias to the Java annotation and its broader targets.
  // An `expect` narrower than its `actual` is a latent error that only the common compile finds.
  AnnotationTarget.CLASS,
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

@Target(
  AnnotationTarget.VALUE_PARAMETER,
  AnnotationTarget.LOCAL_VARIABLE,
  AnnotationTarget.FIELD,
  AnnotationTarget.TYPE,
)
expect annotation class PropertyKey(val resourceBundle: String)

/*
 * `@ApiStatus.Internal` and friends are nested in the Java library. A Kotlin typealias cannot
 * reproduce the nesting, so usages become `@Internal` / `@Obsolete` / `@ScheduledForRemoval`
 * rather than `@ApiStatus.X`.
 */
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.ANNOTATION_CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FIELD,
)
expect annotation class Internal()

@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.ANNOTATION_CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FIELD,
)
expect annotation class Obsolete()

@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.ANNOTATION_CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FIELD,
)
expect annotation class ScheduledForRemoval()
