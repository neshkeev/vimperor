/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.vimscript.model.commands.engineExCommandProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No command this host adds may quietly replace one the engine implements.
 *
 * `ExCommandTree.addCommand` overwrites, and [VsCodeExCommandProvider] is registered *after* the
 * engine's - so a name in both silently swaps the real command for whatever this host declared. It
 * fails in the one direction nobody looks: the command still resolves, still reports no error, and
 * does nothing.
 *
 * That is not hypothetical. Eight commands were shadowed this way at once - `:cd`, `:lcd`,
 * `:profile`, `:diffthis`, `:diffoff`, `:behave`, `:language` and `:scriptnames` were written in
 * the engine while this host still declared them as accepted no-ops, and every engine test passed
 * because the engine tests use the engine's provider alone. This is the check that was missing.
 *
 * A deliberate override goes in [DELIBERATE] with its reason. There are none, and that is worth
 * keeping: a host command that means to replace an engine one is better written as a host *service*
 * the engine command calls.
 */
class ExCommandOverlapTest {

  @Test
  fun `test no command this host adds shadows one the engine implements`() {
    val engine = engineExCommandProvider.getCommands().keys.flatMap { abbreviations(it) }.toSet()
    assertTrue(engine.size > 300, "only ${engine.size} engine commands were read, so this stopped reading them")

    val shadowing = VsCodeExCommandProvider.getCommands().keys
      .filter { spec -> spec !in DELIBERATE && (abbreviations(spec) intersect engine).isNotEmpty() }
      .sorted()

    assertEquals(
      emptyList(),
      shadowing,
      "these replace an engine command with this host's, silently - implement the difference as a " +
        "service the engine command calls, or list it in DELIBERATE with a reason",
    )
  }

  /**
   * The check has to be able to fail.
   *
   * `:set` is the engine's and nothing here declares it; `:vimtutor` is this host's and the engine
   * has never heard of it. If either of those stops being true the reader above is broken.
   */
  @Test
  fun `test the two sides are both being read`() {
    val engine = engineExCommandProvider.getCommands().keys.flatMap { abbreviations(it) }.toSet()
    val host = VsCodeExCommandProvider.getCommands().keys.flatMap { abbreviations(it) }.toSet()

    assertTrue("set" in engine, "the engine registers `:set`")
    assertTrue("set" !in host, "and this host does not")
    assertTrue("vimtutor" in host, "this host registers `:vimtutor`")
    assertTrue("vimtutor" !in engine, "and the engine does not")
  }

  private fun abbreviations(spec: String): Set<String> {
    val found = mutableSetOf<String>()
    for (alternative in spec.split(",")) {
      val trimmed = alternative.trim()
      val required = trimmed.substringBefore("[")
      val optional = if ("[" in trimmed) trimmed.substringAfter("[").substringBefore("]") else ""
      for (length in 0..optional.length) found += required + optional.take(length)
    }
    return found
  }

  private companion object {
    /** Overrides that are meant, each with the reason it is meant. Empty, deliberately. */
    val DELIBERATE = emptySet<String>()
  }
}
