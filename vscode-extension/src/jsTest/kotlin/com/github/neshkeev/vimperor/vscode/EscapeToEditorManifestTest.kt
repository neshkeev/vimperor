/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Escape outside the editor puts the cursor back in the document, the way IntelliJ does.
 *
 * ## Why this is a manifest binding and not engine code
 *
 * A key pressed in the sidebar or the panel never reaches an extension - `type` is the editor's
 * command and the Explorer is not an editor, which is the same wall the NERDTree keys met. So this
 * is `package.json`, and the only part of it decided at runtime is the `when` clause.
 *
 * ## Why it cannot be "the panel handles it, then we do"
 *
 * IntelliJ chains: the focused component sees Escape, and if it does nothing the focus goes to the
 * editor. VS Code resolves **one** keybinding per key - among those whose `when` matches, the last
 * registered wins, and an extension's beat the built-ins - so there is no falling through. The
 * chain has to be written as exclusions instead, and each one is a context where Escape already has
 * a job worth keeping:
 *
 *  - `!terminalFocus`, because Escape belongs to the shell. Vim in a terminal would be unusable.
 *  - `!inputFocus`, because Escape clears a Search box, a tree filter, or a rename field.
 *
 * What is left is the read-only half of the workbench - a tree, a list, the Output view, Problems -
 * where Escape does nothing today, which is exactly where returning to the editor is free.
 */
class EscapeToEditorManifestTest {

  private fun bindings(): List<dynamic> {
    val manifest = JSON.parse<dynamic>(readText("${repositoryRoot()!!}/vscode-extension/package.json"))
    return (manifest.contributes.keybindings as Array<dynamic>).toList()
  }

  private fun escapeBindings(): List<dynamic> =
    bindings().filter { it.command == VsCodeCommands.FOCUS_EDITOR }

  /** The one for the named areas of the chrome - the sidebar, the panel, the secondary sidebar. */
  private fun binding(): dynamic = escapeBindings().first { (it.`when` as String).contains("sideBarFocus ||") }

  private fun clause(): String = binding().`when` as String

  /** The one for focus that nothing has claimed, which is where the activity bar leaves it. */
  private fun unclaimedClause(): String =
    escapeBindings().first { !(it.`when` as String).contains("sideBarFocus ||") }.`when` as String

  /**
   * Every context key a clause names, all false, so a case has only to say what is true of it.
   *
   * Built from the clause rather than written out: the reader refuses a name a case does not
   * define, which is what keeps a case from quietly testing a clause it no longer matches - and
   * with nineteen names that would otherwise be nineteen lines per case.
   */
  private fun context(clause: String, vararg on: String): Map<String, Boolean> =
    Regex("[A-Za-z][A-Za-z0-9_.]*").findAll(clause).map { it.value }.distinct()
      .associateWith { it in on }

  @Test
  fun `test escape outside the editor returns to the document`() {
    assertEquals("escape", binding().key)
  }

  /**
   * Gated, so it can be turned off.
   *
   * This one binding changes what Escape does across the whole workbench, which is more than any
   * other binding here claims. `vimperor.escapeReturnsToEditor` is a setting as well as a context
   * key, and a user who wants VS Code's Escape back sets it false.
   */
  @Test
  fun `test the binding is gated on the setting`() {
    assertTrue(
      clause().startsWith("vimperor.escapeReturnsToEditor"),
      "ungated, this would change Escape for everyone who installs the extension",
    )
  }

  @Test
  fun `test the terminal keeps its own escape`() {
    assertTrue(
      clause().contains("!terminalFocus"),
      "Escape has to reach the shell - vim in a terminal is the case that matters",
    )
  }

  /**
   * A text input keeps its own Escape - but a read-only editor is not one, and the difference is
   * the whole of why this took two attempts.
   *
   * `inputFocus` is true for *any* focused text area, and the Monaco editor is a text area, so
   * `!inputFocus` excluded the Output view along with the Search box. Proved from a real window:
   * pressing Escape in the Output panel reached `vimperor.key`, whose only gate is
   * `editorTextFocus` - so `editorTextFocus` holds there and the Output view is an editor.
   *
   * `!inputFocus || editorTextFocus` is "an input that is not an editor", which is what was meant.
   */
  @Test
  fun `test a text input keeps its own escape`() {
    assertTrue(
      clause().contains("(!inputFocus || editorTextFocus)"),
      "Escape clears a Search box or a rename field, and that is worth more than a focus change",
    )
  }

  /**
   * The Output view is the case this exists for, and it is only reachable because of the clause
   * above. Read-only, focusable, and reporting both `panelFocus` and `editorTextFocus`.
   */
  @Test
  fun `test a read-only editor in the panel is not treated as an input`() {
    val inTheOutputView = mapOf(
      "vimperor.escapeReturnsToEditor" to true,
      "terminalFocus" to false,
      "inputFocus" to true,
      "editorTextFocus" to true,
      "panelFocus" to true,
      "sideBarFocus" to false,
      "auxiliaryBarFocus" to false,
    )

    assertTrue(matches(clause(), inTheOutputView), "Escape in the Output panel has to reach the editor")
  }

  /** ...while the Search box, an input that is not an editor, keeps Escape for itself. */
  @Test
  fun `test the search box keeps escape`() {
    val inTheSearchBox = mapOf(
      "vimperor.escapeReturnsToEditor" to true,
      "terminalFocus" to false,
      "inputFocus" to true,
      "editorTextFocus" to false,
      "panelFocus" to false,
      "sideBarFocus" to true,
      "auxiliaryBarFocus" to false,
    )

    assertTrue(!matches(clause(), inTheSearchBox), "clearing the box is worth more than a focus change")
  }

  /** And the terminal, where Escape belongs to whatever is running in it. */
  @Test
  fun `test the terminal keeps escape`() {
    val inTheTerminal = mapOf(
      "vimperor.escapeReturnsToEditor" to true,
      "terminalFocus" to true,
      "inputFocus" to true,
      "editorTextFocus" to false,
      "panelFocus" to true,
      "sideBarFocus" to false,
      "auxiliaryBarFocus" to false,
    )

    assertTrue(!matches(clause(), inTheTerminal), "vim in a terminal has to keep its Escape")
  }

  /**
   * The activity bar is what a *collapsed* sidebar leaves behind.
   *
   * Reported: with the sidebar collapsed, clicking its empty space and pressing Escape did nothing.
   * There is no sidebar container to click when it is collapsed - the click lands on the icon strip,
   * which sets `activityBarFocus` and not `sideBarFocus`.
   */
  @Test
  fun `test escape returns when nothing has claimed focus`() {
    val clause = unclaimedClause()

    assertTrue(matches(clause, context(clause, "vimperor.escapeReturnsToEditor")))
  }

  /**
   * ...and every context that owns Escape keeps it, one case per name.
   *
   * Each of these was checked against `workbench.desktop.main.js` before it was written down. That
   * check is the whole reason this clause is trustworthy: `activityBarFocus`, which the first
   * attempt at this feature added, **does not exist** - VS Code sets no context key for the
   * activity bar at all - so the binding was inert and the reported bug was unchanged.
   */
  @Test
  fun `test the unclaimed clause yields to everything that owns escape`() {
    val clause = unclaimedClause()
    val owners = listOf(
      "editorTextFocus", "terminalFocus", "inputFocus", "textInputFocus",
      "sideBarFocus", "panelFocus", "auxiliaryBarFocus", "listFocus", "inQuickOpen",
      "notificationFocus", "notificationCenterVisible", "statusBarFocused", "bannerFocused",
      "referenceSearchVisible", "commentFocused", "suggestWidgetVisible", "findWidgetVisible",
      "notebookEditorFocused",
    )

    owners.forEach { owner ->
      assertTrue(
        !matches(clause, context(clause, "vimperor.escapeReturnsToEditor", owner)),
        "Escape would be taken from `$owner`",
      )
    }
  }

  /** And the setting turns both of them off. */
  @Test
  fun `test the unclaimed clause is gated too`() {
    val clause = unclaimedClause()

    assertTrue(!matches(clause, context(clause)))
  }

  /**
   * Every area of the workbench chrome that can take focus is named, and the list is enumerated
   * rather than inverted.
   *
   * The trade is deliberate. A positive that is missing means Escape does nothing there, which is a
   * gap; an *exclusion* that is missing means Escape is taken from something that needed it, which
   * is a bug. Both reports against this feature have been missing positives, which is the cheaper
   * way to be wrong - and the reason the next one should be another name here rather than a rewrite
   * of the clause into `!editorTextFocus`.
   */
  @Test
  fun `test the named areas are the ones VS Code has keys for`() {
    val clause = clause()
    listOf("sideBarFocus", "panelFocus", "auxiliaryBarFocus").forEach {
      assertTrue(clause.contains(it), "$it is missing, so that part of the workbench is stranded")
    }
  }

  /**
   * Enough of VS Code's `when` grammar to answer the clause above: names, `!`, `&&`, `||` and
   * parentheses, with `&&` binding tighter than `||` as it does there.
   *
   * Written out because a clause is the only part of a manifest binding with any behaviour in it,
   * and reading it back as a string asserts spelling rather than meaning - which is what let
   * `!inputFocus` sit here looking correct while it excluded the Output panel.
   *
   * The precedence is not decoration. This clause happens to be `&&` all the way down with its
   * `||`s inside parentheses, so a flat left-to-right reading gets the same answer; the next clause
   * need not be, and a test that quietly disagrees with VS Code about `a && b || c` is worse than
   * no test.
   */
  private fun matches(clause: String, context: Map<String, Boolean>): Boolean {
    val tokens = Regex("""\(|\)|&&|\|\||!|[A-Za-z0-9_.]+""").findAll(clause).map { it.value }.toList()
    return Parser(tokens, context).parse()
  }

  /** A recursive-descent reader for the fragment of the grammar above. */
  private class Parser(private val tokens: List<String>, private val context: Map<String, Boolean>) {
    private var at = 0

    fun parse(): Boolean = disjunction().also { check(at == tokens.size) { "trailing `${tokens.drop(at)}`" } }

    /** Lowest precedence, so it is read first and its operands are whole conjunctions. */
    private fun disjunction(): Boolean {
      var value = conjunction()
      while (at < tokens.size && tokens[at] == "||") {
        at++
        // Not short-circuited: an operand naming a key this test forgot should say so either way.
        val right = conjunction()
        value = value || right
      }
      return value
    }

    private fun conjunction(): Boolean {
      var value = primary()
      while (at < tokens.size && tokens[at] == "&&") {
        at++
        val right = primary()
        value = value && right
      }
      return value
    }

    private fun primary(): Boolean = when (val token = tokens[at++]) {
      "(" -> disjunction().also { check(tokens[at++] == ")") { "unbalanced parentheses" } }
      "!" -> !primary()
      else -> context[token] ?: error("no value for `$token` in this test's context")
    }
  }
}
