/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.api

import com.maddyhome.idea.vim.ex.ranges.LineRange
import com.maddyhome.idea.vim.vimscript.model.commands.Command
import com.maddyhome.idea.vim.vimscript.model.commands.GlobalCommand
import com.maddyhome.idea.vim.vimscript.model.commands.SubstituteCommand

/**
 * What the command line, as typed so far, is asking `'incsearch'` to show.
 *
 * `'incsearch'` is not only about `/` and `?`. Vim previews the pattern of `:s`, `:g` and `:v` as
 * well, and those need the command line taken apart first - a range, a command name, a separator
 * and only then a pattern - where a search prompt is a pattern and nothing else. That taking-apart
 * is what this is, and it is entirely the engine's: it parses the text with the Vimscript parser
 * and resolves the range against the buffer, with no host in it anywhere.
 *
 * It lived in `ExEntryPanel`, in `src/main/java`, next to a Swing document listener - which is why
 * the VS Code host previewed `/` and not `:%s/`, and why one of IdeaVim's own fixtures was recorded
 * as unportable when only its caller was.
 */
sealed interface IncsearchPreviewRequest {
  /**
   * A pattern to preview, and where to look for it.
   *
   * [searchRange] is null for a plain `/` or `?`, where the search starts at the caret and runs to
   * the end of the buffer. For an ex command it is the command's own range, which is where the
   * preview looks *and* where it starts from: `:%s/dolor` shows the first match in the file, not
   * the first one after the caret.
   *
   * [searchText] is the argument as typed, which is more than the pattern - `/foo/bar/g` is all of
   * it. [VimSearchGroup.findEndOfPattern] is what cuts the pattern out, and it needs [separator].
   */
  data class Show(
    val label: String,
    val separator: Char,
    val searchText: String,
    val isExCommand: Boolean,
    val searchRange: LineRange?,
    val substituteCommand: SubstituteCommand?,
  ) : IncsearchPreviewRequest

  /** Nothing to preview: an empty command line, or a command that has no pattern in it. */
  object None : IncsearchPreviewRequest

  /**
   * There was a preview and there is no longer a pattern for it, so put back what was on screen.
   *
   * Highlight `whatever`, type `:%s/foo` - now highlighting `foo` - then delete back to `:%s/`.
   * The difference from [None] is that `'hlsearch'` had something of its own to show and this is
   * the moment to give it back. A range the user has typed but that does not resolve - an unset
   * mark - lands here too.
   */
  object Reset : IncsearchPreviewRequest
}

/**
 * Reads the command line as it stands and says what the preview should show.
 *
 * [label] is the prompt character - `/`, `?` or `:` - and [text] is everything typed after it.
 */
fun incsearchPreviewRequest(editor: VimEditor, label: String, text: String): IncsearchPreviewRequest {
  if (label != ":") {
    // A search prompt: the whole entry is the pattern, and the separator is the prompt itself.
    val separator = label.firstOrNull() ?: return IncsearchPreviewRequest.None
    return IncsearchPreviewRequest.Show(label, separator, text, isExCommand = false, null, null)
  }

  if (text.isEmpty()) return IncsearchPreviewRequest.None
  val command = incsearchCommand(text) ?: return IncsearchPreviewRequest.None

  // The argument of `:%s/foo/bar/g` is `/foo/bar/g`: the first character is the separator and the
  // rest is the pattern, the replacement and the flags. `%` is the range and `s` the command, both
  // of which the parser has already taken off.
  val argument = command.commandArgument
  var separator = label[0]
  var searchText = ""
  if (argument.length > 1) {
    separator = argument[0]
    searchText = argument.substring(1)
  }
  val searchRange = if (searchText.isNotEmpty()) command.getLineRangeSafe(editor) else null
  if (searchText.isEmpty() || searchRange == null) return IncsearchPreviewRequest.Reset

  return IncsearchPreviewRequest.Show(
    label,
    separator,
    searchText,
    isExCommand = true,
    searchRange,
    command as? SubstituteCommand,
  )
}

/**
 * The pattern out of a request, which is not all of [IncsearchPreviewRequest.Show.searchText].
 *
 * Vim ends the pattern at the first unescaped separator, so `/foo/e+1` searches for `foo` and
 * `:%s/foo/bar/g` for `foo`.
 */
fun IncsearchPreviewRequest.Show.pattern(): String =
  searchText.take(injector.searchGroup.findEndOfPattern(searchText, separator, 0))

/**
 * The commands Vim previews, which is not every command with a pattern in it.
 *
 * A half-typed command line is usually not a command at all, so a parse failure here is the normal
 * state of typing rather than something to report. IdeaVim logged it, which in an IntelliJ test
 * would have failed the test - so it never fired, and there is nothing to carry across.
 */
private fun incsearchCommand(text: String): Command? {
  val command = try {
    injector.vimscriptParser.parseCommand(text)
  } catch (e: Throwable) {
    null
  }
  // TODO: `:smagic` and `:snomagic` belong here if they are ever supported.
  return if (command is SubstituteCommand || command is GlobalCommand) command else null
}
