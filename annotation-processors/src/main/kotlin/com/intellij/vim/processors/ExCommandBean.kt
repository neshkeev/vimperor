/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.intellij.vim.processors

import kotlinx.serialization.Serializable

/**
 * One ex-command as the processor found it.
 *
 * [standardConstructor] says whether the class takes `(Range, CommandModifier, String)`. The
 * parser needs that answer to know whether it can build the command generically or has to
 * special-case it, and this is the only place it can be computed once: KSP has the declared types,
 * where a host without reflection has neither them nor a class loader. Deriving it anywhere else
 * would be a second answer that can disagree with this one.
 */
@Serializable
data class ExCommandBean(val `class`: String, val standardConstructor: Boolean)
