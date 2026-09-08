/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.keyboard

import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.options.helpers.LangMapOptionHelper
import com.maddyhome.idea.vim.vimscript.model.commands.UnknownCommand

/**
 * `:ыуе тщцкфз` runs `:set nowrap`.
 *
 * `'langmap'` deliberately stops at the command line, and that is Vim's rule and the right one: a
 * command line carries *text* as well as commands - `:s/привет/пока/` and `:e привет.txt` mean what
 * they say, and translating them would be silent corruption. But a whole line typed in the wrong
 * layout is not text; it is an accident, and today it is `E492` and a retype.
 *
 * So this is not a translation, it is a **correction**, and it is allowed only where a correction
 * cannot destroy anything. Four conditions, all of them required:
 *
 * 1. **The line has a character outside ASCII.** Otherwise there is nothing a layout could explain.
 * 2. **The line has no ASCII letter anywhere.** This is the rule that makes the rest safe, and it
 *    is the difference between an accident and a decision. Someone who forgot to switch layouts
 *    typed the *whole* line in Cyrillic - the command and its argument alike, because switching is
 *    what they forgot to do. Someone who typed `:w привет.txt` switched deliberately, and the
 *    Cyrillic in that line is the filename they meant. Digits and punctuation are not letters, so
 *    `:ыуе еы=4` still qualifies.
 * 3. **The command as typed is not a command.** Nothing that works today changes, and nothing has
 *    run at the point this is decided - the line is parsed, not executed, so a correction that is
 *    wrong cannot have half-happened.
 * 4. **The corrected line is a command.** Otherwise the user gets `E492` for what they actually
 *    typed, which is the more useful error.
 *
 * The caller says what it ran. A correction that guesses wrong has to be visible, because the one
 * case this cannot tell apart is a fully-Cyrillic line whose argument was meant literally -
 * `:%ы/привет/пока/`, where the `s` was a slip and the pattern was not.
 */
object CommandLineLayout {

  /** The corrected command line, or `null` to run what was typed. */
  fun correct(typed: String): String? {
    if (typed.none { it.code > 0x7F }) return null
    if (typed.any { it in 'a'..'z' || it in 'A'..'Z' }) return null
    if (isCommand(typed)) return null

    // Only the characters outside ASCII, so that ranges, delimiters, digits and flags survive
    // untouched. That the tables never map an ASCII character makes this a no-op on the rest of
    // the line rather than a second thing to get right - see `KeyboardLayouts`.
    val corrected = buildString {
      for (char in typed) append(if (char.code > 0x7F) LangMapOptionHelper.mapChar(char) else char)
    }
    if (corrected == typed) return null

    return if (isCommand(corrected)) corrected else null
  }

  /**
   * Whether the engine has a command by this name - parsed, never executed.
   *
   * An unregistered name parses to [UnknownCommand], which is also what a `:command` alias parses
   * to, so the alias has to be asked about separately or defining one in Cyrillic would stop it
   * being reachable.
   */
  private fun isCommand(text: String): Boolean {
    val parsed = try {
      injector.vimscriptParser.parseCommand(text)
    } catch (ignored: Exception) {
      null
    } ?: return false
    return parsed !is UnknownCommand || injector.commandGroup.isAlias(parsed.name)
  }
}
