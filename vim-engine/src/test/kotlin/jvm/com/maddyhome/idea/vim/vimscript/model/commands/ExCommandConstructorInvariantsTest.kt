/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.vimscript.model.commands

import com.maddyhome.idea.vim.ex.ranges.Range
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.createType
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the two assumptions the ex-command construction path rests on.
 *
 * CommandVisitor looks commands up two different ways - `primaryConstructor` on one path, and a
 * search of *all* constructors for `(Range, CommandModifier, String)` on another. Collapsing them
 * into a single factory is only safe while those two always agree, so this asserts that they do,
 * across every ex-command the engine registers.
 */
class ExCommandConstructorInvariantsTest {

  private fun standardConstructorOf(kClass: KClass<*>): KFunction<Any>? =
    kClass.constructors
      .filter { it.parameters.size == 3 }
      .firstOrNull {
        it.parameters[0].type == Range::class.createType() &&
          it.parameters[1].type == CommandModifier::class.createType() &&
          it.parameters[2].type == String::class.createType()
      }

  /** Class name to the processor's `standardConstructor` flag, as recorded in the JSON. */
  private fun engineExCommands(): Map<String, Boolean> {
    val stream = javaClass.classLoader.getResourceAsStream("ksp-generated/engine_ex_commands.json")
      ?: error("engine_ex_commands.json is not on the test classpath")
    // Parsed by hand rather than with kotlinx.serialization, which the engine has only as a
    // compileOnly dependency and so is absent from this module's test runtime.
    val text = stream.reader().readText()
    val entry = Regex("\"class\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"standardConstructor\"\\s*:\\s*(true|false)")
    return entry.findAll(text).associate { it.groupValues[1] to it.groupValues[2].toBoolean() }
  }

  private fun engineExCommandClasses(): List<String> = engineExCommands().keys.sorted()

  @Test
  fun `test a matching three-argument constructor is always the primary one`() {
    val classes = engineExCommandClasses()
    assertTrue(classes.size > 50, "expected the full ex-command list, got " + classes.size)
    val offenders = mutableListOf<String>()
    for (name in classes) {
      val kClass = javaClass.classLoader.loadClass(name).kotlin
      val matching = standardConstructorOf(kClass) ?: continue
      if (matching != kClass.primaryConstructor) offenders.add(name)
    }
    assertEquals(
      emptyList(), offenders,
      "these declare a (Range, CommandModifier, String) constructor that is not the primary one, " +
        "so the two lookup paths in CommandVisitor would disagree for them",
    )
  }

  @Test
  fun `test the processor's constructor flag matches reflection`() {
    // The flag exists so a host with no reflection can be told the answer. That is only safe while
    // the two agree, so this compares KSP's view of the declared types against the runtime's view
    // of the loaded class, for every registered command.
    val disagreements = mutableListOf<String>()
    for ((name, flagged) in engineExCommands()) {
      val kClass = javaClass.classLoader.loadClass(name).kotlin
      val actual = standardConstructorOf(kClass) != null
      if (actual != flagged) disagreements.add("$name: json says $flagged, reflection says $actual")
    }
    assertEquals(emptyList(), disagreements, "the generated flag and the class have drifted apart")
  }

  @Test
  fun `test UnknownCommand has no three-argument constructor`() {
    // CommandVisitor falls back to UnknownCommand::class for unregistered names and relies on the
    // constructor search finding nothing there, so it reaches the explicit four-argument fallback.
    assertNull(standardConstructorOf(UnknownCommand::class))
  }
}
