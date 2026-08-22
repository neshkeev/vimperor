/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the generated registry is more than a list of names: every handler it names is a class this
 * target can actually construct.
 *
 * The JVM cannot answer this question for JS. There, a name that no longer resolves is a
 * `ClassNotFoundException` the first time that key is pressed, so nothing before this ran the
 * constructors at all. Here the compiler has already checked that every class exists, and this
 * checks that every one of them can be built.
 *
 * Some handlers parse their own key sequences in a property initializer, so constructing them needs
 * `injector` - the same thing `getCommands()` needs to turn the stored strings into keystrokes.
 * There is no injector on this target yet, so those are counted separately rather than treated as
 * failures: the engine never builds a handler before the injector exists either, because
 * `LazyInstance` defers construction to first use. Any *other* kind of failure is a port defect.
 *
 * The counts are floors rather than exact numbers: adding a command should not fail a test. What
 * would fail is a chunk of the list silently disappearing.
 */
class GeneratedEngineRegistryTest {

  @Test
  fun `test every command handler constructs`() {
    val failures = mutableListOf<String>()
    var needInjector = 0
    for (entry in GENERATED_ENGINE_COMMANDS) {
      try {
        entry.factory()
      } catch (e: UninitializedPropertyAccessException) {
        needInjector++
      } catch (e: Throwable) {
        failures.add("${entry.className}: ${e.message}")
      }
    }
    assertEquals(emptyList(), failures, "these handlers could not be constructed on JS")
    // A handler that reads the injector while constructing is fine, but a list where most of them
    // do would mean the registry can no longer be built early, which is worth being told about.
    assertTrue(
      needInjector < GENERATED_ENGINE_COMMANDS.size / 10,
      "$needInjector of ${GENERATED_ENGINE_COMMANDS.size} handlers now need the injector to construct",
    )
  }

  @Test
  fun `test every function handler constructs`() {
    val failures = mutableListOf<String>()
    for (function in GENERATED_ENGINE_FUNCTIONS) {
      try {
        function.instance
      } catch (e: UninitializedPropertyAccessException) {
        // As above: built lazily, after the injector exists.
      } catch (e: Throwable) {
        failures.add("${function.name}: ${e.message}")
      }
    }
    assertEquals(emptyList(), failures, "these function handlers could not be constructed on JS")
  }

  @Test
  fun `test the registry holds the whole command list`() {
    assertTrue(
      GENERATED_ENGINE_COMMANDS.size > 300,
      "expected the full command list, got ${GENERATED_ENGINE_COMMANDS.size}",
    )
    assertTrue(
      GENERATED_ENGINE_FUNCTIONS.size > 80,
      "expected the full function list, got ${GENERATED_ENGINE_FUNCTIONS.size}",
    )
  }

  @Test
  fun `test the ex-command registry is complete`() {
    assertTrue(
      GENERATED_ENGINE_EX_COMMANDS.size > 130,
      "expected the full ex-command list, got ${GENERATED_ENGINE_EX_COMMANDS.size}",
    )
    // These two were the last commands JS could not reach: `:smile` read its art off the classpath
    // and `:source` asked the JVM whether a path was the vimrc. Both are named here so that
    // reintroducing a host dependency in either shows up as a failure.
    assertEquals(
      listOf("smile", "so[urce]"),
      listOf("smile", "so[urce]").filter { GENERATED_ENGINE_EX_COMMANDS.containsKey(it) },
      "these should be available on JS",
    )
    // Most names build generically; the rest are the 18 classes CommandVisitor constructs itself,
    // and those cover disproportionately many names because of aliases - one MapCommand answers to
    // `map`, `nmap`, `vmap` and more.
    val withFactory = GENERATED_ENGINE_EX_COMMANDS.values.count { it.hasStandardConstructor }
    assertTrue(
      withFactory > GENERATED_ENGINE_EX_COMMANDS.size / 2,
      "expected most ex-commands to have a factory, got $withFactory of ${GENERATED_ENGINE_EX_COMMANDS.size}",
    )
    // Named cases on both sides of that split, so the flag is checked and not just counted. The
    // keys are Vim's abbreviation syntax, which is how the parser matches a typed command name.
    assertTrue(GENERATED_ENGINE_EX_COMMANDS.getValue("d[elete]").hasStandardConstructor)
    assertFalse(GENERATED_ENGINE_EX_COMMANDS.getValue("s[ubstitute]").hasStandardConstructor)
    assertFalse(GENERATED_ENGINE_EX_COMMANDS.getValue("g[lobal]").hasStandardConstructor)
  }

  @Test
  fun `test each handler class appears once`() {
    val duplicates = GENERATED_ENGINE_COMMANDS.groupBy { it.className }.filterValues { it.size > 1 }
    assertEquals(emptyMap(), duplicates, "a handler class must produce exactly one LazyVimCommand")
  }

  @Test
  fun `test every command has at least one key sequence`() {
    val keyless = GENERATED_ENGINE_COMMANDS.filter { it.keys.isEmpty() || it.keys.any(String::isEmpty) }
    assertEquals(emptyList(), keyless.map { it.className }, "a command with no keys can never be invoked")
  }
}
