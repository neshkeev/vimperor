/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.group

import com.maddyhome.idea.vim.api.SearchWindowGroupBase

/**
 * The IntelliJ registration of the command-line window. The feature itself is in `vim-engine` - it
 * never had a line of IntelliJ in it - and this exists only because `IdeaVIM.ideavim-frontend.xml`
 * names a service implementation class. It goes when the plugin does.
 */
class IjSearchWindowGroup : SearchWindowGroupBase()
