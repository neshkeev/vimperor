/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.host

import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.MappingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first thing the headless host does: build the engine's whole command list.
 *
 * `getCommands()` parses every stored key sequence through `injector.parser` and turns every mode
 * character into a `MappingMode`, so this exercises the registry the way the engine will - not the
 * `TODO`-shaped stand-in a compile check would accept. Until now the JS side could show that the
 * handler classes construct; it could not show that the commands they belong to can be built.
 */
class HeadlessCommandRegistryTest {

  private fun withInjector(action: () -> Unit) {
    injector = HeadlessInjector()
    action()
  }

  @Test
  fun `test the engine's commands build against a headless injector`() {
    withInjector {
      val commands = engineCommandProvider.getCommands()
      assertTrue(commands.size > 300, "expected the full command list, got ${commands.size}")

      val keyless = commands.filter { it.keys.isEmpty() || it.keys.any { keys -> keys.isEmpty() } }
      assertEquals(emptyList(), keyless.map { it.actionId }, "a command with no keys cannot be invoked")

      val modeless = commands.filter { it.modes.isEmpty() }
      assertEquals(emptyList(), modeless.map { it.actionId }, "a command with no modes is unreachable")

      // Action ids come from the *simple* class name, so two commands in different packages can
      // collapse onto one id - and one pair does. `RegisterActions.findAction` returns the first
      // match, so the command-line `<C-R>` handler cannot be reached by id at all; the insert-mode
      // one shadows it.
      //
      // Pre-existing on both targets, not introduced by the port, and not fixed here: action ids
      // appear in users' `<Action>` mappings, so renaming one is a visible change that should be
      // made deliberately. Pinned so that fixing it fails this test rather than passing silently.
      val duplicates = commands.groupBy { it.actionId }.filterValues { it.size > 1 }.keys
      assertEquals(setOf("VimInsertRegisterAction"), duplicates, "the known id collision changed")
    }
  }

  @Test
  fun `test a known binding parses into the keystrokes it names`() {
    withInjector {
      val commands = engineCommandProvider.getCommands()
      val deleteChar = commands.single { it.actionId == "VimDeleteCharacterRightAction" }
      assertTrue(MappingMode.NORMAL in deleteChar.modes)
      val keys = deleteChar.keys.single()
      assertEquals(1, keys.size)
      assertEquals('x'.code, keys.single().keyChar.code)
    }
  }
}
