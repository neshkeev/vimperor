/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

/**
 * Keeps the composed text of dead keys and input methods out of the document while a command is
 * being typed. Ported from IdeaVim's `ComposedTextDispatcher` (VIM-1503).
 *
 * A dead key - `'` in the U.S. International layout - produces no character on its own. The OS
 * hands over what is pending so far as *composed* text, the editor draws it into the document, and
 * the real character arrives only when the next key commits it. In Normal, Visual and
 * Operator-pending modes that composed text is not text at all: it is half of a command, and it has
 * no business in the buffer. Insert, Replace and Select are left alone, because there the composed
 * text is meant to be typed into the document and the user needs to see it while they compose.
 *
 * That much is upstream's. What this host needs on top of it is the arithmetic, because VS Code
 * says all of this over **four commands** where IntelliJ says it in one `InputMethodEvent` carrying
 * `committedCharacterCount`. Read out of the installed editor's `TextAreaInput`:
 *
 * ```js
 * handleCompositionUpdate(o) {
 *   o = o || "";
 *   let e = { text: o, replacePrevCharCnt: this._lastTypeTextLength, ... };
 *   this._lastTypeTextLength = o.length;
 *   return e;
 * }
 * ```
 *
 * **`replacePrevCharCnt` is the length of the composition's own previous preview, and nothing to do
 * with the document.** That is the whole bug and the reason it is so destructive here: the count is
 * applied as `caret.column - replacePrevCharCnt` by `_compositionType`, so a composition that has
 * grown to twenty-seven characters deletes twenty-seven characters of whatever the caret is now
 * sitting behind - text the composition never put there, because Vim was swallowing every key into
 * commands while the IME went on counting. Reported as `<Esc>` eating most of a line.
 *
 * Why `<Esc>` and not sooner: `_compositionType` opens with `if (!e.isEmpty()) return null`, so
 * while a Visual selection is up the composition cannot apply and sits there harmless. Collapsing
 * the selection is what lets it land, and that is the keystroke the user sees it on.
 *
 * **One decision per composition, taken at its start and held to the end.** That is this host's
 * answer to the rule upstream writes as `if (editor.composedTextRange != null) return false` - a
 * composition the editor is already showing has to go on through, or its preview is stranded in the
 * document with nothing able to remove it. VS Code offers no reader for that, but it does not need
 * one: whichever side a composition starts on, it belongs to that side for its whole length, so a
 * mode change in the middle - which is exactly what `<Esc>` is - cannot split it. The bit is exact
 * rather than believed, because [started] and [ended] see every composition there is.
 *
 * Two things that are not obvious about the four commands:
 *
 *  - **The first update arrives as `type`.** `replacePrevCharCnt` is zero for it, and the view
 *    controller routes a zero count to `type` rather than to `replacePreviousChar` - so the first
 *    composed character is indistinguishable from ordinary typing unless `compositionStart` is being
 *    watched. That is why [updated] is what the `type` handler consults too.
 *  - **The committed text arrives before `compositionEnd`.** `onCompositionEnd` fires
 *    `_onType.fire(p)` and *then* `_onCompositionEnd.fire()`, so by the time [ended] is reached the
 *    recording already holds what the user committed. Nothing has to be read back.
 *
 * The command line keeps composed text out as well, which is a divergence from upstream and is
 * forced rather than chosen: IdeaVim's ex field is a real Swing text field and draws the composition
 * itself - `ExDocument.addInputMethodAttributes` - while this host's command line is a status bar
 * item that cannot show one. So the committed text is typed into it when the composition finishes,
 * which is the same answer Normal mode gets.
 */
internal class ComposedText {

  /**
   * Whether the composition in flight is being kept out of the document, or null when there is none.
   *
   * Three states rather than two: "no composition" has to be distinguishable from "a composition
   * that is VS Code's", because a command arriving with no composition recorded is the one case this
   * cannot know anything about - see [updated].
   */
  private var keepingOut: Boolean? = null

  /** The composition as this host has been told it so far, which is what Vim will be given. */
  private var composed: String = ""

  /** Whether a composition is in flight, which is what tells a `type` apart from a typed key. */
  val inProgress: Boolean get() = keepingOut != null

  /** VS Code's `compositionStart`. */
  fun started(keepOutOfTheDocument: Boolean): ComposedTextDecision {
    keepingOut = keepOutOfTheDocument
    composed = ""
    return if (keepOutOfTheDocument) ComposedTextDecision.KeepOut else ComposedTextDecision.HandToVsCode
  }

  /**
   * `replacePreviousChar`, `compositionType`, and the `type` that carries a composition's first
   * update.
   *
   * [keepOutOfTheDocument] is consulted only when no composition has been started - this host
   * activating in the middle of one, which is the single case where the rule above cannot be
   * applied because there is nothing to apply it from. Deciding from the mode now is the answer that
   * fixes the reported bug; the residual is a preview already in the document, which stays there
   * until the user removes it.
   */
  fun updated(text: String, replacePrevious: Int, keepOutOfTheDocument: Boolean): ComposedTextDecision {
    val keeping = keepingOut ?: keepOutOfTheDocument
    keepingOut = keeping
    if (!keeping) return ComposedTextDecision.HandToVsCode
    // `dropLast` rather than clearing, which is VS Code's own arithmetic rather than a shortcut
    // through it: the count is the previous preview's length and this recording *is* that preview,
    // so the two agree - and where they do not, dropping what the count asks for is still right.
    composed = composed.dropLast(replacePrevious) + text
    return ComposedTextDecision.KeepOut
  }

  /** VS Code's `compositionEnd`, after which [composed] is what the user actually committed. */
  fun ended(): ComposedTextDecision {
    val keeping = keepingOut
    val committed = composed
    forget()
    return when {
      keeping != true -> ComposedTextDecision.HandToVsCode
      committed.isEmpty() -> ComposedTextDecision.KeepOut
      else -> ComposedTextDecision.Commit(committed)
    }
  }

  /** Between hosts and between tests. A new host is a new session and cannot be mid-composition. */
  fun forget() {
    keepingOut = null
    composed = ""
  }
}

/**
 * What to do with one of VS Code's four composition commands.
 *
 * A decision rather than an action, for the reason [WorkbenchPress] carries two lists: the policy is
 * the part worth testing and `commands.executeCommand` is not, so the choice is made here where a
 * unit test can read it and carried out in `Extension.kt`, which no unit test loads.
 */
internal sealed interface ComposedTextDecision {

  /** Dropped. The composition is half of a Vim command and must not reach the buffer. */
  object KeepOut : ComposedTextDecision

  /** Forwarded to `default:<id>`, which is how composed text gets typed in Insert mode. */
  object HandToVsCode : ComposedTextDecision

  /** The composition finished; [text] is what the user committed, to be handled as keys. */
  class Commit(val text: String) : ComposedTextDecision
}
