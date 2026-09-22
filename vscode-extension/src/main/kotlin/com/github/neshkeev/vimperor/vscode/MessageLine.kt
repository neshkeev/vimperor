/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

/**
 * Vim's message line: one status bar entry holding whatever Vim last said.
 *
 * Vim's bottom row is the command line while one is open and the last message the rest of the time,
 * and "the last message" is one thing however it was produced - `/pattern`, `3 substitutions on 2
 * lines` and `E486: Pattern not found` are all the same row. So all three [MessageSink] methods
 * land here and the last one wins, which is what makes a failed search read correctly: the engine
 * says `/pattern`, then `search hit BOTTOM`, then the error, and the error is what stays.
 *
 * **What this replaces is the reason it exists.** The output channel used to carry all of it, and
 * an error called `show()` on the channel - so a mistyped `/` opened the panel over the editor,
 * every time, for a message that belongs on one line. The method named for the status bar wrote to
 * the mode indicator's *tooltip*, which nobody hovers, so the one path that was meant for this
 * showed nothing at all.
 *
 * The channel still gets every line, because it is the log this extension is debugged from and a
 * message line cannot be scrolled back. What it no longer does is reveal itself: a panel that was
 * hidden when the command ran is still hidden afterwards. [OutputChannelPanel] is the other half of
 * that rule and keeps its `show()` - `:registers` is output a user asked to see, and an error is
 * not.
 */
internal class MessageLine(
  private val log: OutputChannel,
  private val item: StatusBarItem,
) : MessageSink {

  override fun message(text: String?) {
    if (text == null) return
    draw(text, isError = false)
  }

  override fun error(text: String?) {
    if (text == null) return
    draw(text, isError = true)
  }

  /**
   * `showStatusBarMessage`, which in Vim is the same row as the rest - see [MessageLine].
   *
   * Null means clear here and nowhere else: `clearStatusBarMessage` is the engine's way of taking
   * the message back, while a null from [message] or [error] is a caller with nothing to say - an
   * exception whose `message` was null, most often - and wiping the line for one of those would
   * throw away a message the user has not read.
   */
  override fun status(text: String?) {
    if (text == null) clear() else draw(text, isError = false)
  }

  /** Takes the line back, which is what a command line opening over it does in Vim. */
  fun clear() {
    item.text = ""
    item.tooltip = null
    item.backgroundColor = null
    item.hide()
  }

  private fun draw(text: String, isError: Boolean) {
    log.appendLine(text)
    if (text.isBlank()) return clear()
    item.text = oneLine(text)
    // The whole of it, unshortened and with its line breaks, for anything the row could not hold.
    item.tooltip = text
    item.backgroundColor = if (isError) ThemeColor(ERROR_BACKGROUND) else null
    item.show()
  }

  private companion object {
    /** One of the two colours VS Code accepts for a status bar background. */
    const val ERROR_BACKGROUND = "statusBarItem.errorBackground"

    /**
     * How much of a message a row can hold before it starts pushing the rest of the status bar off.
     * The wildmenu next door works to the same budget for the same reason.
     */
    const val BUDGET = 96

    /**
     * A message as one line of status bar text.
     *
     * Two things a status bar entry cannot take literally. It is a single row, so the newlines an
     * `:echoerr` can carry become spaces rather than being dropped - a line break is a word break,
     * and running the two sides together would invent a word. And `$(name)` in the text is *drawn
     * as an icon*: VS Code parses it and offers no escape, so `/\$(` - a perfectly ordinary search
     * for a literal dollar and bracket - would report its failure with a picture in the middle of
     * the pattern. A zero-width space after the dollar stops the parse and is invisible, which is
     * the workaround the codicon syntax has always needed.
     */
    fun oneLine(text: String): String {
      val flattened = text.split('\n', '\r').joinToString(" ") { it.trim() }.trim()
      val escaped = flattened.replace("\$(", "\$\u200b(")
      return if (escaped.length <= BUDGET) escaped else escaped.take(BUDGET - 1).trimEnd() + "…"
    }
  }
}
