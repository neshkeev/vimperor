/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.github.neshkeev.vimperor.extension.readCharacter
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.ImmutableVimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.Argument
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.MotionType
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping
import com.maddyhome.idea.vim.handler.Motion
import com.maddyhome.idea.vim.handler.MotionActionHandler
import com.maddyhome.idea.vim.handler.toMotionOrError
import com.maddyhome.idea.vim.key.MappingOwner
import com.maddyhome.idea.vim.state.mode.Mode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An extension that reads its input *later* can still give an operator the motion argument it is
 * waiting on, and the motion keeps its own inclusivity.
 *
 * Nothing ships on this yet. It is the probe that answered the `clever-f` question, kept because the
 * answer is a fact about the engine that no other test asserts, and because the question had been
 * recorded in CLAUDE.md with the wrong answer for two passes.
 *
 * ## What the question was
 *
 * `clever-f` makes `dfa` inclusive and `dta` stop in front of the character. It gets there by
 * *blocking*: `inputKeyStroke` pumps a nested AWT event loop
 * (`ModalEntry.activate` → `createSecondaryLoop()` → `loop.enter()`), so the character is in hand
 * before the handler returns and `commandBuilder.addAction(CleverFMotionAction(...))` lands inside
 * the keystroke that `d` is still part of. A runtime with one thread has no nested loop, so this
 * host cannot do that, and `readKeys` returns before the character arrives.
 *
 * The recorded conclusion was that the only faithful route was an engine change - the command
 * builder deciding at runtime whether a motion needs a character argument - and that easymotion's
 * `WithCallback` would instead make `dfa` behave as `dta`, because an operator given a *caret
 * movement* treats that end as exclusive.
 *
 * ## What is actually true
 *
 * Both halves of that were wrong, and `ToHandlerMappingInfo` says so rather than the extension.
 * `myFun` - the caret-delta route - is guarded by
 * `!keyState.commandBuilder.hasCurrentCommandPartArgument()`, which is exactly how `clever-f`'s own
 * `addAction` suppresses it; and `WithCallback` already defers both `myFun` and the
 * `finishedCommandPreparation` after it into `_backingFunction`. So a handler that calls `addAction`
 * from inside its `readKeys` callback and *then* `continueVimExecution()` skips the caret-delta
 * branch and has the command run with its own motion.
 *
 * Measured here: the command builder still holds `d` a keystroke later, and
 * `keyHandlerState.commandBuilder` is the same object at callback time. Those were the two things
 * that had to be settled, and both hold. `clever-f` needs no engine change for operator-pending
 * mode - only the caret-moved and document-changed listeners `SearchFinalizer` wants.
 *
 * The third test is the control and is the one that makes the other two mean anything: the same
 * handler without `addAction` is exclusive, so the difference is the mechanism and not the arithmetic.
 */
class DeferredExtensionMotionTest {

  private class Session(text: String) {
    val fake = FakeEditor(text)
    val host = VimHost().also { it.start() }

    init {
      KeyHandler.getInstance().fullReset(host.editorFor(fake))
    }

    fun key(notation: String) = host.key(fake, notation)
    fun type(text: String) = text.forEach { host.type(fake, it.toString()) }
    val content: String get() = fake.document.content
    val caret: Int get() = host.editorFor(fake).primaryCaret().offset
  }

  private val owner = MappingOwner.Plugin.get("clever-f-probe")

  private fun map(key: String, handler: ExtensionHandler) =
    putExtensionHandlerMapping(MappingMode.NXO, injector.parser.parseKeys(key), owner, handler, false)

  /** The next [char] at or after [from], or null. */
  private fun find(editor: VimEditor, from: Int, char: Char): Int? =
    ((from + 1) until editor.text().length).firstOrNull { editor.text()[it] == char }

  /** `clever-f`'s own motion: the character is already known, and the motion type is a `val`. */
  private class ProbeMotion(
    private val target: Int,
    inclusive: Boolean,
  ) : MotionActionHandler.ForEachCaret() {
    override val motionType: MotionType = if (inclusive) MotionType.INCLUSIVE else MotionType.EXCLUSIVE

    override fun getOffset(
      editor: VimEditor,
      caret: ImmutableVimCaret,
      context: ExecutionContext,
      argument: Argument?,
      operatorArguments: OperatorArguments,
    ): Motion = target.toMotionOrError()
  }

  /** Reads one character, then hands the builder a motion - clever-f's shape, one keystroke later. */
  private inner class AddActionHandler(private val till: Boolean) : ExtensionHandler.WithCallback() {
    override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
      val underOperator = editor.mode is Mode.OP_PENDING
      readCharacter(editor, context) { char ->
        val match = find(editor, editor.primaryCaret().offset, char) ?: return@readCharacter
        val target = if (till) match - 1 else match
        if (underOperator) {
          // Inclusive because the search went *forward*, which is upstream's rule and not a guess:
          // `CharSearch.isInclusive` is `isForward`, so `t` is inclusive too - `:h t` places the
          // caret on the character left of the match, inclusive. The probe had `!till` here and
          // `dta` came out one character short.
          KeyHandler.getInstance().keyHandlerState.commandBuilder.addAction(ProbeMotion(target, true))
          continueVimExecution()
        } else {
          editor.primaryCaret().moveToOffset(target)
        }
      }
    }
  }

  /** The caret-delta route easymotion takes, for the comparison: the operator sees only the offset. */
  private inner class CaretMoveHandler : ExtensionHandler.WithCallback() {
    override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
      val underOperator = editor.mode is Mode.OP_PENDING
      readCharacter(editor, context) { char ->
        val target = find(editor, editor.primaryCaret().offset, char) ?: return@readCharacter
        editor.primaryCaret().moveToOffset(target)
        if (underOperator) continueVimExecution()
      }
    }
  }

  @Test
  fun `test addAction from a readKeys callback makes dfa inclusive`() {
    val session = Session("one a two a\n")
    map("f", AddActionHandler(till = false))

    session.type("df")
    session.type("a")

    assertEquals(" two a\n", session.content, "dfa should take the 'a' with it")
    assertEquals(0, session.caret)
  }

  @Test
  fun `test addAction from a readKeys callback makes dta stop in front of the character`() {
    val session = Session("one a two\n")
    map("f", AddActionHandler(till = true))

    session.type("df")
    session.type("a")

    assertEquals("a two\n", session.content, "dta takes up to the character before the 'a'")
  }

  @Test
  fun `test the caret-delta route is exclusive, which is what dfa must not be`() {
    val session = Session("one a two a\n")
    map("f", CaretMoveHandler())

    session.type("df")
    session.type("a")

    assertEquals("a two a\n", session.content, "the operator sees only where the caret went")
  }

  @Test
  fun `test fa outside an operator still moves the caret`() {
    val session = Session("one a two a\n")
    map("f", AddActionHandler(till = false))

    session.type("f")
    session.type("a")

    assertEquals(4, session.caret)
    assertEquals("one a two a\n", session.content)
  }
}
