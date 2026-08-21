/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.intellij.vim.api.annotations

/*
 * There is no JS equivalent of org.jetbrains.annotations, and nothing to point one at: these carry
 * information for IntelliJ's inspections, which only run against the JVM sources. Inert
 * declarations keep the annotations readable in common code and cost nothing at runtime.
 */

@Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
actual annotation class Range(actual val from: Long, actual val to: Long)

@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.ANNOTATION_CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FIELD,
)
actual annotation class Experimental
