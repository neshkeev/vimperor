/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

/**
 * The engine's own ex-commands, however this target can supply them.
 *
 * The JVM reads a JSON resource and resolves each class name through the class loader. JS cannot,
 * and unlike the normal-mode commands and the vimscript functions - which are generated into a
 * registry of direct constructor calls - the ex-commands need one more thing that is not in the
 * JSON: whether each class has a `(Range, CommandModifier, String)` constructor.
 * [LazyExCommandInstance] carries a factory or null depending on that, and `CommandVisitor`
 * special-cases the 18 classes that take something else.
 *
 * Deciding it from the source would be a second answer that can disagree with the reflective one,
 * so JS registers nothing here for now and `:` commands are unavailable on that target. The parser
 * itself is shared and works; this is the registry, not the grammar.
 */
expect val engineExCommandProvider: ExCommandProvider
