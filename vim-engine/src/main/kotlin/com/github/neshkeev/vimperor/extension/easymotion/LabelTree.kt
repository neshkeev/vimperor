/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.extension.easymotion

/*
 * Which keys reach which target.
 *
 * vim-easymotion's "single-key priority" grouping - `g:EasyMotion_grouping` 1, its default - written
 * from its documentation (`:h EasyMotion_grouping`) and its `s:GroupingAlgorithmSCTree`.
 * vim-easymotion is MIT-licensed, by Kim Silkebækken and haya14busa.
 *
 * ## The rule
 *
 * Targets arrive nearest first, and as many as possible get a key of their own. When there are more
 * targets than keys, the *last* keys stop being targets and become groups: typing one relabels only
 * what it covers, with the same keys again. So `g:EasyMotion_keys` is an ordered list, and the
 * documentation's advice follows from that - put the keys you would most like to type twice last.
 *
 * With `abcdef` and nine targets:
 *
 *     a b c d e f f f f
 *
 * `a` to `e` jump, and `f` opens a group of four, labelled `a` to `d`.
 *
 * ## Why the counting goes backwards
 *
 * Turning a key from a target into a group gains `keys - 1` places: it covers a whole set of keys
 * and gives up the one target it had. So the counts are handed out a level at a time, starting from
 * the last key, until the targets run out. Counted that way the keys that jump come before the keys
 * that open groups, and group sizes never shrink along the key order - so the targets that get a
 * single key are always the nearest ones, which is the promise `:h EasyMotion_grouping` makes.
 *
 * It promises nothing about longer labels. With few keys a nearer group can nest deeper than a
 * farther one: two keys and six targets give two groups of three, each with one target a level
 * shallower than the other two, so the lengths run 2, 3, 3, 2, 3, 3. That is vim-easymotion's
 * grouping too, and a test here once claimed otherwise and was wrong.
 *
 * AceJump assigns its tags by a different method, and it is GPL-licensed; nothing here follows it.
 */

/** A key's meaning at one level: jump, or open a smaller set. */
internal sealed interface LabelNode<out T> {
  /** A key that jumps to [target]. */
  data class Target<out T>(val target: T) : LabelNode<T>

  /** A key that opens [children], labelled again from the start of the keys. */
  data class Group<out T>(val children: Map<Char, LabelNode<T>>) : LabelNode<T>
}

/**
 * [targets], nearest first, labelled with [keys] in order.
 *
 * At least two keys, none repeated: one key cannot tell two targets apart, and a repeated key would
 * make every target after its first use unreachable.
 */
internal fun <T> groupTargets(targets: List<T>, keys: String): Map<Char, LabelNode<T>> {
  require(keys.length >= 2) { "EasyMotion needs at least two keys to label targets with, got \"$keys\"" }
  require(keys.toSet().size == keys.length) { "EasyMotion keys must not repeat, got \"$keys\"" }
  if (targets.isEmpty()) return emptyMap()

  val tree = LinkedHashMap<Char, LabelNode<T>>()
  var key = 0
  var next = 0
  for (count in countsPerKey(targets.size, keys.length)) {
    // A key that covers nothing is passed over *without* using up a key. See [countsPerKey].
    if (count == 0) continue
    tree[keys[key]] = if (count == 1) {
      LabelNode.Target(targets[next])
    } else {
      LabelNode.Group(groupTargets(targets.subList(next, next + count), keys))
    }
    key++
    next += count
  }
  return tree
}

/**
 * How many targets each key covers, in key order.
 *
 * Handed out from the last key backwards, a level at a time: one each first, then `keys - 1` more
 * per key while targets remain. A key reached after they ran out covers nothing, and those are
 * always the leading ones - which [groupTargets] skips without consuming a key, so the keys that are
 * used still start from the first.
 */
private fun countsPerKey(targetCount: Int, keyCount: Int): IntArray {
  val fromLast = IntArray(keyCount)
  var left = targetCount
  var level = 0
  while (left > 0) {
    val covers = if (level == 0) 1 else keyCount - 1
    for (i in 0 until keyCount) {
      fromLast[i] += covers
      left -= covers
      if (left <= 0) {
        // Handed out one too many at this key: give the surplus back.
        fromLast[i] += left
        break
      }
    }
    level++
  }
  return fromLast.reversedArray()
}

/** Every target and the whole key sequence that reaches it, nearest first. */
internal fun <T> pathsOf(tree: Map<Char, LabelNode<T>>): List<Pair<T, String>> {
  val paths = mutableListOf<Pair<T, String>>()
  fun walk(level: Map<Char, LabelNode<T>>, prefix: String) {
    for ((key, node) in level) {
      when (node) {
        is LabelNode.Target -> paths += node.target to prefix + key
        is LabelNode.Group -> walk(node.children, prefix + key)
      }
    }
  }
  walk(tree, "")
  return paths
}

/** What the keys typed so far mean. */
internal sealed interface Resolution<out T> {
  data class Jump<out T>(val target: T) : Resolution<T>

  /** Not there yet: these are what the next key chooses between. */
  data class Partial<out T>(val children: Map<Char, LabelNode<T>>) : Resolution<T>

  /** A key no label starts with, or a key typed after a jump was already decided. */
  data object Invalid : Resolution<Nothing>
}

internal fun <T> resolve(tree: Map<Char, LabelNode<T>>, typed: List<Char>): Resolution<T> {
  var level = tree
  for ((index, key) in typed.withIndex()) {
    when (val node = level[key] ?: return Resolution.Invalid) {
      is LabelNode.Target -> return if (index == typed.lastIndex) Resolution.Jump(node.target) else Resolution.Invalid
      is LabelNode.Group -> level = node.children
    }
  }
  return Resolution.Partial(level)
}
