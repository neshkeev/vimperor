/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.thinapi

/**
 * IdeaVim's `VimPluginService`, which is the shared one.
 *
 * All three of its methods were one-line delegations to `VimExtensionFacade`, and none of those
 * bodies had any IntelliJ in them - running normal-mode keys, declaring a Vimscript function and
 * adding a command alias are all the engine's. They are [VimPluginServiceBase] now, so a host
 * without IntelliJ has them too.
 */
class IjVimPluginService : VimPluginServiceBase()
