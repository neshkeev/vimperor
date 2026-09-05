/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.multiplecursors

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getText
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.options
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.group.visual.vimSetSelection
import com.maddyhome.idea.vim.helper.SearchOptions
import com.maddyhome.idea.vim.helper.endOffsetInclusive
import com.maddyhome.idea.vim.helper.enumSetOf
import com.maddyhome.idea.vim.helper.exitVisualMode
import com.maddyhome.idea.vim.key.MappingOwner
import com.maddyhome.idea.vim.state.mode.SelectionType
import com.maddyhome.idea.vim.state.mode.inVisualMode
import kotlin.math.max
import kotlin.math.min

private const val NEXT_WHOLE_OCCURRENCE = "<Plug>NextWholeOccurrence"

private const val NEXT_OCCURRENCE = "<Plug>NextOccurrence"

private const val SKIP_OCCURRENCE = "<Plug>SkipOccurrence"

private const val REMOVE_OCCURRENCE = "<Plug>RemoveOccurrence"

private const val ALL_WHOLE_OCCURRENCES = "<Plug>AllWholeOccurrences"

private const val ALL_OCCURRENCES = "<Plug>AllOccurrences"

/**
 * When set to `0`, the default key mappings are not created, allowing the user to map the `<Plug>` mappings to keys of
 * their own choosing (e.g. mapping back to the old `<A-n>` based mappings). This matches the
 * `g:multi_cursor_use_default_mapping` option of the original vim-multiple-cursors plugin.
 */
private const val USE_DEFAULT_MAPPING = "multi_cursor_use_default_mapping"

/**
 * Per-buffer state, keyed the way `exchange` keys its own and for the same reason: IdeaVim kept this
 * in the editor's user data, the engine has no per-editor storage, and `IjVimEditor` throws from
 * `equals` so it cannot be a map key. The path is what both hosts agree on.
 */
private class Session {
  var wholeWord: Boolean? = null
  var lastSelection: TextRange? = null
}

private val sessions: MutableMap<String, Session> = mutableMapOf()

private val VimEditor.session: Session get() = sessions.getOrPut(getPath() ?: "") { Session() }

/** Drops every buffer's session, for `set nomultiple-cursors`. */
public fun disposeMultipleCursors(): Unit = sessions.clear()

/**
 * Port of vim-multiple-cursors.
 *
 * See https://github.com/terryma/vim-multiple-cursors
 *
 * ## What moving it cost
 *
 * Carets, and less than it looked. `VimEditor` has had `addCaret(offset)` and `removeCaret` all
 * along, so the only thing IntelliJ's `CaretModel` was really being asked for was a caret at a
 * *visual* position - which is a buffer position here, and the two differ only where something is
 * folded or soft-wrapped. `updateCaretsVisualAttributes` goes: it repaints IntelliJ's carets for
 * the current mode, which both hosts already do when they flush.
 */
@VimPlugin(name = MULTIPLE_CURSORS)
public fun VimInitApi.init(): Unit = registerMultipleCursors()

/** Public because the plugin's extension-point adapter names it too. */
public const val MULTIPLE_CURSORS: String = "multiple-cursors"

public fun registerMultipleCursors() {
  val owner = MappingOwner.Plugin.get(MULTIPLE_CURSORS)
  putExtensionHandlerMapping(
    MappingMode.NXO,
    injector.parser.parseKeys(NEXT_WHOLE_OCCURRENCE),
    owner,
    NextOccurrenceHandler(),
    false
  )
  putExtensionHandlerMapping(
    MappingMode.NXO,
    injector.parser.parseKeys(NEXT_OCCURRENCE),
    owner,
    NextOccurrenceHandler(whole = false),
    false,
  )
  putExtensionHandlerMapping(
    MappingMode.NXO,
    injector.parser.parseKeys(ALL_WHOLE_OCCURRENCES),
    owner,
    AllOccurrencesHandler(),
    false
  )
  putExtensionHandlerMapping(
    MappingMode.NXO,
    injector.parser.parseKeys(ALL_OCCURRENCES),
    owner,
    AllOccurrencesHandler(whole = false),
    false,
  )
  putExtensionHandlerMapping(
    MappingMode.X,
    injector.parser.parseKeys(SKIP_OCCURRENCE),
    owner,
    SkipOccurrenceHandler(),
    false
  )
  putExtensionHandlerMapping(
    MappingMode.X, injector.parser.parseKeys(REMOVE_OCCURRENCE), owner,
    RemoveOccurrenceHandler(), false
  )

  val useDefaultMapping =
    injector.variableService.getGlobalVariableValue(USE_DEFAULT_MAPPING)?.toVimNumber()?.booleanValue ?: true
  if (useDefaultMapping) {
    putKeyMappingIfMissing(
      MappingMode.NXO,
      injector.parser.parseKeys("<C-n>"),
      owner,
      injector.parser.parseKeys(NEXT_WHOLE_OCCURRENCE),
      true
    )
    putKeyMappingIfMissing(
      MappingMode.NXO,
      injector.parser.parseKeys("g<C-n>"),
      owner,
      injector.parser.parseKeys(NEXT_OCCURRENCE),
      true
    )
    putKeyMappingIfMissing(
      MappingMode.NXO,
      injector.parser.parseKeys("<A-n>"),
      owner,
      injector.parser.parseKeys(ALL_WHOLE_OCCURRENCES),
      true
    )
    putKeyMappingIfMissing(
      MappingMode.NXO,
      injector.parser.parseKeys("g<A-n>"),
      owner,
      injector.parser.parseKeys(ALL_OCCURRENCES),
      true
    )
    putKeyMappingIfMissing(
      MappingMode.X,
      injector.parser.parseKeys("<C-x>"),
      owner,
      injector.parser.parseKeys(SKIP_OCCURRENCE),
      true
    )
    putKeyMappingIfMissing(
      MappingMode.X,
      injector.parser.parseKeys("<C-p>"),
      owner,
      injector.parser.parseKeys(REMOVE_OCCURRENCE),
      true
    )
  }
  }

private abstract class WriteActionHandler : ExtensionHandler {
  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
  injector.application.runWriteAction {
    executeInWriteAction(editor, context)
  }
  }

  abstract fun executeInWriteAction(editor: VimEditor, context: ExecutionContext)
}

private class NextOccurrenceHandler(val whole: Boolean = true) : WriteActionHandler() {
  override fun executeInWriteAction(editor: VimEditor, context: ExecutionContext) {

    // vim-multiple-cursors provides a completely custom implementation of multiple cursors. We can rely on IntelliJ's
    // implementation.
    // vim-multiple-cursors will call "new" to add a new cursor. In normal mode, it sets "whole" to true, in visual,
    // "whole" is false. The "whole" flag is saved to a script wide variable, the cursor is added and then the plugin
    // enters a custom loop, applying appropriate commands. In this loop, there is only a key shortcut for "next"
    // (<C-N>) and no support for "next non-word". The loop will check the script wide word boundary flag and call
    // "new" again.
    // We might want to consider updating the mappings to handle the difference between normal mode and visual mode

  if (!editor.inVisualMode) {
      // TODO: Handle multiple cursors in normal mode
      // E.g. start a multiple cursor session, clear selection and add a new cursor
      // TODO: New cursor should be based on text at the last visual selection marks
      // (Marks are not set until we come out of visual mode, so might need to use a work around)
      // TODO: Make sure we can handle manually added cursors
      if (editor.carets().size > 1) return

      val selection = selectWordUnderCaret(editor, editor.primaryCaret())

      // The handler is specific to whole/not-whole word, but the next occurrence is based on the initial call
      editor.session.wholeWord = whole
      editor.session.lastSelection = selection
    } else {
      // vim-multiple-cursors is case sensitive, so it's ok to use a case sensitive set here
      val patterns = mutableSetOf<String>()
      val newOffsets = arrayListOf<Int>()

      // If multiple lines are selected, we want to convert the selection to multiple carets, positioned at the start
      // of each line
      for (caret in editor.carets()) {
        val selectedText = caret.selectedText(editor) ?: return

        // Keep a track of the selected text, we'll check it later
        patterns.add(selectedText)

        val minOffset = min(caret.selectionEnd, caret.selectionStart)
        var maxOffset = max(caret.selectionEnd, caret.selectionStart)

        // As the last offset appears after the new line character, technically it's placed on the next line.
        if (selectedText.lastOrNull() == '\n') {
          maxOffset -= 1
        }
        val start = editor.offsetToBufferPosition(minOffset).line
        val end = editor.offsetToBufferPosition(maxOffset).line
        val lines = end - start
        if (lines > 0) {
          val selectionStart = min(caret.selectionStart, caret.selectionEnd)
          val startPosition = editor.offsetToBufferPosition(selectionStart)
          for (line in startPosition.line + 1..startPosition.line + lines) {
            newOffsets.add(editor.bufferPositionToOffset(BufferPosition(line, startPosition.column)))
          }
          caret.moveToOffset(selectionStart)
        }
      }

      if (newOffsets.size > 0) {
        editor.exitVisualMode()
        newOffsets.forEach { editor.addCaret(it) ?: return@forEach }
        return
      }

      // All the carets should be selecting the same text. If they're not, then it's likely they have been added
      // by some other means, so we shouldn't continue with the VIM behaviour
      if (patterns.size > 1) return

      // If we are adding the first new cursor, based on the current selection, we do a non-whole word match (ignoring
      // the value passed to the handler during mapping. We should fix the mappings for visual mode). If we're adding
      // a second or subsequent cursor, we should use the boundary matching parameter used to start the session.
      // But all we know right now is that we're in visual mode, and we have a selection. We cannot tell if the
      // selection has been added by the user (we're trying to add the first cursor) or it was added when we added the
      // first/previous cursor (we're about to add a second/subsequent cursor).
      // So, we keep track of the selection used to add the previous cursor. If it matches the current select, we know
      // we're about to add a second cursor (so use the saved word boundary flag). If it does not match, something's
      // changed, so we're adding a first cursor based on the current selection (set a new non-whole word flag)
      val currentSelection = TextRange(editor.primaryCaret().selectionStart, editor.primaryCaret().selectionEnd)
      var lastSelection = editor.session.lastSelection
      val wholeWord = if (lastSelection != null && lastSelection.startOffset == currentSelection.startOffset &&
        lastSelection.endOffset == currentSelection.endOffset
      ) {
        editor.session.wholeWord ?: false
      } else {
        false
      }
      editor.session.wholeWord = wholeWord
      lastSelection = currentSelection

      // Always work on the text in the last visual selection range, so we work with any changed text, even if it's no
      // longer selected
      val pattern = editor.getText(lastSelection)

      val primaryCaret = editor.primaryCaret()
      val nextOffset = findNextOccurrence(editor, primaryCaret.offset, pattern, wholeWord)
      if (nextOffset != -1) {
        editor.carets().forEach {
          if (it.selectionStart == nextOffset) {
            noMoreMatches()
            return
          }
        }

        val caret = editor.addCaret(nextOffset) ?: return
        editor.session.lastSelection = selectText(editor, caret, pattern, nextOffset)
      } else {
        noMoreMatches()
      }
    }
  }
}

private class AllOccurrencesHandler(val whole: Boolean = true) : WriteActionHandler() {
  override fun executeInWriteAction(editor: VimEditor, context: ExecutionContext) {
    if (editor.carets().size > 1) return

    val primaryCaret = editor.primaryCaret()
    val text = if (editor.inVisualMode) {
      primaryCaret.selectedText(editor) ?: return
    } else {
      val range =
        injector.searchHelper.findWordAtOrFollowingCursor(editor, primaryCaret, isBigWord = false) ?: return
      if (range.startOffset > primaryCaret.offset) return
      editor.getText(range)
    }

    if (!editor.inVisualMode) {
      enterVisualMode(editor)
    }

    // Note that ignoreCase is not overridden by the `\C` in the pattern
    val pattern = makePattern(text, whole)
    val matches = injector.searchHelper.findAll(editor, pattern, 0, -1, false)
    for (match in matches) {
      if (match.contains(editor.primaryCaret().offset)) {
        editor.primaryCaret().moveToOffset(match.startOffset)
        selectText(editor, editor.primaryCaret(), text, match.startOffset)
      } else {
        val caret = editor.addCaret(match.startOffset) ?: return
        selectText(editor, caret, text, match.startOffset)
      }
    }
  }
}

private class SkipOccurrenceHandler : WriteActionHandler() {
  override fun executeInWriteAction(editor: VimEditor, context: ExecutionContext) {
    val primaryCaret = editor.primaryCaret()
    val selectedText = primaryCaret.selectedText(editor) ?: return

    val nextOffset =
      findNextOccurrence(editor, primaryCaret.offset, selectedText, editor.session.wholeWord ?: false)
    if (nextOffset != -1) {
      editor.carets().forEach {
        if (it.selectionStart == nextOffset) {
          noMoreMatches()
          return
        }
      }

      editor.primaryCaret().moveToOffset(nextOffset)
      selectText(editor, editor.primaryCaret(), selectedText, nextOffset)
    }
  }
}

private class RemoveOccurrenceHandler : WriteActionHandler() {
  override fun executeInWriteAction(editor: VimEditor, context: ExecutionContext) {
    val caret = editor.primaryCaret()
    if (caret.selectedText(editor) == null) return
    if (editor.carets().size > 1) {
      editor.removeCaret(caret)
    } else {
      editor.exitVisualMode()
    }
    injector.scroll.scrollCaretIntoView(editor)
  }
}

/** What the caret has selected, or null when it has selected nothing. */
private fun VimCaret.selectedText(editor: VimEditor): String? =
  if (!hasSelection()) null else editor.getText(TextRange(selectionStart, selectionEnd))

private fun noMoreMatches() {
  injector.messages.showStatusBarMessage(null, injector.messages.message("multiple-cursors.message.no.more.matches"))
}

private fun selectText(editor: VimEditor, caret: VimCaret, text: String, offset: Int): TextRange? {
  if (text.isEmpty()) return null
  caret.vimSetSelection(offset, offset + text.length - 1, true)
  injector.scroll.scrollCaretIntoView(editor)
  // Read back off the editor rather than off `caret`: `vimSetSelection` may move the caret, and a
  // moved caret is a *new* `VimCaret` on this side - the one in hand still describes where it was.
  val moved = editor.carets().firstOrNull { it.selectionStart == offset } ?: return null
  return TextRange(moved.selectionStart, moved.selectionEnd)
}

private fun selectWordUnderCaret(editor: VimEditor, caret: VimCaret): TextRange? {
  // TODO: I think vim-multiple-cursors uses a text object rather than the star operator
  val range =
    injector.searchHelper.findWordAtOrFollowingCursor(editor, caret, isBigWord = false) ?: return null
  if (range.startOffset > caret.offset) return null

  enterVisualMode(editor)

  // Select the word under the caret, moving the caret to the end of the selection
  caret.vimSetSelection(range.startOffset, range.endOffsetInclusive, true)
  val moved = editor.carets().firstOrNull { it.selectionStart == range.startOffset } ?: return null
  return TextRange(moved.selectionStart, moved.selectionEnd)
}

private fun enterVisualMode(editor: VimEditor) {
  // We need to reset the key handler to make sure we pick up the fact that we're in visual mode
  injector.visualMotionGroup.enterVisualMode(editor, SelectionType.CHARACTER_WISE)
  KeyHandler.getInstance().reset(editor)
}

private fun findNextOccurrence(editor: VimEditor, startOffset: Int, text: String, whole: Boolean): Int {
  val searchOptions = enumSetOf(SearchOptions.WHOLE_FILE)
  if (injector.options(editor).wrapscan) {
    searchOptions.add(SearchOptions.WRAP)
  }

  return injector.searchHelper.findPattern(
    editor,
    makePattern(text, whole),
    startOffset,
    1,
    searchOptions
  )?.startOffset ?: -1
}

private fun makePattern(text: String, whole: Boolean): String {
  // Pattern is "very nomagic" (ignore regex chars) and "force case sensitive". This is vim-multiple-cursors behaviour
  // In very nomagic mode, only backslash has special meaning, so we need to escape backslashes in the text
  val escapedText = text.replace("\\", "\\\\")
  return "\\V\\C" + if (whole) "\\<$escapedText\\>" else escapedText
}
