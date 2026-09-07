/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.diagnostic

/**
 * The platform's name for [instance]'s class, as `Class.getName()` reports it on the JVM.
 *
 * Deliberately not `this::class.simpleName`. Action ids are derived from this by taking everything
 * after the last dot, and the JVM spells a nested class `Outer$Inner` - so `Matchit$MatchitAction`
 * yields the action id `VimMatchit$MatchitAction`, while `simpleName` would yield
 * `VimMatchitAction`. Those ids appear in users' `<Action>` mappings, so the difference is a
 * silent rename rather than a cosmetic one.
 *
 * That also names a phase 4 problem early: a host whose class names are mangled or minified cannot
 * produce stable action ids this way, and will need them declared explicitly rather than derived.
 */
expect fun platformClassName(instance: Any): String

/**
 * The canonical (source-form) name of [instance]'s class, as `Class.getCanonicalName()`, or null
 * where the platform has none.
 *
 * Distinct from [platformClassName]: canonical spells a nested class `Outer.Inner`, binary spells
 * it `Outer$Inner`. This one reaches `:map` output, so the difference is visible to users.
 */
expect fun platformCanonicalName(instance: Any): String?

/**
 * The platform's own rendering of [instance]'s class, as `Class.toString()` - `class com.foo.Bar`
 * on the JVM.
 *
 * Reaches the value printed for a command alias, so it is reproduced rather than reconstructed
 * from a name and a prefix.
 */
expect fun platformClassToString(instance: Any): String
