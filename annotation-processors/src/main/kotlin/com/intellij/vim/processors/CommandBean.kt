/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.intellij.vim.processors

import kotlinx.serialization.Serializable

/**
 * One key binding as the processor found it.
 *
 * [description] is the line `:help` shows beside the keys. It is defaulted, so the generated table only carries the
 * commands that have one - which is also how the help file knows which are still undescribed.
 */
@Serializable
data class CommandBean(
  val keys: String,
  val `class`: String,
  val modes: String,
  val description: String = "",
)