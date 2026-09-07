/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

@file:OptIn(ExperimentalJsExport::class)

package com.maddyhome.idea.vim.js

import com.maddyhome.idea.vim.regexp.parser.VimRegexParser
import com.maddyhome.idea.vim.regexp.parser.VimRegexParserResult

/**
 * What JavaScript can see of the engine.
 *
 * **This file is the reason the library is not empty.** Kotlin/JS eliminates everything not
 * reachable from an exported root, and only `@JsExport` creates a root - so without a file like
 * this, `IdeaVIM-vim-engine.js` compiles to a 561-byte shell exporting nothing, no matter how much
 * of the engine builds. The Kotlin/JS *tests* pass regardless, because test code is compiled with
 * the engine and is itself a root; that is why this went unnoticed until something tried to consume
 * the library from JavaScript.
 *
 * Deliberately one function. Designing the API a VS Code extension should be given is a real
 * decision - how much of Vim's surface to expose, and in what shape - and this proves the mechanism
 * without pre-empting it. Note also that the alternative is to write the extension in Kotlin/JS,
 * where nothing needs exporting at all because the extension is compiled with the engine.
 */
@JsExport
fun isValidVimPattern(pattern: String): Boolean =
  VimRegexParser.parse(pattern) is VimRegexParserResult.Success
