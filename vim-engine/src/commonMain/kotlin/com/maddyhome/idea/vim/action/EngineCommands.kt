/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.action

/**
 * The engine's own commands, however this target can supply them: a JSON resource read through the
 * class loader on the JVM, a generated registry of direct constructor calls on JS.
 *
 * The sibling of `engineExCommandProvider`. Common code needs a way to name the list without
 * naming either mechanism.
 */
expect val engineCommandProvider: CommandProvider
