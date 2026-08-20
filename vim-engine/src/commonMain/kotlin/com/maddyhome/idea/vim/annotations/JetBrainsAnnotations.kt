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
