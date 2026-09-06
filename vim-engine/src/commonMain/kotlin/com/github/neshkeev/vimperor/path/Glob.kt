/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.path
import com.maddyhome.idea.vim.api.injector

/**
 * Finding files by shape rather than by name - one glob, for everything that needs one.
 *
 * `:vimgrep` over a tree and `glob()` over the same tree are the same question, and this file exists so that
 * they cannot answer it differently. Two implementations would agree for a while and then disagree
 * about a double star, or about whether a pattern with no wildcard names a file that is not there,
 * and the disagreement would show up as a config that works in one command and not the other.
 *
 * Vim's rule, and the one thing worth getting right: a single star matches inside one path segment
 * and a double star matches across them, so `src/` + star + `.kt` is one directory and a double
 * star in the middle is a whole tree.
 */
object Glob {

  /**
   * The files [pattern] names, relative to [workingDirectory] when it is not absolute.
   *
   * A pattern with no wildcard in it is the file itself, existing or not - `:vimgrep /x/ nofile`
   * should complain about the file rather than silently search nothing, and that is the caller's
   * decision to make rather than this one's.
   */
  fun expand(pattern: String, workingDirectory: String?): List<String> {
    val absolute = when {
      pattern.startsWith("/") || pattern.startsWith("~") -> pattern
      pattern.length > 2 && pattern[1] == ':' -> pattern
      workingDirectory != null -> "${workingDirectory.trimEnd('/', '\\')}/$pattern"
      else -> pattern
    }

    if (!hasWildcard(absolute)) return listOf(absolute)

    val segments = absolute.split("/").filter { it.isNotEmpty() }
    val root = if (absolute.startsWith("/")) "" else "."
    return walk(root, segments, 0).sorted()
  }

  /** True when [text] holds something for this to expand. */
  fun hasWildcard(text: String): Boolean = '*' in text || '?' in text || '[' in text

  private fun walk(at: String, segments: List<String>, index: Int): List<String> {
    if (index >= segments.size) return if (injector.fileSystem.exists(at)) listOf(at) else emptyList()

    val segment = segments[index]

    // A double star matches here and at every depth below here, which is why it recurses on the
    // same segment index as well as on the next one.
    if (segment == "**") {
      val here = walk(at, segments, index + 1)
      val deeper = injector.fileSystem.listDirectory(at)
        .map { "$at/$it" }
        .filter { injector.fileSystem.isDirectory(it) }
        .flatMap { walk(it, segments, index) }
      return here + deeper
    }

    if (!hasWildcard(segment)) return walk("$at/$segment", segments, index + 1)

    return injector.fileSystem.listDirectory(at)
      .filter { matchesSegment(it, segment) }
      .flatMap { walk("$at/$it", segments, index + 1) }
  }

  /**
   * One path segment against one wildcard segment.
   *
   * A star stops at the separator by never seeing one - the caller has already split the path - so
   * there is nothing here that has to know about directories at all.
   *
   * The classic two-pointer walk with a remembered star, rather than recursion. A recursive matcher
   * takes exponential time on a pattern like `*a*a*a*` against a name that does not match, which is
   * a shape a real project directory produces by accident.
   */
  fun matchesSegment(name: String, pattern: String): Boolean {
    var nameAt = 0
    var patternAt = 0
    var starAt = -1
    var nameAtStar = 0

    while (nameAt < name.length) {
      val onClass = patternAt < pattern.length && pattern[patternAt] == '['
      val classEnd = if (onClass) pattern.indexOf(']', patternAt + 1) else -1
      when {
        patternAt < pattern.length && (pattern[patternAt] == '?' || pattern[patternAt] == name[nameAt]) -> {
          nameAt++
          patternAt++
        }

        onClass && classEnd > 0 && inClass(name[nameAt], pattern.substring(patternAt + 1, classEnd)) -> {
          nameAt++
          patternAt = classEnd + 1
        }

        patternAt < pattern.length && pattern[patternAt] == '*' -> {
          starAt = patternAt
          nameAtStar = nameAt
          patternAt++
        }

        starAt >= 0 -> {
          patternAt = starAt + 1
          nameAtStar++
          nameAt = nameAtStar
        }

        else -> return false
      }
    }
    while (patternAt < pattern.length && pattern[patternAt] == '*') patternAt++
    return patternAt == pattern.length
  }

  /** `[abc]`, `[a-z]` and their negations, which Vim spells with either `^` or `!`. */
  private fun inClass(character: Char, body: String): Boolean {
    val negated = body.startsWith("^") || body.startsWith("!")
    val set = if (negated) body.drop(1) else body
    var index = 0
    var found = false
    while (index < set.length) {
      if (index + 2 < set.length && set[index + 1] == '-') {
        if (character in set[index]..set[index + 2]) found = true
        index += 3
      } else {
        if (character == set[index]) found = true
        index++
      }
    }
    return found != negated
  }

  /**
   * The glob as a Vim pattern, which is what `glob2regpat()` hands back.
   *
   * It exists because Vim has no `fnmatch()`: a config that wants to match a name it already has
   * against a glob turns the glob into a regex and uses `=~` instead.
   */
  fun toPattern(glob: String): String = buildString {
    append('^')
    for (character in glob) {
      when (character) {
        '*' -> append(".*")
        '?' -> append('.')
        '.' -> append("\\.")
        '\\', '~', '^', '$', '/' -> append('\\').append(character)
        else -> append(character)
      }
    }
    append('$')
  }
}
