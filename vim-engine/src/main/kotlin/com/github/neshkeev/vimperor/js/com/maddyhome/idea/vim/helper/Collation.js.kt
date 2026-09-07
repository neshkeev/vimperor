/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * `Intl.Collator` with no locale argument, which resolves to the runtime's current locale - the
 * same "follow the host's locale" rule the JVM side applies.
 */
actual fun localeCollator(): Comparator<String> {
  val collator = js("new Intl.Collator()")
  return Comparator { a, b -> (collator.compare(a, b) as Number).toInt() }
}
