/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.maddyhome.idea.vim.ex.ranges.Range
import kotlin.reflect.KClass

/**
 * An ex-command the parser can identify and build.
 *
 * [kClass] is only ever compared for identity - `CommandVisitor` switches on it to special-case the
 * commands that need something other than the standard constructor. `kotlin.reflect.KClass` is
 * multiplatform, so that comparison is fine here; it was `kotlin.reflect.full` and the class loader
 * that were not, and both now live in the provider.
 *
 * [barSeparates] and [delimitedSections] are the command's `@ExCommand` values, carried through the generated table
 * unchanged. They are what tells the parser whether a `|` in the argument ends this command or belongs to it - Vim's
 * `TRLBAR` flag, read back. See `CommandVisitor.splitOffNextCommand`.
 */
class LazyExCommandInstance(
  private val kClass: KClass<out Command>,
  private val factory: ((Range, CommandModifier, String) -> Command)?,
  val barSeparates: Boolean = true,
  val delimitedSections: Int = 0,
) {
  fun getKClass(): KClass<out Command> = kClass

  /** False for commands with no `(Range, CommandModifier, String)` constructor, e.g. `:call`. */
  val hasStandardConstructor: Boolean
    get() = factory != null

  /** Builds the command, or null if it has no standard constructor. */
  fun create(range: Range, modifier: CommandModifier, argument: String): Command? =
    factory?.invoke(range, modifier, argument)
}
