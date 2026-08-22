/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

/*
 * The engine's message bundle, embedded by the generateJsMessageBundle task, plus enough of
 * `java.text.MessageFormat` to render it.
 *
 * "Enough" is precise here, not approximate. The bundle uses exactly three things, and the quoting
 * rules are the part that a naive `replace("{0}", ..)` gets wrong:
 *
 * - `{n}` substitutes argument n.
 * - `{n,number,...}` formats a number. One key uses it (E684), with the pattern `#0`, which is a
 *   plain integer rendering.
 * - A single quote opens a quoted section that ends at the next single quote, and `''` is a literal
 *   quote. So `''langmap''` renders as `'langmap'` - and `'{0}'` renders as the literal text `{0}`,
 *   *not* the argument. E354 in this bundle does exactly that, so the register name never appears
 *   in the message. That is pre-existing behaviour on the JVM and is reproduced here rather than
 *   quietly fixed; fixing it is a change to what users see and belongs in its own commit.
 */

private fun formatMessage(pattern: String, params: Array<out Any>): String {
  val out = StringBuilder()
  var i = 0
  while (i < pattern.length) {
    val c = pattern[i]
    when {
      c == '\'' && i + 1 < pattern.length && pattern[i + 1] == '\'' -> {
        out.append('\'')
        i += 2
      }
      c == '\'' -> {
        // A quoted section: everything up to the next quote is literal, braces included.
        val end = pattern.indexOf('\'', i + 1)
        if (end < 0) {
          out.append(pattern, i + 1, pattern.length)
          i = pattern.length
        } else {
          out.append(pattern, i + 1, end)
          i = end + 1
        }
      }
      c == '{' -> {
        val end = pattern.indexOf('}', i)
        if (end < 0) {
          out.append(pattern, i, pattern.length)
          i = pattern.length
        } else {
          val body = pattern.substring(i + 1, end)
          val index = body.substringBefore(',').trim().toIntOrNull()
          if (index == null || index >= params.size) {
            // Not an argument reference, or no such argument: MessageFormat leaves it alone.
            out.append(pattern, i, end + 1)
          } else {
            out.append(renderArgument(params[index], body.substringAfter(',', "")))
          }
          i = end + 1
        }
      }
      else -> {
        out.append(c)
        i++
      }
    }
  }
  return out.toString()
}

private fun renderArgument(value: Any, format: String): String =
  if (format.startsWith("number")) {
    // The only number pattern in the bundle is `#0`, an integer rendering with no grouping.
    when (value) {
      is Int -> value.toString()
      is Long -> value.toString()
      is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
      else -> value.toString()
    }
  } else {
    value.toString()
  }

internal actual fun lookupEngineMessage(key: String, params: Array<out Any>): String {
  val pattern = GENERATED_ENGINE_MESSAGES[key]
    ?: throw NoSuchElementException("No message for key '" + key + "'")
  return if (params.isEmpty()) pattern else formatMessage(pattern, params)
}
