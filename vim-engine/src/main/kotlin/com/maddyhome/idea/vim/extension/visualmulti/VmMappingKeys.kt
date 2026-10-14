/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.visualmulti

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.extension.multiplecursors.isGlobalFlagEnabled
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDictionary
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString

/** When `0`, only the mappings with [VmMapping.alwaysMapped] are created. */
private const val DEFAULT_MAPPINGS = "VM_default_mappings"

/**
 * Mapping names to keys - `let g:VM_maps['Find Under'] = '<C-d>'`. An empty string disables the
 * mapping, and unknown names are ignored.
 */
private const val MAPS = "VM_maps"

/**
 * @param fromUser whether the keys came from `g:VM_maps`. vim-visual-multi maps those even when the
 *   user has mapped other keys to the `<Plug>` mapping, which is why it is worth knowing.
 */
internal data class MappingKeys(val keys: String, val fromUser: Boolean)

/** The keys of the enabled mappings, by name. */
internal fun readMappingKeys(): Map<String, MappingKeys> {
  val userMaps = injector.variableService.getGlobalVariableValue(MAPS) as? VimDictionary
  val defaultMappings = isGlobalFlagEnabled(DEFAULT_MAPPINGS)

  return buildMap {
    for (mapping in getPermanentMappings() + getSessionMappings()) {
      val userKeys = userMaps?.dictionary?.get(VimString(mapping.name)) as? VimString
      val keys = when {
        userKeys != null -> MappingKeys(userKeys.value, fromUser = true)
        mapping.alwaysMapped || defaultMappings -> MappingKeys(mapping.defaultKeys, fromUser = false)
        else -> null
      }
      if (keys != null && keys.keys.isNotEmpty()) {
        put(mapping.name, keys)
      }
    }
  }
}
