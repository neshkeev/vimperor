/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.extension.easymotion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The grouping, on its own and on both targets.
 *
 * The one part of easymotion that is pure arithmetic, and the part where a mistake would be hardest
 * to see from outside: a wrong grouping still draws labels and still jumps - to the wrong place, or
 * behind a longer label than it needed. So these check properties across many sizes, not a few trees.
 */
class LabelTreeTest {

  private val defaultKeys = "asdghklqwertyuiopzxcvbnmfj;"

  private val sizes = (1..60) + listOf(100, 300, 750)

  @Test
  fun `test the example in vim-easymotion's documentation`() {
    // `:h EasyMotion_grouping`, keys "abcdef" and nine targets: `a b c d e f f f f`.
    val tree = groupTargets((0 until 9).toList(), "abcdef")

    assertEquals("abcdef", tree.keys.joinToString(""))
    "abcde".forEachIndexed { index, key -> assertEquals(LabelNode.Target(index), tree[key]) }
    val group = assertIs<LabelNode.Group<*>>(tree['f'])
    assertEquals(
      mapOf('a' to LabelNode.Target(5), 'b' to LabelNode.Target(6), 'c' to LabelNode.Target(7), 'd' to LabelNode.Target(8)),
      group.children,
    )
  }

  @Test
  fun `test targets that fit get a key each, from the start of the keys`() {
    assertEquals(
      listOf("one" to "a", "two" to "s", "three" to "d"),
      pathsOf(groupTargets(listOf("one", "two", "three"), defaultKeys)),
    )
  }

  @Test
  fun `test no targets means no labels`() {
    assertEquals(emptyMap<Char, LabelNode<Int>>(), groupTargets(emptyList(), defaultKeys))
  }

  @Test
  fun `test every target is reachable by exactly one label, however many there are`() {
    for (keys in listOf("ab", "abc", "asdf", defaultKeys)) {
      for (count in sizes) {
        val tree = groupTargets((0 until count).toList(), keys)
        val paths = pathsOf(tree)
        assertEquals((0 until count).toList(), paths.map { it.first }, "keys=$keys count=$count")
        assertEquals(paths.size, paths.map { it.second }.toSet().size, "keys=$keys count=$count: a label used twice")
        // Typing a label has to arrive at its own target and nowhere sooner - which also rules out one
        // label being the beginning of another, since the shorter one would jump first.
        for ((target, label) in paths) {
          assertEquals(Resolution.Jump(target), resolve(tree, label.toList()), "keys=$keys count=$count label=$label")
        }
      }
    }
  }

  @Test
  fun `test the single-key labels go to the nearest targets`() {
    // The promise `:h EasyMotion_grouping` makes. Not the stronger one - that a nearer target never
    // has a longer label - which this test first asserted and which does not hold: keys "ab" and six
    // targets give lengths 2, 3, 3, 2, 3, 3, in vim-easymotion's grouping as in this one.
    for (keys in listOf("ab", "abc", "asdf", defaultKeys)) {
      for (count in sizes) {
        val lengths = pathsOf(groupTargets((0 until count).toList(), keys)).map { it.second.length }
        val singles = lengths.count { it == 1 }
        assertEquals(List(singles) { 1 }, lengths.take(singles), "keys=$keys count=$count")
      }
    }
  }

  @Test
  fun `test groups never shrink along the key order`() {
    for (keys in listOf("ab", "abc", "asdf", defaultKeys)) {
      for (count in sizes) {
        val sizesInKeyOrder = groupTargets((0 until count).toList(), keys).values.map { node ->
          if (node is LabelNode.Group<*>) pathsOf(node.children).size else 1
        }
        assertEquals(sizesInKeyOrder.sorted(), sizesInKeyOrder, "keys=$keys count=$count")
      }
    }
  }

  @Test
  fun `test with few keys a nearer group can nest deeper, as vim-easymotion's does`() {
    // Recorded rather than rediscovered: see the comment in LabelTree.kt.
    val lengths = pathsOf(groupTargets((0 until 6).toList(), "ab")).map { it.second.length }
    assertEquals(listOf(2, 3, 3, 2, 3, 3), lengths)
  }

  @Test
  fun `test as many targets as possible jump with one key`() {
    // Twenty-seven keys and thirty targets: one key has to become a group of four, which leaves
    // twenty-six keys that jump. Any other split would give some target a longer label than it needs.
    val lengths = pathsOf(groupTargets((0 until 30).toList(), defaultKeys)).map { it.second.length }
    assertEquals(26, lengths.count { it == 1 })
    assertEquals(4, lengths.count { it == 2 })
  }

  @Test
  fun `test the last key is the one that becomes a group`() {
    assertIs<LabelNode.Group<*>>(groupTargets((0 until 30).toList(), defaultKeys)[';'])
  }

  @Test
  fun `test what typed keys resolve to`() {
    val tree = groupTargets((0 until 9).toList(), "abcdef")

    assertEquals(Resolution.Partial(tree), resolve(tree, emptyList()))
    assertEquals(Resolution.Jump(2), resolve(tree, listOf('c')))
    assertIs<Resolution.Partial<*>>(resolve(tree, listOf('f')))
    assertEquals(Resolution.Jump(7), resolve(tree, listOf('f', 'c')))
    assertEquals(Resolution.Invalid, resolve(tree, listOf('z')))
    assertEquals(Resolution.Invalid, resolve(tree, listOf('c', 'a')), "a key after a jump was already decided")
  }

  @Test
  fun `test one key cannot label anything`() {
    assertFailsWith<IllegalArgumentException> { groupTargets(listOf(1, 2, 3), "a") }
  }

  @Test
  fun `test a repeated key is refused rather than leaving targets unreachable`() {
    assertFailsWith<IllegalArgumentException> { groupTargets(listOf(1, 2, 3), "aab") }
  }
}
