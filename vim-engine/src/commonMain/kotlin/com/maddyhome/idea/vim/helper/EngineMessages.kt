/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * Looks up a message by key and substitutes [params] into it.
 *
 * The bundle is `MessageFormat`-formatted, and that is more than positional substitution: the
 * engine's own bundle contains `{0,number,#0}`, and twelve of its lines contain a single quote,
 * which MessageFormat treats as an escape (`''` is one literal quote, and `'{0}'` is the literal
 * text `{0}`). A naive `replace("{0}", ...)` would corrupt those lines rather than fail on them,
 * so this stays on the platform's own formatter instead of being reimplemented.
 *
 * The JVM actual is `ResourceBundle` plus `MessageFormat`, exactly as before. A host without them
 * needs an embedded message map and a MessageFormat-compatible formatter - not a simpler one.
 */
internal expect fun lookupEngineMessage(key: String, params: Array<out Any>): String
