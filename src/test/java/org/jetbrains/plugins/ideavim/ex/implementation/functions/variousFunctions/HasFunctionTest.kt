/*
 * Copyright 2003-2025 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package org.jetbrains.plugins.ideavim.ex.implementation.functions.variousFunctions

import org.jetbrains.plugins.ideavim.SkipNeovimReason
import org.jetbrains.plugins.ideavim.TestWithoutNeovim
import org.jetbrains.plugins.ideavim.VimTestCase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo

class HasFunctionTest : VimTestCase() {
  @BeforeEach
  override fun setUp(testInfo: TestInfo) {
    super.setUp(testInfo)
    configureByText("\n")
  }

  @Test
  fun `test has for supported feature`() {
    assertCommandOutput("echo has('ide')", "1")
  }

  /**
   * The list of features grew when `has()` moved into the engine.
   *
   * This test used to name `autocmd` as an unsupported one, and that stopped being true - `:autocmd`
   * is real here. The example is now something the fork genuinely does not have, which is what the
   * test was always for.
   */
  @Test
  fun `test has for unsupported feature`() {
    assertCommandOutput("echo has('python3')", "0")
  }

  /** And the other side of the same move: features this fork does have, answered from the engine. */
  @Test
  fun `test has for the features this fork provides`() {
    assertCommandOutput("echo has('autocmd')", "1")
    assertCommandOutput("echo has('quickfix')", "1")
    assertCommandOutput("echo has('signs')", "1")
  }

  /**
   * The operating system is the host's answer, and this host has one.
   *
   * Asked as "does it know what it is running on" rather than "is it a Mac", so the test says the
   * same thing wherever it runs: every platform this fork builds on is one or the other. A `0`
   * would mean the host had stopped contributing its half of the answer, and the engine has no
   * platform API at all to fall back on.
   */
  @Test
  fun `test has knows which platform this is`() {
    assertCommandOutput("echo has('unix') || has('win32')", "1")
  }

  @Test
  fun `test has for int as an argument`() {
    assertCommandOutput("echo has(42)", "0")
  }

  @TestWithoutNeovim(SkipNeovimReason.PLUGIN_ERROR)
  @Test
  fun `test has for list as an argument`() {
    enterCommand("echo has([])")
    assertPluginError(true)
    assertPluginErrorMessage("E730: Using a List as a String")
  }
}
