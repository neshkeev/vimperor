/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine's tests that do not run on Kotlin/JS, and why each of them does not.
 *
 * This matters more than it looks. `vim-engine` is compiled to JS and run in a VS Code extension
 * host, so a test in `jvmTest` is a test of behaviour that ships to users on a platform the test
 * never touches. `commonTest` runs on both; `jvmTest` runs on one.
 *
 * The question this was written to answer is how much of the engine is untested on the platform the
 * VS Code port actually runs on, and the answer turned out to be reassuring: nearly every file here
 * is JVM-only *by construction*, because the JDK is the reference implementation it checks against.
 * A differential test of `Character.isLetter` cannot run where there is no `Character`. Only one
 * file - `CommenterToCommentsTest` - was here for no reason at all, and it has moved.
 *
 * So the list is pinned, in both directions. Adding a file here has to come with a reason, and a
 * file that stops needing one has to leave. Habit is what put the last one here: `org.junit`
 * imports are what a JVM developer types, and nothing said not to.
 */
class JvmOnlyTestsTest {

  @Test
  fun `test every JVM-only engine test is listed with a reason`() {
    val root = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
      .firstOrNull { Files.exists(it.resolve("settings.gradle.kts")) }
    assertTrue(root != null, "could not find the repository root")

    val directory = root!!.resolve("vim-engine/src/jvmTest")
    val found = Files.walk(directory).use { paths ->
      paths.filter { it.extension == "kt" }
        .map { it.name.removeSuffix(".kt") }
        .filter { it != "JvmOnlyTestsTest" }
        .sorted()
        .toList()
    }

    assertTrue(found.size > 10, "only ${found.size} files were found, so this has stopped reading the directory")
    assertEquals(REASONS.keys.sorted(), found)
  }

  /**
   * And the other side of it: `commonTest` is where a test belongs unless one of those reasons
   * applies, and it is the larger of the two. Without this the list above would be satisfied by
   * moving everything into `jvmTest` and writing a reason for each.
   */
  @Test
  fun `test most of the engine's tests run on both targets`() {
    val root = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
      .firstOrNull { Files.exists(it.resolve("settings.gradle.kts")) }!!

    fun count(source: String) = Files.walk(root.resolve("vim-engine/src/$source")).use { paths ->
      paths.filter { it.extension == "kt" }.count()
    }

    assertTrue(
      count("commonTest") > count("jvmTest"),
      "more of the engine's tests are JVM-only than run on both targets, which is backwards",
    )
  }

  private companion object {
    /**
     * Every file, and what keeps it here. Three kinds, and it is worth seeing them separated.
     *
     * Most are *differential*: they check the engine's own implementation against the JDK's, which
     * is the thing being replaced. Those cannot run where the reference does not exist.
     *
     * Some need reflection, which Kotlin/JS does not have in the form they use.
     *
     * Two need JUnit 5 features `kotlin.test` has no equivalent of. Those are the ones that could
     * move if somebody rewrote them, and they are marked so that nobody has to work it out again.
     */
    val REASONS: Map<String, String> = mapOf(
      "Arguments" to "builds JUnit 5 Arguments for the two @MethodSource tests below",
      "CharacterHelperTest" to "differential against java.lang.Character",
      "CodePointsTest" to "differential against java.lang.Character's surrogate pair handling",
      "CodeWrapperTest" to "JUnit 5 @TestFactory and DynamicTest; could move if rewritten",
      "ConvertToKotlinTypeTest" to "kotlin.reflect typeOf over Vim data types",
      "ConvertToVimDataTypeTest" to "kotlin.reflect typeOf over Vim data types",
      "DigraphUnicodeBlockTest" to "differential against java.lang.Character.UnicodeBlock",
      "ExCommandConstructorInvariantsTest" to "kotlin.reflect over every ex command's constructor",
      "FiletypePresetsTest" to "JUnit 5 assertAll and Executable; could move if rewritten",
      "ForbiddenApiTest" to "walks the source tree with java.nio.file",
      "GraphemesTest" to "reads Unicode's own break-test data files with java.nio.file",
      "JdkCollectionShimsTest" to "differential against java.util.StringTokenizer and Collections",
      "LambdaTests" to "JUnit 5 @MethodSource; could move if the combinations were written out",
      "JdkKeyStrokeParityTest" to "differential against javax.swing.KeyStroke",
      "NumbersDifferentialTest" to "differential against java.math.BigInteger",
      "PlatformClassNameTest" to "pins platformClassName to Class.getName semantics",
      "RightToLeftTest" to "differential against java.lang.Character.getDirectionality",
      "StringKTypeEquivalenceTest" to "kotlin.reflect KType equivalence",
      "VimPathExpansionTest" to "sets and reads real environment variables through System",
      "VimRegexTest" to "JUnit 5 @Nested; could move if the nesting were flattened",
      "TernaryExpressionTests" to "JUnit 5 @MethodSource; could move if the combinations were written out",
    )
  }
}
