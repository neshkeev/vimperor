/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.sneak

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.intellij.vim.api.models.HighlightId
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.ScheduledTask
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getMappingInfo
import com.maddyhome.idea.vim.api.hasMapTo
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.options
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtensionFacade
import com.maddyhome.idea.vim.extension.VimExtensionFacade.executeNormalWithoutMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMapping
import com.maddyhome.idea.vim.extension.readCharacters
import com.maddyhome.idea.vim.helper.StrictMode
import com.maddyhome.idea.vim.helper.enumSetOf
import com.maddyhome.idea.vim.key.MappingOwner


private const val DEFAULT_HIGHLIGHT_DURATION_SNEAK = 300

/**
 * `vim-sneak`: `s{char}{char}` jumps to the next occurrence of two characters, `S` backwards, and
 * `;` and `,` repeat.
 *
 * By [Mikhail Levchenko](https://github.com/Mishkun). Original repository:
 * https://github.com/Mishkun/ideavim-sneak
 *
 * ## What moving it needed
 *
 * The two characters. This asked for them with `injector.keyGroup.getChar`, which blocks, and there
 * is no blocking read on a runtime with one thread - see [readCharacters], which is the same
 * question asked the way the engine's modal input already answers `:s///c`. The cost is that `s`
 * returns before it knows what it is looking for, and the jump happens on the second keystroke
 * after it.
 *
 * The highlight and its timeout were the other half, and both were built for `highlightedyank` one
 * commit earlier: `injector.highlightingService` and `injector.application.schedule`.
 */
@VimPlugin(name = SNEAK)
public fun VimInitApi.init(): Unit = registerSneak()

/** Public because the plugin's extension-point adapter names it too. */
public const val SNEAK: String = "sneak"

/** Cleared by `set nosneak`, and by the plugin's adapter, so no highlight outlives the extension. */
public fun disposeSneak(): Unit = highlightHandler.clearAllSneakHighlighters()

private val highlightHandler = HighlightHandler()

public fun registerSneak() {
  val owner = MappingOwner.Plugin.get(SNEAK)
  val _highlightHandler = highlightHandler

  // Note that vim-sneak uses `z` for Op-pending and `Z` for Visual and Op-pending for compatibility with surround.
  // See VIM-3330 and VIM-4225
  mapToFunctionAndProvideKeys("s", SneakHandler(_highlightHandler, Direction.FORWARD), MappingMode.N)
  mapToFunctionAndProvideKeys("s", SneakHandler(_highlightHandler, Direction.FORWARD), MappingMode.X)
  mapToFunctionAndProvideKeys("z", SneakHandler(_highlightHandler, Direction.FORWARD), MappingMode.O)

  mapToFunctionAndProvideKeys("S", SneakHandler(_highlightHandler, Direction.BACKWARD), MappingMode.N)
  mapToFunctionAndProvideKeys("Z", SneakHandler(_highlightHandler, Direction.BACKWARD), MappingMode.X)
  mapToFunctionAndProvideKeys("Z", SneakHandler(_highlightHandler, Direction.BACKWARD), MappingMode.O)

  // workaround to support ; and , commands
  mapToFunctionAndProvideKeys("f", SneakMemoryHandler("f"), MappingMode.NXO)
  mapToFunctionAndProvideKeys("F", SneakMemoryHandler("F"), MappingMode.NXO)
  mapToFunctionAndProvideKeys("t", SneakMemoryHandler("t"), MappingMode.NXO)
  mapToFunctionAndProvideKeys("T", SneakMemoryHandler("T"), MappingMode.NXO)

  mapToFunctionAndProvideKeys(";", SneakRepeatHandler(_highlightHandler, RepeatDirection.IDENTICAL), MappingMode.NXO)
  mapToFunctionAndProvideKeys(",", SneakRepeatHandler(_highlightHandler, RepeatDirection.REVERSE), MappingMode.NXO)
}

private class SneakHandler(
  private val highlightHandler: HighlightHandler,
  private val direction: Direction,
) : ExtensionHandler {
  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    // Returns before it knows what it is looking for; the jump happens in the callback, two
    // keystrokes later. See `readCharacters` for why there is no way to wait here.
    readCharacters(2, editor, context) { typed ->
      val charone = typed[0]
      val chartwo = typed[1]
      val range = Util.jumpTo(editor, charone, chartwo, direction)
      range?.let { highlightHandler.highlightSneakRange(editor, range) }
      Util.lastSymbols = typed
      Util.lastSDirection = direction
    }
  }
}

/**
 * This class acts as proxy for normal find commands because we need to update [Util.lastSDirection]
 */
private class SneakMemoryHandler(private val char: String) : ExtensionHandler {
  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    Util.lastSDirection = null
    executeNormalWithoutMapping(injector.parser.parseKeys(char), editor)
  }
}

private class SneakRepeatHandler(
  private val highlightHandler: HighlightHandler,
  private val direction: RepeatDirection,
) : ExtensionHandler {
  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    val lastSDirection = Util.lastSDirection
    if (lastSDirection != null) {
      val (charone, chartwo) = Util.lastSymbols.toList()
      val jumpRange = Util.jumpTo(editor, charone, chartwo, direction.map(lastSDirection))
      jumpRange?.let { highlightHandler.highlightSneakRange(editor, jumpRange) }
    } else {
      executeNormalWithoutMapping(injector.parser.parseKeys(direction.symb), editor)
    }
  }
}

private object Util {
  var lastSDirection: Direction? = null
  var lastSymbols: String = ""
  fun jumpTo(editor: VimEditor, charone: Char, chartwo: Char, sneakDirection: Direction): TextRange? {
    val caret = editor.primaryCaret()
    val position = caret.offset
    val chars = editor.text()
    val foundPosition = sneakDirection.findBiChar(editor, chars, position, charone, chartwo)
    if (foundPosition != null) {
      editor.primaryCaret().moveToOffset(foundPosition)
    }
    injector.scroll.scrollCaretIntoView(editor)
    return foundPosition?.let { TextRange(foundPosition, foundPosition + 2) }
  }
}

private enum class Direction(val offset: Int) {
  FORWARD(1) {
    override fun findBiChar(
      editor: VimEditor,
      charSequence: CharSequence,
      position: Int,
      charone: Char,
      chartwo: Char,
    ): Int? {
      for (i in (position + offset) until charSequence.length - 1) {
        if (matches(editor, charSequence, i, charone, chartwo)) {
          return i
        }
      }
      return null
    }
  },
  BACKWARD(-1) {
    override fun findBiChar(
      editor: VimEditor,
      charSequence: CharSequence,
      position: Int,
      charone: Char,
      chartwo: Char,
    ): Int? {
      for (i in (position + offset) downTo 0) {
        if (matches(editor, charSequence, i, charone, chartwo)) {
          return i
        }
      }
      return null
    }

  };

  abstract fun findBiChar(
    editor: VimEditor,
    charSequence: CharSequence,
    position: Int,
    charone: Char,
    chartwo: Char,
  ): Int?

  fun matches(
    editor: VimEditor,
    charSequence: CharSequence,
    charPosition: Int,
    charOne: Char,
    charTwo: Char,
  ): Boolean {
    var match = charSequence[charPosition].equals(charOne, ignoreCase = injector.options(editor).ignorecase) &&
      charSequence[charPosition + 1].equals(charTwo, ignoreCase = injector.options(editor).ignorecase)

    if (injector.options(editor).ignorecase && injector.options(editor).smartcase) {
      if (charOne.isUpperCase() || charTwo.isUpperCase()) {
        match = charSequence[charPosition].equals(charOne, ignoreCase = false) &&
          charSequence[charPosition + 1].equals(charTwo, ignoreCase = false)
      }
    }
    return match
  }
  }

private enum class RepeatDirection(val symb: String) {
  IDENTICAL(";") {
  override fun map(direction: Direction): Direction = direction
  },
  REVERSE(",") {
  override fun map(direction: Direction): Direction = when (direction) {
    Direction.FORWARD -> Direction.BACKWARD
    Direction.BACKWARD -> Direction.FORWARD
  }
  };

  abstract fun map(direction: Direction): Direction
}

private class HighlightHandler {
  private var editor: VimEditor? = null
  private val sneakHighlighters: MutableSet<HighlightId> = mutableSetOf()
  private var timer: ScheduledTask = ScheduledTask.NONE

  fun highlightSneakRange(editor: VimEditor, range: TextRange) {
    clearAllSneakHighlighters()

    this.editor = editor

    if (range.isMultiple) {
      for (i in 0 until range.size()) {
        highlightSingleRange(editor, range.startOffsets[i]..range.endOffsets[i])
      }
    } else {
      highlightSingleRange(editor, range.startOffset..range.endOffset)
    }
  }

  fun clearAllSneakHighlighters() {
    timer.cancel()
    timer = ScheduledTask.NONE
    val editor = this.editor
    if (editor == null) {
      if (sneakHighlighters.isNotEmpty()) StrictMode.fail("Highlighters without an editor")
    } else {
      sneakHighlighters.forEach { injector.highlightingService.removeHighlighter(editor, it) }
    }

    sneakHighlighters.clear()
  }

  private fun highlightSingleRange(editor: VimEditor, range: ClosedRange<Int>) {
    sneakHighlighters.add(
      injector.highlightingService.addSearchHighlighter(editor, range.start, range.endInclusive, null, null),
    )
    setClearHighlightRangeTimer()
  }

  private fun setClearHighlightRangeTimer() {
    timer.cancel()
    timer = injector.application.schedule(DEFAULT_HIGHLIGHT_DURATION_SNEAK) { clearAllSneakHighlighters() }
  }
}

/**
 * Map some <Plug>(keys) command to given handler
 *  and create mapping to <Plug>(prefix)[keys]
 */
private fun mapToFunctionAndProvideKeys(
  keys: String, handler: ExtensionHandler, mappingModes: MutableSet<MappingMode>,
) {
  val owner = MappingOwner.Plugin.get(SNEAK)
  VimExtensionFacade.putExtensionHandlerMapping(
    mappingModes,
    injector.parser.parseKeys(command(keys)),
    owner,
    handler,
    false
  )
  VimExtensionFacade.putExtensionHandlerMapping(
    mappingModes,
    injector.parser.parseKeys(commandFromOriginalPlugin(keys)),
    owner,
    handler,
    false
  )

  // This is a combination to meet the following requirements:
  //  - Now we should support mappings from sneak `Sneak_s` and mappings from the previous version of the plugin `(sneak-s)`
  //  - The shortcut should not be registered if any of these mappings is overridden in .ideavimrc
  //  - The shortcut should not be registered if some other shortcut for this key exists
  val fromKeys = injector.parser.parseKeys(keys)
  val filteredModes = mappingModes.filterNotTo(mutableSetOf()) {
    injector.keyGroup.hasMapTo(command(keys), enumSetOf(it))
  }
  val filteredModes2 = mappingModes.filterNotTo(mutableSetOf()) {
    injector.keyGroup.hasMapTo(commandFromOriginalPlugin(keys), enumSetOf(it))
  }
  val filteredFromModes = mappingModes.filterNotTo(mutableSetOf()) { mode ->
    injector.keyGroup.getMappingInfo(fromKeys, mode) != null
  }

  val doubleFiltered = mappingModes
    .filter { it in filteredModes2 && it in filteredModes && it in filteredFromModes }
    .toSet()
  putKeyMapping(doubleFiltered, fromKeys, owner, injector.parser.parseKeys(command(keys)), true)
  putKeyMapping(
    doubleFiltered,
    fromKeys,
    owner,
    injector.parser.parseKeys(commandFromOriginalPlugin(keys)),
    true
  )
}

private fun command(keys: String) = "<Plug>(sneak-$keys)"
private fun commandFromOriginalPlugin(keys: String) = "<Plug>Sneak_$keys"
