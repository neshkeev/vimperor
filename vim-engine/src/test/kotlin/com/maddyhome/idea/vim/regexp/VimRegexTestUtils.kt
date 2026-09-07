/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.regexp

import com.maddyhome.idea.vim.api.BufferPosition
import com.maddyhome.idea.vim.host.TestVimCaret
import com.maddyhome.idea.vim.host.TestVimEditor
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.mark.VimMark
import kotlin.test.assertNotEquals
import kotlin.test.fail

internal object VimRegexTestUtils {

  const val START: String = "<start>"
  const val END: String = "<end>"
  const val CARET: String = "<caret>"
  const val VISUAL_START = "<vstart>"
  const val VISUAL_END = "<vend>"
  private const val MARK = "<mark.>"
  fun MARK(mark: Char): CharSequence {
    return "<mark$mark>"
  }

  fun mockEditorFromText(text: CharSequence): VimEditor {
    val cleanText = getTextWithoutEditorTags(getTextWithoutRangeTags(text))
    val lines = cleanText.split("\n").map { it + "\n" }


    val textWithoutRangeTags = getTextWithoutRangeTags(text)

    val carets = mutableListOf<VimCaret>()
    val textWithOnlyCarets = getTextWithoutVisualTags(getTextWithoutMarkTags(textWithoutRangeTags))
    val textWithOnlyVisuals = getTextWithoutCaretTags(getTextWithoutMarkTags(textWithoutRangeTags))
    val textWithOnlyMarks = getTextWithoutCaretTags(getTextWithoutVisualTags(textWithoutRangeTags))

    val visualStart = textWithOnlyVisuals.indexOf(VISUAL_START)
    val visualEnd = if (visualStart >= 0) textWithOnlyVisuals.indexOf(VISUAL_END) - VISUAL_START.length
    else -1

    // Marks are recorded as buffer positions, so offsets need converting - which is a question
    // about the text alone, and an editor with no carets answers it.
    val positions = TestVimEditor(cleanText.toString(), emptyList())
    val marks = mutableMapOf<Char, BufferPosition>()

    var nextMark = MARK.toRegex().find(textWithOnlyMarks)
    var offset = 0
    while (nextMark != null) {
      val nextMarkIndex = nextMark.range.first - offset
      offset += MARK.length
      marks[nextMark.value[5]] = positions.offsetToBufferPosition(nextMarkIndex)
      nextMark = nextMark.next()
    }

    var nextCaretIndex = textWithOnlyCarets.indexOf(CARET)
    offset = 0

    while (nextCaretIndex != -1) {
      carets.add(mockCaret(nextCaretIndex - offset, Pair(visualStart, visualEnd), marks))
      nextCaretIndex = textWithOnlyCarets.indexOf(CARET, nextCaretIndex + CARET.length)
      offset += CARET.length
    }

    // If the text carries no caret tag, Vim still has a caret: put it at the start.
    val allCarets = carets.ifEmpty { listOf(mockCaret(0, Pair(visualStart, visualEnd), marks)) }
    return TestVimEditor(cleanText.toString(), allCarets)
  }

  fun mockEditor(text: CharSequence, carets: List<VimCaret>): VimEditor {
    assertNotEquals(0, carets.size, "Expected at least one caret.")
    val cleanText = getTextWithoutEditorTags(getTextWithoutRangeTags(text))
    val lines = cleanText.split("\n").map { it + "\n" }

    return TestVimEditor(cleanText.toString(), carets)
  }

  fun mockCaret(
    caretOffset: Int,
    visualOffset: Pair<Int, Int> = Pair(-1, -1),
    marks: Map<Char, BufferPosition> = emptyMap(),
  ): VimCaret {
    return TestVimCaret(caretOffset, visualOffset.first, visualOffset.second, marks)
  }


  private fun getTextWithoutCaretTags(text: CharSequence): CharSequence {
    return text.replace(CARET.toRegex(), "")
  }

  private fun getTextWithoutVisualTags(text: CharSequence): CharSequence {
    return text.replace("$VISUAL_START|$VISUAL_END".toRegex(), "")
  }

  private fun getTextWithoutMarkTags(text: CharSequence): CharSequence {
    return text.replace(MARK.toRegex(), "")
  }

  private fun getTextWithoutEditorTags(text: CharSequence): CharSequence {
    return getTextWithoutMarkTags(
      getTextWithoutVisualTags(
        getTextWithoutCaretTags(
          text
        )
      )
    )
  }


  fun getMatchRanges(text: CharSequence): List<TextRange> {
    val textWithoutEditorTags = getTextWithoutEditorTags(text)
    val matchRanges = mutableListOf<TextRange>()
    var offset = 0
    var oldOffset = 0

    var startIndex = textWithoutEditorTags.indexOf(START)
    while (startIndex != -1) {
      val endIndex = textWithoutEditorTags.indexOf(END, startIndex + START.length)
      if (endIndex != -1) {
        offset += START.length
        matchRanges.add(TextRange(startIndex - oldOffset, endIndex - offset))
        startIndex = textWithoutEditorTags.indexOf(START, endIndex + END.length)
        offset += END.length
        oldOffset = offset
      } else {
        fail("Please provide the same number of START and END tags!")
      }
    }
    return matchRanges
  }

  // `StringBuilder.delete` is a JVM-only member; removing fixed literals needs no builder anyway.
  private fun getTextWithoutRangeTags(text: CharSequence): CharSequence =
    text.toString().replace(START, "").replace(END, "")
}