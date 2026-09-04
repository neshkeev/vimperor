/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.profile

import com.maddyhome.idea.vim.annotations.TestOnly
import com.maddyhome.idea.vim.helper.currentTimeMillis

/**
 * `:profile` - how long the Vimscript functions in this session took.
 *
 * Vim profiles functions and scripts; this profiles functions, which is where a slow `.ideavimrc`
 * actually is. A script's own lines run once at startup and are visible in the load time; a
 * function called from a mapping runs on every keystroke that uses it, and that is the thing worth
 * timing and the thing nobody can time by watching.
 *
 * Wall-clock milliseconds, from the one place a defined function is called. Vim reports
 * microseconds and separates "total" from "self" - time in a function including and excluding what
 * it called - and both are here: [total] is measured across the whole call and [self] subtracts
 * whatever a nested call charged, which is what makes a recursive or delegating function readable
 * rather than counted twice.
 *
 * Only the functions [start] was told to watch, so profiling an editor plugin's own function does
 * not drown in the engine's. `:profile func *` watches everything, which is Vim's spelling too.
 */
object Profile {

  /** One function's tally, in the shape Vim's `:profile dump` prints. */
  data class Entry(
    val name: String,
    var count: Int = 0,
    var total: Long = 0,
    var self: Long = 0,
  )

  private val entries = linkedMapOf<String, Entry>()
  private var patterns = mutableListOf<String>()

  /** Where a `dump` writes when it is not given a file. Null until `:profile start` names one. */
  var destination: String? = null
    private set

  var isRunning: Boolean = false
    private set

  /**
   * The stack of calls in progress, holding how much each has already been charged by its callees.
   *
   * The whole reason "self" can be computed at all: when a nested call finishes it adds its total
   * to whatever is above it on this stack, and the caller subtracts that from its own.
   */
  private val nested = mutableListOf<Long>()

  fun start(file: String?) {
    destination = file
    isRunning = true
  }

  fun pause() {
    isRunning = false
  }

  fun resume() {
    isRunning = true
  }

  /** `:profile func {pattern}`. A `*` matches any run of characters, as it does in `:actionlist`. */
  fun watch(pattern: String) {
    if (pattern !in patterns) patterns += pattern
  }

  fun isWatched(name: String): Boolean = patterns.any { matches(name, it) }

  fun entries(): List<Entry> = entries.values.toList()

  /**
   * Times one call, and charges its caller for it.
   *
   * Returns whatever [body] returned, so a caller wraps rather than branches: profiling that is off
   * costs the one boolean at the top and nothing else.
   */
  inline fun <T> timing(name: String, body: () -> T): T {
    if (!isRunning || !isWatched(name)) return body()
    val started = currentTimeMillis()
    push()
    try {
      return body()
    } finally {
      record(name, currentTimeMillis() - started)
    }
  }

  /** Not private only because [timing] is inline; nothing else should call it. */
  fun push() {
    nested += 0L
  }

  /** Not private only because [timing] is inline; nothing else should call it. */
  fun record(name: String, elapsed: Long) {
    val chargedByCallees = nested.removeAt(nested.size - 1)
    if (nested.isNotEmpty()) nested[nested.size - 1] = nested[nested.size - 1] + elapsed

    val entry = entries.getOrPut(name) { Entry(name) }
    entry.count++
    entry.total += elapsed
    entry.self += elapsed - chargedByCallees
  }

  /**
   * The report, in Vim's shape: the tallies, then the same list sorted by self time.
   *
   * Sorted twice on purpose. The first table answers "what did this function cost" and the second
   * answers "where did the time go", and they are different questions - a function called once that
   * takes a second and one called a thousand times that takes a millisecond each are next to each
   * other in one and far apart in the other.
   */
  fun dump(): String = buildString {
    if (entries.isEmpty()) {
      appendLine("Nothing was profiled. `:profile func {pattern}` says what to watch.")
      return@buildString
    }

    appendLine("count  total (ms)   self (ms)  function")
    for (entry in entries.values) {
      appendLine(line(entry))
    }
    appendLine()
    appendLine("FUNCTIONS SORTED ON SELF TIME")
    appendLine("count  total (ms)   self (ms)  function")
    for (entry in entries.values.sortedByDescending { it.self }) {
      appendLine(line(entry))
    }
  }

  private fun line(entry: Entry): String =
    entry.count.toString().padStart(5) + "  " +
      entry.total.toString().padStart(10) + "  " +
      entry.self.toString().padStart(10) + "  " +
      entry.name

  /** `:actionlist`'s rule, which is IdeaVim's everywhere a `*` appears outside a pattern. */
  private fun matches(name: String, pattern: String): Boolean =
    pattern.split("*").filter { it.isNotEmpty() }.all { it in name } ||
      pattern.trim() == "*"

  @TestOnly
  fun reset() {
    entries.clear()
    patterns = mutableListOf()
    nested.clear()
    destination = null
    isRunning = false
  }
}
