/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.extension.easymotion

import com.github.neshkeev.vimperor.extension.readKeys
import com.github.neshkeev.vimperor.label.JumpLabel
import com.intellij.vim.api.VimInitApi
import com.intellij.vim.api.VimPlugin
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.options
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.MotionType
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing
import com.maddyhome.idea.vim.key.MappingOwner
import com.maddyhome.idea.vim.key.VimKeyCodes
import com.maddyhome.idea.vim.key.VimKeyStroke
import com.maddyhome.idea.vim.options.helpers.LangMapOptionHelper
import com.maddyhome.idea.vim.state.mode.Mode
import kotlin.math.abs

/**
 * `vim-easymotion`: every place a motion could go, labelled, and the next key jumps to one.
 *
 * `<Leader><Leader>w` labels each word start on screen, and typing a label moves there.
 * `d<Leader><Leader>w` deletes to it, and in Visual mode it extends the selection. The seventeen
 * default mappings are vim-easymotion's own - IdeaVim-EasyMotion kept the same ones - and the
 * `<Plug>(easymotion-*)` names cover the rest.
 *
 * ## Written here, not ported
 *
 * IdeaVim's easymotion is a separate plugin, IdeaVim-EasyMotion, built on the AceJump plugin, and
 * both are GPL-3.0. This extension is MIT like the rest of the fork, so neither could be brought
 * across. It follows vim-easymotion itself, which is MIT: its documentation for the behaviour and
 * the mappings, and its grouping algorithm - see [groupTargets]. Labels are drawn the way VSCodeVim's
 * easymotion draws them, also MIT; see the VS Code host's `VsCodeJumpLabelDisplay`.
 *
 * ## What it needed from the engine
 *
 *  - **Somewhere to draw.** A label is a letter over a letter, which neither the highlighting
 *    service nor `:sign` can do: [com.github.neshkeev.vimperor.label.VimJumpLabelDisplay].
 *  - **One prompt, many keys.** A find motion reads its character and then its labels, which narrow
 *    as each key is typed. `readKeys` grew `onProgress` to redraw between keys and `onCancel` for
 *    `<Esc>`, so this never opens a second prompt - `readKeys` records that as losing a keystroke.
 *  - **Lines, under an operator.** An operator waiting on an extension's motion sees only where the
 *    caret went, so `d` over `j` came out characterwise: `ExtensionHandler.WithCallback.isLinewiseMotion`.
 *
 * Operators themselves were already reachable. IdeaVim built `ExternalActionHandler` for
 * IdeaVim-EasyMotion, and nothing in this fork had used it.
 *
 * ## Where it differs from vim-easymotion
 *
 *  - **Labels are typed on whichever layout is active.** `'keyboardlayout'` and `'langmap'` apply to
 *    them - `ы` types `s` on a Russian layout - because a label is a command key. The character a
 *    find motion searches for is not translated, because that is text.
 *  - The `iskeyword-*` motions are the plain ones: this engine's word motions already honour
 *    `'iskeyword'`.
 *  - **Not implemented:** the multi-character finds (`s2`, `sn` and their kin), `repeat`, `next`,
 *    `prev`, `jumptoanywhere`, the `line*` motions and `overwin-*`. `.` after an easymotion operator
 *    does not repeat the jump.
 *  - Labels past the right edge of a window that does not wrap cannot be left out, because VS Code
 *    says which lines are on screen but not which columns.
 */
@VimPlugin(name = EASYMOTION)
public fun VimInitApi.init(): Unit = registerEasyMotion()

/** Public because the VS Code host names it. */
public const val EASYMOTION: String = "easymotion"

/** What the default mappings hang off, so `map <Leader> <Plug>(easymotion-prefix)` can move them. */
private const val PREFIX = "<Plug>(easymotion-prefix)"

/** vim-easymotion's default: home row first, and the keys easiest to type twice last. */
private const val DEFAULT_KEYS = "asdghklqwertyuiopzxcvbnmfj;"

private const val CANCELLED = "EasyMotion: Cancelled"
private const val NO_TARGET = "EasyMotion: No target"
private const val TOO_FEW_KEYS = "EasyMotion: g:EasyMotion_keys needs at least two different keys"

public fun registerEasyMotion() {
  val owner = MappingOwner.Plugin.get(EASYMOTION)
  val prefix = injector.parser.parseKeys(PREFIX)
  // Read once, at load, which is when vim-easymotion reads it too - and why `let mapleader` has to
  // come before `set easymotion`, since `<Leader>` is resolved as the mappings are made.
  val defaults = globalFlag("EasyMotion_do_mapping", true)

  for (motion in Motion.entries) {
    val plug = injector.parser.parseKeys("<Plug>(easymotion-${motion.plug})")
    putExtensionHandlerMapping(MappingMode.NXO, plug, owner, EasyMotionHandler(motion), false)
    val keys = motion.defaultKeys
    if (defaults && keys != null) {
      putKeyMappingIfMissing(MappingMode.NXO, prefix + injector.parser.parseKeys(keys), owner, plug, true)
    }
  }
  if (defaults) {
    putKeyMappingIfMissing(MappingMode.NXO, injector.parser.parseKeys("<Leader><Leader>"), owner, prefix, true)
  }
}

/** The editor showing labels, so switching the extension off mid-jump can take them down. */
private var labelled: VimEditor? = null

public fun disposeEasyMotion() {
  labelled?.let { injector.jumpLabelDisplay.clearLabels(it) }
  labelled = null
}

private enum class Direction { FORWARD, BACKWARD, BOTH }

private enum class Kind(val readsCharacter: Boolean = false) {
  WORD_START,
  WORD_END,

  /** `j` and `k`: the first non-blank, or the same column when `g:EasyMotion_startofline` is 0. */
  LINE,
  LINE_FIRST_NON_BLANK,
  LINE_END,
  FIND(readsCharacter = true),

  /** The character before a match going forwards, and after one going backwards. */
  TILL(readsCharacter = true),
  SEARCH,
}

/**
 * Every `<Plug>(easymotion-*)` this extension answers.
 *
 * Inclusive and exclusive are Vim's own for the motion each one stands in for - `f` takes the
 * character it lands on and `F` does not - and only matter under an operator.
 */
private enum class Motion(
  val plug: String,
  val defaultKeys: String?,
  val kind: Kind,
  val direction: Direction,
  val type: MotionType,
  val bigWord: Boolean = false,
  val currentLineOnly: Boolean = false,
) {
  // The seventeen with default mappings.
  FIND_FORWARD("f", "f", Kind.FIND, Direction.FORWARD, MotionType.INCLUSIVE),
  FIND_BACKWARD("F", "F", Kind.FIND, Direction.BACKWARD, MotionType.EXCLUSIVE),
  TILL_FORWARD("t", "t", Kind.TILL, Direction.FORWARD, MotionType.INCLUSIVE),
  TILL_BACKWARD("T", "T", Kind.TILL, Direction.BACKWARD, MotionType.EXCLUSIVE),
  WORD("w", "w", Kind.WORD_START, Direction.FORWARD, MotionType.EXCLUSIVE),
  BIG_WORD("W", "W", Kind.WORD_START, Direction.FORWARD, MotionType.EXCLUSIVE, bigWord = true),
  BACK("b", "b", Kind.WORD_START, Direction.BACKWARD, MotionType.EXCLUSIVE),
  BIG_BACK("B", "B", Kind.WORD_START, Direction.BACKWARD, MotionType.EXCLUSIVE, bigWord = true),
  END("e", "e", Kind.WORD_END, Direction.FORWARD, MotionType.INCLUSIVE),
  BIG_END("E", "E", Kind.WORD_END, Direction.FORWARD, MotionType.INCLUSIVE, bigWord = true),
  BACK_END("ge", "ge", Kind.WORD_END, Direction.BACKWARD, MotionType.INCLUSIVE),
  BIG_BACK_END("gE", "gE", Kind.WORD_END, Direction.BACKWARD, MotionType.INCLUSIVE, bigWord = true),
  LINE_DOWN("j", "j", Kind.LINE, Direction.FORWARD, MotionType.LINE_WISE),
  LINE_UP("k", "k", Kind.LINE, Direction.BACKWARD, MotionType.LINE_WISE),
  SEARCH_NEXT("n", "n", Kind.SEARCH, Direction.FORWARD, MotionType.EXCLUSIVE),
  SEARCH_PREVIOUS("N", "N", Kind.SEARCH, Direction.BACKWARD, MotionType.EXCLUSIVE),
  SEARCH_CHARACTER("s", "s", Kind.FIND, Direction.BOTH, MotionType.INCLUSIVE),

  // Both directions at once.
  BD_FIND("bd-f", null, Kind.FIND, Direction.BOTH, MotionType.INCLUSIVE),
  BD_TILL("bd-t", null, Kind.TILL, Direction.BOTH, MotionType.INCLUSIVE),
  BD_WORD("bd-w", null, Kind.WORD_START, Direction.BOTH, MotionType.EXCLUSIVE),
  BD_BIG_WORD("bd-W", null, Kind.WORD_START, Direction.BOTH, MotionType.EXCLUSIVE, bigWord = true),
  BD_END("bd-e", null, Kind.WORD_END, Direction.BOTH, MotionType.INCLUSIVE),
  BD_BIG_END("bd-E", null, Kind.WORD_END, Direction.BOTH, MotionType.INCLUSIVE, bigWord = true),
  BD_LINE("bd-jk", null, Kind.LINE, Direction.BOTH, MotionType.LINE_WISE),
  BD_SEARCH("bd-n", null, Kind.SEARCH, Direction.BOTH, MotionType.EXCLUSIVE),

  // Lines, by where on the line.
  SOL_DOWN("sol-j", null, Kind.LINE_FIRST_NON_BLANK, Direction.FORWARD, MotionType.LINE_WISE),
  SOL_UP("sol-k", null, Kind.LINE_FIRST_NON_BLANK, Direction.BACKWARD, MotionType.LINE_WISE),
  SOL_BD("sol-bd-jk", null, Kind.LINE_FIRST_NON_BLANK, Direction.BOTH, MotionType.LINE_WISE),
  EOL_DOWN("eol-j", null, Kind.LINE_END, Direction.FORWARD, MotionType.LINE_WISE),
  EOL_UP("eol-k", null, Kind.LINE_END, Direction.BACKWARD, MotionType.LINE_WISE),
  EOL_BD("eol-bd-jk", null, Kind.LINE_END, Direction.BOTH, MotionType.LINE_WISE),

  // Within the current line.
  LINE_SEARCH_CHARACTER("sl", null, Kind.FIND, Direction.BOTH, MotionType.INCLUSIVE, currentLineOnly = true),
  LINE_FIND_FORWARD("fl", null, Kind.FIND, Direction.FORWARD, MotionType.INCLUSIVE, currentLineOnly = true),
  LINE_FIND_BACKWARD("Fl", null, Kind.FIND, Direction.BACKWARD, MotionType.EXCLUSIVE, currentLineOnly = true),
  LINE_BD_FIND("bd-fl", null, Kind.FIND, Direction.BOTH, MotionType.INCLUSIVE, currentLineOnly = true),
  LINE_TILL_FORWARD("tl", null, Kind.TILL, Direction.FORWARD, MotionType.INCLUSIVE, currentLineOnly = true),
  LINE_TILL_BACKWARD("Tl", null, Kind.TILL, Direction.BACKWARD, MotionType.EXCLUSIVE, currentLineOnly = true),
  LINE_BD_TILL("bd-tl", null, Kind.TILL, Direction.BOTH, MotionType.INCLUSIVE, currentLineOnly = true),
  LINE_WORD("wl", null, Kind.WORD_START, Direction.FORWARD, MotionType.EXCLUSIVE, currentLineOnly = true),
  LINE_BACK("bl", null, Kind.WORD_START, Direction.BACKWARD, MotionType.EXCLUSIVE, currentLineOnly = true),
  LINE_BD_WORD("bd-wl", null, Kind.WORD_START, Direction.BOTH, MotionType.EXCLUSIVE, currentLineOnly = true),
  LINE_END_OF_WORD("el", null, Kind.WORD_END, Direction.FORWARD, MotionType.INCLUSIVE, currentLineOnly = true),
  LINE_BACK_END("gel", null, Kind.WORD_END, Direction.BACKWARD, MotionType.INCLUSIVE, currentLineOnly = true),
  LINE_BD_END("bd-el", null, Kind.WORD_END, Direction.BOTH, MotionType.INCLUSIVE, currentLineOnly = true),

  // `'iskeyword'` motions, which are the plain ones here: see the file comment.
  ISKEYWORD_WORD("iskeyword-w", null, Kind.WORD_START, Direction.FORWARD, MotionType.EXCLUSIVE),
  ISKEYWORD_BACK("iskeyword-b", null, Kind.WORD_START, Direction.BACKWARD, MotionType.EXCLUSIVE),
  ISKEYWORD_BD_WORD("iskeyword-bd-w", null, Kind.WORD_START, Direction.BOTH, MotionType.EXCLUSIVE),
  ISKEYWORD_END("iskeyword-e", null, Kind.WORD_END, Direction.FORWARD, MotionType.INCLUSIVE),
  ISKEYWORD_BACK_END("iskeyword-ge", null, Kind.WORD_END, Direction.BACKWARD, MotionType.INCLUSIVE),
  ISKEYWORD_BD_END("iskeyword-bd-e", null, Kind.WORD_END, Direction.BOTH, MotionType.INCLUSIVE),

  // `vim-n` searches with Vim's regular expressions instead of Perl's. Here there are only Vim's.
  VIM_SEARCH_NEXT("vim-n", null, Kind.SEARCH, Direction.FORWARD, MotionType.EXCLUSIVE),
  VIM_SEARCH_PREVIOUS("vim-N", null, Kind.SEARCH, Direction.BACKWARD, MotionType.EXCLUSIVE),
}

private class EasyMotionHandler(private val motion: Motion) : ExtensionHandler.WithCallback() {

  override val isLinewiseMotion: Boolean
    get() = motion.type == MotionType.LINE_WISE

  override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
    // Remembered now. By the time a label is typed that is a later keystroke's question.
    val underOperator = editor.mode is Mode.OP_PENDING
    val keys = labelKeys() ?: return abandon(editor, underOperator, TOO_FEW_KEYS, beep = true)
    val jump = Jump(editor, keys, underOperator)

    // A motion that reads no character knows its targets now, and needs no prompt for one or none.
    if (!motion.kind.readsCharacter) {
      val targets = jump.targets(null)
      if (targets.size <= 1) return jump.finish(targets)
      jump.draw(emptyList())
    }

    // One prompt for everything: a find motion's character, then the labels. See the file comment.
    readKeys(
      editor,
      context,
      isComplete = jump::isComplete,
      onProgress = jump::draw,
      onCancel = jump::cancel,
      onInput = jump::input,
    )
  }

  /** One use of the motion, from the moment it is invoked to the jump or the cancel. */
  private inner class Jump(
    private val editor: VimEditor,
    private val keys: String,
    private val underOperator: Boolean,
  ) {
    private val start = editor.primaryCaret().offset
    private val skipped = if (motion.kind.readsCharacter) 1 else 0
    private var found: List<Int>? = null
    private var tree: Map<Char, LabelNode<Int>> = emptyMap()
    private var shaded: List<TextRange> = emptyList()

    /**
     * The targets, found once: at the start, or - for a find motion - at the character it reads.
     *
     * Computed inside [isComplete] for a find motion, which is a pure question with a side of
     * arithmetic: nothing is drawn and no key is run until the prompt says where it has got to.
     */
    fun targets(character: Char?): List<Int> = found ?: findTargets(editor, motion, start, character).also {
      found = it
      if (it.size > 1) {
        tree = groupTargets(it, keys)
        shaded = shadedRanges(editor, motion, start)
      }
    }

    fun isComplete(typed: List<VimKeyStroke>): Boolean {
      if (skipped == 1 && targets(typed.first().keyChar).size <= 1) return true
      return resolve(tree, labelsIn(typed)) !is Resolution.Partial
    }

    /** The labels still reachable from what has been typed. Called while the prompt is open. */
    fun draw(typed: List<VimKeyStroke>) {
      val level = (resolve(tree, labelsIn(typed)) as? Resolution.Partial)?.children ?: return
      val labels = pathsOf(level).map { (offset, path) -> JumpLabel(offset, path) }
      injector.jumpLabelDisplay.showLabels(editor, labels, shaded)
      labelled = editor
    }

    fun cancel() {
      clear()
      abandon(editor, underOperator, CANCELLED, beep = false)
    }

    fun input(typed: List<VimKeyStroke>) {
      clear()
      val targets = targets(typed.firstOrNull()?.keyChar)
      if (targets.size <= 1) return finish(targets)
      when (val chosen = resolve(tree, labelsIn(typed))) {
        is Resolution.Jump -> jumpTo(chosen.target)
        else -> abandon(editor, underOperator, CANCELLED, beep = false)
      }
    }

    /** No choice to offer: jump to the one target, or say there is none. */
    fun finish(targets: List<Int>) {
      val target = targets.singleOrNull() ?: return abandon(editor, underOperator, NO_TARGET, beep = true)
      jumpTo(target)
    }

    private fun labelsIn(typed: List<VimKeyStroke>): List<Char> = typed.drop(skipped).map(::labelCharacter)

    private fun jumpTo(target: Int) {
      val caret = editor.primaryCaret()
      if (underOperator) {
        // The operator sees where the caret went and treats that end as exclusive. An inclusive
        // motion takes the character it lands on, so it lands one further. Backwards needs nothing:
        // an exclusive backward range already includes its target and leaves out where it started,
        // which is Vim's own rule for `F` and `b`.
        val end = if (motion.type == MotionType.INCLUSIVE && target > caret.offset) target + 1 else target
        caret.moveToOffset(end)
        continueVimExecution()
      } else {
        injector.jumpService.saveJumpLocation(editor)
        caret.moveToOffset(target)
      }
    }

    private fun clear() {
      injector.jumpLabelDisplay.clearLabels(editor)
      labelled = null
    }
  }
}

/**
 * Gives up, and says why.
 *
 * Under an operator it also takes the operator down. The prompt closing is not enough on its own:
 * the `d` is still waiting for a motion, and would take the next key typed as one.
 */
private fun abandon(editor: VimEditor, underOperator: Boolean, message: String, beep: Boolean) {
  injector.messages.showStatusBarMessage(editor, message)
  if (beep) injector.messages.indicateError()
  if (underOperator) KeyHandler.getInstance().reset(editor)
}

/** A typed label key, in the terms the labels were made in. */
private fun labelCharacter(key: VimKeyStroke): Char {
  val character = key.keyChar
  if (character == VimKeyCodes.CHAR_UNDEFINED) return character
  // A label is a command key, so a layout applies to it. See the file comment.
  val command = LangMapOptionHelper.mapChar(character)
  return if (globalFlag("EasyMotion_use_upper", false)) command.uppercaseChar() else command
}

private fun searchedLines(editor: VimEditor, motion: Motion, start: Int): List<IntRange> {
  val caretLine = editor.offsetToBufferPosition(start).line
  return if (motion.currentLineOnly) listOf(caretLine..caretLine) else injector.jumpLabelDisplay.visibleLines(editor)
}

private fun findTargets(editor: VimEditor, motion: Motion, start: Int, character: Char?): List<Int> {
  val text = editor.text()
  val caretLine = editor.offsetToBufferPosition(start).line
  val found = LinkedHashSet<Int>()
  for (lines in searchedLines(editor, motion, start)) {
    if (lines.isEmpty()) continue
    val from = editor.getLineStartOffset(lines.first)
    val to = editor.getLineEndOffset(lines.last)
    when (motion.kind) {
      Kind.WORD_START -> wordStarts(editor, text, from, to, motion.bigWord, found)
      Kind.WORD_END -> wordEnds(editor, text, from, to, motion.bigWord, found)
      Kind.LINE, Kind.LINE_FIRST_NON_BLANK, Kind.LINE_END -> {
        for (line in lines) {
          if (line != caretLine) found += lineTarget(editor, line, motion.kind)
        }
      }
      Kind.FIND -> if (character != null) finds(text, from, to, character, found)
      Kind.TILL -> if (character != null) tills(text, from, to, character, start, found)
      Kind.SEARCH -> searches(editor, lines, found)
    }
  }
  found.remove(start)
  return nearestFirst(found, start, motion.direction)
}

private fun nearestFirst(offsets: Collection<Int>, start: Int, direction: Direction): List<Int> = when (direction) {
  Direction.FORWARD -> offsets.filter { it > start }.sorted()
  Direction.BACKWARD -> offsets.filter { it < start }.sortedDescending()
  // Equally near either way, forwards wins - which is the direction a plain motion would have gone.
  Direction.BOTH -> offsets.sortedWith(compareBy<Int>({ abs(it - start) }, { if (it > start) 0 else 1 }))
}

private fun wordStarts(editor: VimEditor, text: CharSequence, from: Int, to: Int, bigWord: Boolean, into: MutableSet<Int>) {
  // `findNextWord` answers "the next word after this offset", so ask from one before the range to
  // catch a word starting on its first character. Offset 0 has nothing before it to ask from.
  var after = from - 1
  if (from == 0) {
    if (text.isNotEmpty() && text[0] != ' ' && text[0] != '\t') into += 0
    after = 0
  }
  while (true) {
    val next = injector.searchHelper.findNextWord(editor, after, 1, bigWord)
    if (next <= after || next > to || next >= text.length) break
    into += next
    after = next
  }
}

private fun wordEnds(editor: VimEditor, text: CharSequence, from: Int, to: Int, bigWord: Boolean, into: MutableSet<Int>) {
  // The same as [wordStarts]. The one end this cannot find is a one-character word at offset 0,
  // which `e` itself skips over from there.
  var after = if (from == 0) 0 else from - 1
  while (true) {
    val next = injector.searchHelper.findNextWordEnd(editor, after, 1, bigWord, stopOnEmptyLine = false)
    if (next <= after || next > to || next >= text.length) break
    into += next
    after = next
  }
}

private fun lineTarget(editor: VimEditor, line: Int, kind: Kind): Int = when (kind) {
  Kind.LINE_END -> injector.motion.moveCaretToLineEnd(editor, line, allowPastEnd = false)
  Kind.LINE ->
    if (globalFlag("EasyMotion_startofline", true)) {
      injector.motion.moveCaretToLineStartSkipLeading(editor, line)
    } else {
      injector.motion.moveCaretToLineWithSameColumn(editor, line, editor.primaryCaret())
    }
  else -> injector.motion.moveCaretToLineStartSkipLeading(editor, line)
}

private fun finds(text: CharSequence, from: Int, to: Int, character: Char, into: MutableSet<Int>) {
  val ignoreCase = globalFlag("EasyMotion_smartcase", false) && character.isLowerCase()
  for (offset in from until minOf(to, text.length)) {
    if (text[offset].equals(character, ignoreCase)) into += offset
  }
}

private fun tills(text: CharSequence, from: Int, to: Int, character: Char, start: Int, into: MutableSet<Int>) {
  val ignoreCase = globalFlag("EasyMotion_smartcase", false) && character.isLowerCase()
  for (offset in from until minOf(to, text.length)) {
    if (!text[offset].equals(character, ignoreCase)) continue
    // Just before a match ahead, just after one behind, and never across the end of a line.
    if (offset > start && offset > 0 && text[offset - 1] != '\n') into += offset - 1
    if (offset < start && offset + 1 < text.length && text[offset + 1] != '\n') into += offset + 1
  }
}

private fun searches(editor: VimEditor, lines: IntRange, into: MutableSet<Int>) {
  val pattern = injector.searchGroup.lastSearchPattern ?: return
  val options = injector.options(editor)
  val ignoreCase = options.ignorecase && !(options.smartcase && pattern.any { it.isUpperCase() })
  injector.searchHelper.findAll(editor, pattern, lines.first, lines.last, ignoreCase).forEach { into += it.startOffset }
}

/** The text the motion searched, to be dimmed: after the caret, before it, or all of it. */
private fun shadedRanges(editor: VimEditor, motion: Motion, start: Int): List<TextRange> {
  if (!globalFlag("EasyMotion_do_shade", true)) return emptyList()
  return searchedLines(editor, motion, start).mapNotNull { lines ->
    if (lines.isEmpty()) return@mapNotNull null
    val lineStart = editor.getLineStartOffset(lines.first)
    val lineEnd = editor.getLineEndOffset(lines.last)
    val from = if (motion.direction == Direction.FORWARD) maxOf(lineStart, start + 1) else lineStart
    val to = if (motion.direction == Direction.BACKWARD) minOf(lineEnd, start) else lineEnd
    if (from < to) TextRange(from, to) else null
  }
}

private fun labelKeys(): String? {
  val configured = globalString("EasyMotion_keys") ?: DEFAULT_KEYS
  // A repeated key is dropped rather than refused. The grouping cannot use one twice, and a config
  // that repeats a letter by accident should still work.
  return configured.toList().distinct().joinToString("").takeIf { it.length >= 2 }
}

private fun globalString(name: String): String? = try {
  injector.variableService.getGlobalVariableValue(name)?.asString()
} catch (e: Exception) {
  null
}

private fun globalFlag(name: String, default: Boolean): Boolean = try {
  injector.variableService.getGlobalVariableValue(name)?.asBoolean() ?: default
} catch (e: Exception) {
  default
}
