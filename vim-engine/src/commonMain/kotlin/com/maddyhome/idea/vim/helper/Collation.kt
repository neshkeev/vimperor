/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/**
 * Compares strings ignoring case, as `java.lang.String.CASE_INSENSITIVE_ORDER` does.
 *
 * Kotlin's `compareTo(ignoreCase = true)` is the same algorithm - upper-case both characters, and
 * lower-case the result if they still differ - so this is exactly the JDK comparator on the JVM
 * rather than an approximation of it. It backs `sort(list, 'i')`.
 */
val caseInsensitiveOrder: Comparator<String> =
  Comparator { a, b -> a.compareTo(b, ignoreCase = true) }

/**
 * Compares strings using the platform's collation for the current locale. Backs `sort(list, 'l')`,
 * which Vim documents as sorting "using the current locale".
 *
 * **The two targets do not agree character for character.** The JVM uses `java.text.Collator`,
 * whose tables come from the JDK's copy of CLDR; a browser or Node uses `Intl.Collator`, whose
 * tables come from whatever ICU that runtime was built with. Both implement the same Unicode
 * collation contract, and both order the same way for the cases users hit - accents, case, and
 * digits - but there is no promise of identical ordering for every pair, and pinning one would mean
 * shipping our own collation tables. Locale-sensitive by definition, so it is not a fixed order on
 * either target either.
 */
expect fun localeCollator(): Comparator<String>
