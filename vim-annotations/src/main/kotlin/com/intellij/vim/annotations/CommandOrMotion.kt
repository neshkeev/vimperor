/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.intellij.vim.annotations

// TODO support numpad keys parsing, see :keycodes
/**
 * It's not necessary a Vim command
 * This annotation may be used for:
 * - commands
 * - motions
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class CommandOrMotion(
  val keys: Array<String>,
  vararg val modes: Mode,
  /**
   * One line, in the present tense, saying what the command does - the line `:help` shows beside the keys.
   *
   * Write it the way Vim's own `index.txt` writes it: "Delete [count] lines", not "Deletes lines". `[count]`,
   * `{motion}` and `["x]` are Vim's notation for the parts a user supplies and are worth keeping. Empty means the
   * command is left out of the help, which is a hole rather than a decision.
   */
  val description: String = "",
)

annotation class TextObject(val keys: String)


enum class Mode(val abbrev: Char) {
  /**
   * Indicates this key mapping applies to Normal mode
   */
  NORMAL('N'),

  /**
   * Indicates this key mapping applies to Visual mode
   */
  VISUAL('X'),

  /**
   * Indicates this key mapping applies to Select mode
   */
  SELECT('S'),

  /**
   * Indicates this key mapping applies to Operator Pending mode
   */
  OP_PENDING('O'),

  /**
   * Indicates this key mapping applies to Insert or Replace modes
   */
  INSERT('I'),

  /**
   * Indicates this key mapping applies to Command Line mode
   */
  CMD_LINE('C'),
}