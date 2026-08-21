/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.intellij.vim.api.annotations

/*
 * Phase 3 / W6. Same problem, and same answer, as the shims in vim-engine:
 * `org.jetbrains.annotations` is a Java-only library, so referencing it pins a file to the JVM.
 * These are declared here rather than reused from the engine because vim-engine depends on this
 * module, not the other way round.
 *
 * The JVM actuals are typealiases to the real annotations, so IntelliJ's inspections behave
 * exactly as before on the JVM side.
 */

@Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
expect annotation class Range(val from: Long, val to: Long)

/*
 * `@ApiStatus.Experimental` is nested in the Java library, and a Kotlin typealias cannot reproduce
 * the nesting, so usages read `@Experimental` rather than `@ApiStatus.Experimental`.
 */
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.ANNOTATION_CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FIELD,
)
expect annotation class Experimental()
