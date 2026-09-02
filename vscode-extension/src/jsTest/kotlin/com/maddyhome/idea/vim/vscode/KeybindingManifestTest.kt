/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.key.VimKeyCodes
import com.maddyhome.idea.vim.key.VimKeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The keys VS Code will never hand to this extension unless `package.json` asks for them.
 *
 * A blind spot underneath all the others, and the one that would have shipped. Every test in this
 * module presses keys by calling `VimHost.key("<C-V>")` directly, which is the extension's own
 * entry point - not VS Code's. In a real window a key only reaches that entry point if the manifest
 * declares a keybinding for it. Printable characters arrive through the `type` command and need
 * nothing; everything else - Escape, the arrows, every control chord - arrives only if asked for.
 *
 * So a key can be implemented, swept, fixture-tested and completely dead in a real editor, and
 * nothing in this repository would say so. `<C-V>`, `<C-G>` and `<S-Right>` all had tests written
 * for them this week and none of the three was in the manifest.
 *
 * This compares what the engine registers against what the manifest asks for, in both directions.
 * A key left out on purpose has to be listed with a reason.
 */
class KeybindingManifestTest {

  @Test
  fun `test every key that needs a binding either has one or is listed here`() {
    val root = repositoryRoot()
    assertTrue(root != null, "could not find the repository root, so package.json could not be read")

    val declared = declaredKeys(readText("$root/vscode-extension/package.json"))

    val missing = (keysNeedingABinding() - declared).sorted()

    assertTrue(declared.size > 5, "only ${declared.size} keybindings were read, so this stopped reading the manifest")
    assertEquals(UNBOUND_ON_PURPOSE.trim(), missing.joinToString("\n").trim())
  }

  /**
   * The comparison has to be able to fail.
   *
   * Escape is declared and `<C-V>` was not until this test was written, so the two of them together
   * say that both sides are being read.
   */
  @Test
  fun `test a declared key is not reported and the enumeration finds the chords`() {
    val needed = keysNeedingABinding()

    assertTrue("<Esc>" in needed, "Escape is a key VS Code will not deliver without a binding")
    assertTrue("<C-V>" in needed, "a control chord is a key VS Code will not deliver without a binding")
    assertTrue("a" !in needed, "an ordinary character arrives through the `type` command")
    assertTrue(needed.size > 40, "only ${needed.size} keys were enumerated, so the engine was not read")
  }

  private companion object {
    /**
     * The `args` of every keybinding, which is where the manifest names a key in Vim's notation.
     *
     * Parsed rather than matched with a regex, and that is not fastidiousness: `<C-\\>` is spelled
     * `"<C-\\\\>"` in JSON, so a regex over the raw text reports a key as missing that is right
     * there. It did, until this was changed.
     */
    fun declaredKeys(manifest: String): Set<String> {
      val parsed = JSON.parse<dynamic>(manifest)
      val bindings = parsed.contributes.keybindings as Array<dynamic>
      return bindings.mapNotNull { it.args as? String }.toSet()
    }

    /**
     * Every keystroke the engine can be given that VS Code will not deliver as typed text.
     *
     * Anything with a modifier, and anything with no character of its own - Escape, the arrows,
     * Home, the function keys. A plain printable character is what the `type` command carries and
     * is the only thing that needs no help.
     */
    fun keysNeedingABinding(): Set<String> {
      VimHost().also { it.start() }
      val commands = engineCommandProvider.getCommands() + VsCodeCommandProvider.getCommands()
      return commands.asSequence()
        .flatMap { it.keys.asSequence() }
        .flatMap { it.asSequence() }
        .filter { it.needsABinding() }
        .map { injector.parser.toKeyNotation(listOf(it)) }
        .toSet()
    }

    fun VimKeyStroke.needsABinding(): Boolean {
      if (modifiers != 0) return true
      val character = keyChar
      return character == VimKeyCodes.CHAR_UNDEFINED || character.code < 32 || character.code == 127
    }

    /**
     * The keys left to VS Code, and why each one is left.
     *
     * There were sixty-one of these before this test was written, which is the point of it: `<C-V>`,
     * `<C-W>`, `<C-D>`, `<C-O>`, every shifted arrow and the whole of Home/End/PageUp/PageDown were
     * implemented, tested, and unreachable in a real window.
     *
     * `<C-C>`, `<C-F>`, `<C-S>` and `<C-Q>` are copy, find, save and quit. Vim has meanings for all
     * four and a Vim emulator that took them would be the reason somebody uninstalled it; a user who
     * wants them can add four lines to their own keybindings, which is the direction that decision
     * should go.
     *
     * `<C-2>`, `<C-@>` and `<C-S-2>` are terminal spellings of the same NUL that `<C-Space>` sends,
     * and `<C-6>` and `<C-S-6>` are terminal spellings of `<C-^>` - which *is* bound, to `ctrl+6`.
     * A terminal produced these; a keyboard does not.
     *
     * The keypad keys - `<Kleft>` and the rest, with and without control - are a distinction VS Code
     * does not make. Its `left` is both arrows.
     *
     * `<Undo>` is the dedicated Undo key on keyboards that have one. VS Code has no name for it.
     */
    val UNBOUND_ON_PURPOSE = """
      <C-2>
      <C-6>
      <C-@>
      <C-C>
      <C-F>
      <C-Kleft>
      <C-Kright>
      <C-Q>
      <C-S-2>
      <C-S-6>
      <C-S>
      <Kdown>
      <Kleft>
      <Kright>
      <Kup>
      <Undo>
    """.trimIndent()
  }
}
