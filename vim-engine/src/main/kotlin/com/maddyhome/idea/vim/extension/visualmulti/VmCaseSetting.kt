/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.visualmulti

import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.extension.multiplecursors.CaseSensitivity
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString

private const val CASE_SETTING = "VM_case_setting"

/**
 * `g:VM_case_setting`: `smart`, `sensitive` or `ignore`. Any other value, the default empty string
 * included, follows `'ignorecase'` and `'smartcase'`.
 *
 * vim-visual-multi *sets* `'ignorecase'` and `'smartcase'` for the length of the session. This
 * resolves them the same way and leaves the user's options alone, which is the one divergence and is
 * the better trade: a session that ends badly cannot leave the options somewhere the user did not
 * put them.
 */
internal object VmCaseSetting : CaseSensitivity {

  override fun ignoresCase(text: String): Boolean {
    val (ignoreCase, smartCase) = when (readSetting()) {
      "smart" -> true to true
      "sensitive" -> false to false
      "ignore" -> true to false
      else -> injector.globalOptions().let { it.ignorecase to it.smartcase }
    }
    return ignoreCase && !(smartCase && text.any(Char::isUpperCase))
  }

  /** vim-visual-multi compares the value ignoring case. */
  private fun readSetting(): String? =
    (injector.variableService.getGlobalVariableValue(CASE_SETTING) as? VimString)?.value?.lowercase()
}
