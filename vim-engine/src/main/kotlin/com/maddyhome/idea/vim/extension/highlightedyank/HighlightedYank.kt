/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.highlightedyank

import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.intellij.vim.api.models.Color
import com.intellij.vim.api.models.HighlightId
import com.maddyhome.idea.vim.api.ImmutableVimCaret
import com.maddyhome.idea.vim.api.ScheduledTask
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.ModeChangeListener
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.common.VimYankListener
import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString

public const val HIGHLIGHTED_YANK: String = "highlightedyank"

public const val DEFAULT_HIGHLIGHT_DURATION: Int = 300

private const val HIGHLIGHT_DURATION_VARIABLE_NAME = "highlightedyank_highlight_duration"
private const val HIGHLIGHT_COLOR_VARIABLE_NAME = "highlightedyank_highlight_color"
private const val HIGHLIGHT_FOREGROUND_COLOR_VARIABLE_NAME = "highlightedyank_highlight_foreground_color"

/**
 * Port of [vim-highlightedyank](https://github.com/machakann/vim-highlightedyank): the text a yank
 * covered flashes, so you can see what you took.
 *
 * ```vim
 * let g:highlightedyank_highlight_duration = "1000"        " milliseconds; -1 makes it persistent
 * let g:highlightedyank_highlight_color = "rgba(160, 160, 160, 155)"
 * let g:highlightedyank_highlight_foreground_color = "rgba(0, 0, 0, 255)"
 * ```
 *
 * From the plugin's own docs: when new text is yanked, or the user starts editing, the old
 * highlighting is deleted.
 *
 * ## What moving it needed
 *
 * Two host capabilities, and neither was the highlighting. `injector.highlightingService` already
 * built exactly the attributes this used to build for itself - foreground, background, the caret
 * colour, `EffectType.SEARCH_MATCH` - so the drawing came across unchanged. What did not exist was
 * [ScheduledTask]: a flash that goes away is a flash *and a timer*, and the engine had no way to ask
 * for one. IntelliJ's `Alarm` and VS Code's `setTimeout` are the same idea, and `VimApplication`
 * names it now.
 *
 * The other was the default colour. This used to read `TEXT_SEARCH_RESULT_ATTRIBUTES` from
 * IntelliJ's colour scheme, and that is not a value a `Color` can carry - VS Code's equivalent is a
 * theme *key* it resolves when it paints. So the choice belongs to the host, which is what
 * `addSearchHighlighter` is for; the extension asks for "a search match" and names a colour only
 * when the user did.
 */
@VimPlugin(name = HIGHLIGHTED_YANK)
public fun VimInitApi.init(): Unit = registerHighlightedYank()

/** The one instance the listeners are registered with, so [disposeHighlightedYank] can remove it. */
private val listener = HighlightedYankListener()

public fun registerHighlightedYank() {
  injector.listenersNotifier.modeChangeListeners.add(listener)
  injector.listenersNotifier.yankListeners.add(listener)
}

/**
 * Undoes [registerHighlightedYank].
 *
 * Named rather than left to the loader's owner-based teardown for the same reason `yankring`'s is:
 * these listeners go straight onto `listenersNotifier` rather than through the thin API's listener
 * scope, so nothing removes them by owner.
 */
public fun disposeHighlightedYank() {
  injector.listenersNotifier.modeChangeListeners.remove(listener)
  injector.listenersNotifier.yankListeners.remove(listener)
  listener.clearYankHighlighters()
}

/** Takes the current highlight off without unregistering anything. See [onYankHighlighted]. */
public fun clearHighlightedYank(): Unit = listener.clearYankHighlighters()

/**
 * Called once a yank has been highlighted, for a host that has to re-arm something each time.
 *
 * IntelliJ is the one that does. IdeaVim can be switched off from its status-bar icon without the
 * *extension* being disabled, and a highlight left on screen would outlive it - so the plugin
 * clears highlights on its on/off disposable. There is no callback for being switched back on, and
 * a disposable that has fired is spent, so it has to be registered again; this is where the plugin
 * hears that there is something worth registering for. See VIM-3419.
 */
public var onYankHighlighted: (() -> Unit)? = null

private class HighlightedYankListener : VimYankListener, ModeChangeListener {

  private var pending: ScheduledTask = ScheduledTask.NONE
  private var lastEditor: VimEditor? = null
  private val highlights = mutableListOf<HighlightId>()

  override fun yankPerformed(caretToRange: Map<ImmutableVimCaret, TextRange>) {
    // From the plugin's docs: a new yank replaces the old highlighting rather than adding to it.
    clearYankHighlighters()
    val editor = caretToRange.keys.firstOrNull()?.editor ?: return
    lastEditor = editor

    val background = userColor(HIGHLIGHT_COLOR_VARIABLE_NAME)
    val foreground = userColor(HIGHLIGHT_FOREGROUND_COLOR_VARIABLE_NAME)
    for (range in caretToRange.values) {
      for (i in 0 until range.size()) {
        highlights += injector.highlightingService.addSearchHighlighter(
          editor,
          range.startOffsets[i],
          range.endOffsets[i],
          background,
          foreground,
        )
      }
    }

    // From the plugin's docs: a negative number makes the highlight persistent.
    val timeout = highlightDuration()
    if (timeout >= 0) {
      pending = injector.application.schedule(timeout) { clearYankHighlighters() }
    }

    onYankHighlighted?.invoke()
  }

  /** Editing takes the highlight off, which is the other half of what the plugin documents. */
  override fun modeChanged(editor: VimEditor, oldMode: Mode) {
    if (editor.mode !is Mode.INSERT) return
    clearYankHighlighters()
  }

  fun clearYankHighlighters() {
    pending.cancel()
    pending = ScheduledTask.NONE
    val editor = lastEditor
    if (editor != null) {
      highlights.forEach { injector.highlightingService.removeHighlighter(editor, it) }
    }
    highlights.clear()
    lastEditor = null
  }

  private fun highlightDuration(): Int = variable(HIGHLIGHT_DURATION_VARIABLE_NAME, DEFAULT_HIGHLIGHT_DURATION) {
    // `toVimNumber` answers 0 for a string that is not a number, which would silently mean "no
    // flash at all". Going through `toInt` instead makes it throw, so the user hears about it.
    when (it) {
      is VimString -> it.value.toInt()
      else -> it.toVimNumber().value
    }
  }

  private fun userColor(variableName: String): Color? =
    variable<Color?>(variableName, null) { parseRgbaColor(it.toVimString().value) }

  /**
   * `rgba(r, g, b, a)`, which is the form the plugin's own documentation uses.
   *
   * A `Color` is a hex string, and `#RRGGBBAA` is what both hosts want anyway - IntelliJ takes the
   * four components back off it, and CSS reads it directly.
   */
  private fun parseRgbaColor(colorString: String): Color {
    val rgba = colorString
      .substringAfter('(', "")
      .filter { it != ')' && !it.isWhitespace() }
      .split(',')
      .map { it.toInt() }

    require(rgba.size == 4 && rgba.all { it in 0..255 }) {
      "Invalid RGBA values. Each component must be between 0 and 255"
    }
    return Color(rgba[0], rgba[1], rgba[2], rgba[3])
  }

  /**
   * Reads a `g:` variable, and says so rather than failing quietly when it cannot be read.
   *
   * A config runs with `indicateErrors = false`, so a `let` that names nonsense produces no error
   * of its own; without this message a mistyped colour would simply mean no highlight, with nothing
   * to look at.
   */
  private fun <T> variable(name: String, default: T, read: (VimDataType) -> T): T {
    val value = injector.variableService.getGlobalVariableValue(name) ?: return default
    return try {
      read(value)
    } catch (e: Exception) {
      injector.messages.showStatusBarMessage(
        null,
        injector.messages.message("highlightedyank.error.invalid.value.of.0.1", "g:$name", e.message ?: ""),
      )
      default
    }
  }
}
