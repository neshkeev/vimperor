/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

// The common tree has a file of this name too, and on the JVM a file's top-level
// declarations land in a facade class named after it - so both would generate
// `EngineFunctionsKt` and the compilation fails. The names are deliberately identical, an
// `actual` beside its `expect`, so the facade moves instead of the file.
@file:JvmName("EngineFunctionsJvm")

package com.maddyhome.idea.vim.vimscript.model.functions

actual val engineFunctionProvider: VimscriptFunctionProvider = EngineFunctionProvider
