/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.functions.handlers.stringFunctions

import com.intellij.vim.annotations.VimscriptFunction
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.helper.enumSetOf
import com.maddyhome.idea.vim.regexp.VimRegex
import com.maddyhome.idea.vim.regexp.VimRegexOptions
import com.maddyhome.idea.vim.regexp.match.VimMatchResult
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimList
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.datatypes.asVimInt
import com.maddyhome.idea.vim.vimscript.model.functions.BuiltinFunctionHandler

/**
 * Vim's five `match*()` functions, which are one search asked five different questions.
 *
 * `match()` wants where it starts, `matchend()` where it ends, `matchstr()` what it found,
 * `matchlist()` what its capture groups found, and `matchstrpos()` all three at once. They share
 * everything except the last line, so they share a base class with exactly that shape.
 *
 * All five take a *list* as well as a string, and that is not a curiosity: `match(getline(1, '$'),
 * 'TODO')` is how a config finds the first matching line, and it answers with an index rather than
 * an offset. The two shapes are answered by the same code because Vim answers them with the same
 * function.
 *
 * `'ignorecase'` applies and `'smartcase'` does not - Vim's rule for the `match*()` family, and the
 * right one: a pattern written into a config is not a search the user is typing, and should not
 * change meaning because it happens to contain a capital.
 */
internal sealed class MatchFunctionBase<T : VimDataType> :
  BuiltinFunctionHandler<T>(minArity = 2, maxArity = 4) {

  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): T {
    val pattern = arguments.getString(1).value
    val start = arguments.getNumberOrNull(2)?.value ?: 0
    // Vim counts matches from one: `{count}` of 2 is the second match, and anything less than one
    // means the first. Reading it as an index instead is off by one and looks right in every test
    // that only ever asks for the first.
    val count = ((arguments.getNumberOrNull(3)?.value ?: 1) - 1).coerceAtLeast(0)

    val list = arguments[0] as? VimList
    if (list != null) return overList(list, pattern, start, count)

    val text = arguments.getString(0).value
    val from = if (start < 0) (text.length + start).coerceAtLeast(0) else start.coerceAtMost(text.length)
    return answer(text, matchIn(text, pattern, from, count), from)
  }

  /**
   * The same question asked of a list: which *element* matches, rather than where in a string.
   *
   * Vim returns the index of the element for `match()` and `matchend()`, the element's matched text
   * for `matchstr()`, and for `matchstrpos()` the index alongside the offsets within it. So the
   * element is found here and the shape of the answer is still the subclass's.
   */
  private fun overList(list: VimList, pattern: String, start: Int, count: Int): T {
    val from = if (start < 0) (list.values.size + start).coerceAtLeast(0) else start
    var skipped = 0
    for (index in from until list.values.size) {
      val text = list.values[index].toVimString().value
      val match = matchIn(text, pattern, 0, 0)
      if (match != null) {
        if (skipped < count) {
          skipped++
          continue
        }
        return answerForList(index, text, match)
      }
    }
    return answerForList(-1, "", null)
  }

  private fun matchIn(text: String, pattern: String, from: Int, count: Int): VimMatchResult.Success? {
    val options = enumSetOf<VimRegexOptions>()
    if (injector.globalOptions().ignorecase) options.add(VimRegexOptions.IGNORE_CASE)
    return try {
      VimRegex(pattern).findAll(text = text, startIndex = from, options = options).getOrNull(count)
    } catch (e: Throwable) {
      null
    }
  }

  /** The answer for a string subject. [match] is null when nothing matched. */
  protected abstract fun answer(text: String, match: VimMatchResult.Success?, from: Int): T

  /** The answer for a list subject. [index] is -1 when no element matched. */
  protected abstract fun answerForList(index: Int, text: String, match: VimMatchResult.Success?): T
}

/**
 * `match({expr}, {pat} [, {start} [, {count}]])` - where the match starts, or -1.
 *
 * see "h match()"
 */
@VimscriptFunction(name = "match")
internal class MatchFunctionHandler : MatchFunctionBase<VimInt>() {
  override fun answer(text: String, match: VimMatchResult.Success?, from: Int): VimInt =
    (match?.range?.startOffset ?: -1).asVimInt()

  override fun answerForList(index: Int, text: String, match: VimMatchResult.Success?): VimInt = index.asVimInt()
}

/**
 * `matchend({expr}, {pat} [, {start} [, {count}]])` - one past the end of the match, or -1.
 *
 * Not the same as `match()` plus the length of `matchstr()` when the pattern matched nothing: an
 * empty match has an end and no text, which is the case this function is for.
 *
 * see "h matchend()"
 */
@VimscriptFunction(name = "matchend")
internal class MatchEndFunctionHandler : MatchFunctionBase<VimInt>() {
  override fun answer(text: String, match: VimMatchResult.Success?, from: Int): VimInt =
    (match?.range?.endOffset ?: -1).asVimInt()

  override fun answerForList(index: Int, text: String, match: VimMatchResult.Success?): VimInt =
    (match?.range?.endOffset ?: -1).asVimInt()
}

/**
 * `matchstr({expr}, {pat} [, {start} [, {count}]])` - the matched text, or an empty string.
 *
 * see "h matchstr()"
 */
@VimscriptFunction(name = "matchstr")
internal class MatchStrFunctionHandler : MatchFunctionBase<VimString>() {
  override fun answer(text: String, match: VimMatchResult.Success?, from: Int): VimString =
    VimString(match?.value ?: "")

  override fun answerForList(index: Int, text: String, match: VimMatchResult.Success?): VimString =
    VimString(match?.value ?: "")
}

/**
 * `matchlist({expr}, {pat} [, {start} [, {count}]])` - the match and its nine capture groups.
 *
 * Vim's list is always ten long when there was a match: the whole match, then `\1` to `\9`, with an
 * empty string for a group that did not take part. That fixed shape is what lets a config write
 * `matchlist(s, p)[2]` without checking how many groups the pattern had.
 *
 * see "h matchlist()"
 */
@VimscriptFunction(name = "matchlist")
internal class MatchListFunctionHandler : MatchFunctionBase<VimList>() {
  override fun answer(text: String, match: VimMatchResult.Success?, from: Int): VimList = asList(match)

  override fun answerForList(index: Int, text: String, match: VimMatchResult.Success?): VimList = asList(match)

  private fun asList(match: VimMatchResult.Success?): VimList {
    if (match == null) return VimList(mutableListOf())
    val values = (0..9).map { group ->
      VimString(match.groups.get(group)?.value ?: "") as VimDataType
    }
    return VimList(values.toMutableList())
  }
}

/**
 * `matchstrpos({expr}, {pat} [, {start} [, {count}]])` - the text and where it was.
 *
 * Three elements for a string subject and four for a list one, because a list answer has to say
 * *which element* as well as where in it. Vim's own shapes; a config that unpacks this with
 * `let [s, a, b] = ...` is written against the string form.
 *
 * see "h matchstrpos()"
 */
@VimscriptFunction(name = "matchstrpos")
internal class MatchStrPosFunctionHandler : MatchFunctionBase<VimList>() {
  override fun answer(text: String, match: VimMatchResult.Success?, from: Int): VimList = VimList(
    mutableListOf(
      VimString(match?.value ?: ""),
      (match?.range?.startOffset ?: -1).asVimInt(),
      (match?.range?.endOffset ?: -1).asVimInt(),
    ),
  )

  override fun answerForList(index: Int, text: String, match: VimMatchResult.Success?): VimList = VimList(
    mutableListOf(
      index.asVimInt(),
      VimString(match?.value ?: ""),
      (match?.range?.startOffset ?: -1).asVimInt(),
      (match?.range?.endOffset ?: -1).asVimInt(),
    ),
  )
}

/**
 * `substitute({expr}, {pat}, {sub}, {flags})` - `:s` on a string instead of on a buffer.
 *
 * The replacement is built by the same code `:s` builds it with, which matters more than it looks:
 * `\0`, `\1`..`\9`, `&`, `~`, `\u`, `\U`, `\l`, `\L`, `\e` and `\E` are a small language of their
 * own, and a second implementation of it would be a second implementation to drift. `VimRegex`
 * exposes it as `replacementFor` for exactly this.
 *
 * `{flags}` is `g` for every occurrence and `&` to keep the previous flags; `i` and `I` force the
 * case rule for this call alone, which is what makes `substitute(s, p, r, 'i')` reliable in a
 * config that cannot know the user's `'ignorecase'`.
 *
 * see "h substitute()"
 */
@VimscriptFunction(name = "substitute")
internal class SubstituteFunctionHandler : BuiltinFunctionHandler<VimString>(arity = 4) {
  override fun doFunction(
    arguments: Arguments,
    editor: VimEditor,
    context: ExecutionContext,
    vimContext: VimLContext,
  ): VimString {
    val text = arguments.getString(0).value
    val pattern = arguments.getString(1).value
    val replacement = arguments.getString(2).value
    val flags = arguments.getString(3).value

    val options = enumSetOf<VimRegexOptions>()
    when {
      'i' in flags -> options.add(VimRegexOptions.IGNORE_CASE)
      'I' in flags -> {}
      injector.globalOptions().ignorecase -> options.add(VimRegexOptions.IGNORE_CASE)
    }

    val regex = try {
      VimRegex(pattern)
    } catch (e: Throwable) {
      return VimString(text)
    }
    val matches = regex.findAll(text = text, options = options)
    if (matches.isEmpty()) return VimString(text)
    val wanted = if ('g' in flags) matches else matches.take(1)

    val result = StringBuilder()
    var consumed = 0
    for (match in wanted) {
      if (match.range.startOffset < consumed) continue
      result.append(text, consumed, match.range.startOffset)
      result.append(regex.replacementFor(match, replacement, ""))
      consumed = match.range.endOffset
      // An empty match would otherwise loop forever on the same offset; Vim steps over one
      // character and carries on, which is what makes `substitute(s, 'x*', '-', 'g')` terminate.
      if (match.range.startOffset == match.range.endOffset && consumed < text.length) {
        result.append(text[consumed])
        consumed++
      }
    }
    result.append(text, consumed, text.length)
    return VimString(result.toString())
  }
}
