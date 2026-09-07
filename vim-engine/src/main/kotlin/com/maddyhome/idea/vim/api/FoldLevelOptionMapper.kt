/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt

/**
 * Maps the `'foldlevel'` local-to-window Vim option to apply fold levels
 *
 * This mapper ensures that whenever the foldlevel option is changed, the fold state is immediately
 * applied to the editor. It coerces the value to valid bounds and handles the case where the value
 * is set to the same level (e.g., zM when foldlevel is already 0).
 */
class FoldLevelOptionMapper : LocalOptionValueOverride<VimInt> {
  override fun getLocalValue(storedValue: OptionValue<VimInt>?, editor: VimEditor): OptionValue<VimInt> {
    val maxDepth = editor.getMaxFoldDepth()

    if (storedValue == null) {
      return OptionValue.Default(VimInt(maxDepth + 1))
    }

    val coercedLevel = storedValue.value.value.coerceIn(0, maxDepth + 1)

    return storedValue.withValue(VimInt(coercedLevel))
  }

  override fun setLocalValue(
    storedValue: OptionValue<VimInt>?,
    newValue: OptionValue<VimInt>,
    editor: VimEditor,
  ): Boolean {
    val maxDepth = editor.getMaxFoldDepth()
    val coercedLevel = newValue.value.value.coerceIn(0, maxDepth + 1)

    // When a new window opens, setLocalValue is called twice: first from copyLocalToWindowLocalValues,
    // then from initialiseLocalToWindowOptions. We skip applyFoldLevel in both cases to preserve
    // IntelliJ's default fold state. The first call has storedValue=null, and the second has both
    // storedValue and newValue as Default - these conditions define initialization.
    if (!isInitializing(storedValue, newValue)) {
      editor.applyFoldLevel(coercedLevel)
    }

    return storedValue?.value?.value != coercedLevel
  }

  private fun isInitializing(
    storedValue: OptionValue<VimInt>?,
    newValue: OptionValue<VimInt>,
  ): Boolean = storedValue == null ||
    (storedValue is OptionValue.Default && newValue is OptionValue.Default)
}
