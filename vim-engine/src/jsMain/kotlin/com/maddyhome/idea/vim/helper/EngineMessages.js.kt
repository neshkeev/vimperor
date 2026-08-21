/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * Unimplemented on purpose.
 *
 * The bundle is a JVM `.properties` resource and the format is `MessageFormat`, which is more than
 * placeholder substitution: the engine's bundle contains `{0,number,#0}` and twelve lines whose
 * single quotes are MessageFormat escapes. A naive `replace("{0}", ..)` would corrupt those lines
 * rather than fail on them, and every one of these strings is shown to the user.
 *
 * A JS host needs the bundle embedded as a map plus a MessageFormat-compatible formatter.
 */
internal actual fun lookupEngineMessage(key: String, params: Array<out Any>): String =
  TODO("engine messages need an embedded bundle and a MessageFormat-compatible formatter on JS")
