/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.highlight
import com.maddyhome.idea.vim.annotations.TestOnly

/**
 * The highlight groups `:highlight` has been told about, and nothing else.
 *
 * This is deliberately not a picture of what the editor is painting. Vim's own table holds every
 * group its syntax files ever mentioned, with a default for each; neither of this fork's hosts
 * highlights with Vim's syntax engine, so there are no defaults to hold and no `Comment` here
 * until somebody writes one. What lives here is exactly the user's `:highlight` commands.
 *
 * That makes the absent case the common one, and it has to stay distinguishable from two others.
 * A name nobody has mentioned resolves to null, and a host answers null with its own theme - which
 * is what makes `:match Search /x/` work in a session that never defined `Search`. A name that was
 * *disabled* - `:hi Search NONE`, `:hi clear Search` - resolves to [HighlightAttributes.NONE],
 * which paints nothing, because Vim says disabling a group is explicitly not the same as putting
 * it back to its default. Those two collapse into each other in any design that stores only
 * attributes, so the entry is a small sealed type instead.
 *
 * Settings merge. Vim's word for it: "all settings that are not included remain the same, only the
 * specified field is used" - so `:hi Todo guifg=Red` after `:hi Todo gui=bold` leaves a bold red
 * group and not a red one. They are kept as the raw text that was typed rather than as resolved
 * colours, because `:hi Todo` has to print back what was set, and `ctermfg=4` printed as `#0000ee`
 * would be a different answer to the question.
 */
object Highlights {

  /** The keys `:highlight` accepts, in the order Vim lists them back. */
  val KEYS: List<String> = listOf(
    "term", "start", "stop", "cterm", "ctermfg", "ctermbg", "ctermul", "ctermfont",
    "gui", "font", "guifg", "guibg", "guisp", "blend",
  )

  private sealed interface Entry {
    /** True when `:highlight default` put this here, so a later `default` may still be ignored. */
    val fromDefault: Boolean

    data class Defined(val settings: Map<String, String>, override val fromDefault: Boolean) : Entry
    data class Linked(val to: String, override val fromDefault: Boolean) : Entry
    data class Disabled(override val fromDefault: Boolean = false) : Entry
  }

  /** Keyed by the lower-cased name, because Vim's group names are case-insensitive. */
  private val entries = mutableMapOf<String, Entry>()

  /** The spelling each group was first given, so a listing reads back the way it was written. */
  private val spellings = mutableMapOf<String, String>()

  /** What `:highlight default` does when the group is already spoken for: nothing at all. */
  private fun alreadySpokenFor(key: String): Boolean = entries[key] != null

  /**
   * Merges [changes] into [name]'s settings. A null value is Vim's `NONE`: that key goes away.
   *
   * Returns false when nothing was done because [isDefault] was given and the group already had
   * something - the whole point of `:highlight default`, which lets a syntax file state a colour
   * without overruling the one in the user's vimrc.
   */
  fun define(name: String, changes: Map<String, String?>, isDefault: Boolean = false): Boolean {
    val key = name.lowercase()
    if (isDefault && alreadySpokenFor(key)) return false
    spellings.getOrPut(key) { name }

    // A link is not a setting, so defining over one replaces it rather than merging into it -
    // "as soon as you use a :highlight command for a linked group, the link is removed".
    val existing = (entries[key] as? Entry.Defined)?.settings ?: emptyMap()
    val merged = existing.toMutableMap()
    for ((setting, value) in changes) {
      if (value == null) merged.remove(setting) else merged[setting] = value
    }
    entries[key] = if (merged.isEmpty()) Entry.Disabled(isDefault) else Entry.Defined(merged, isDefault)
    return true
  }

  /** The three ways `:highlight link` can end. */
  enum class LinkResult { LINKED, IGNORED_DEFAULT, HAS_SETTINGS }

  /**
   * Links [name] to [to], or to nothing when [to] is null - Vim's `:hi link {group} NONE`.
   *
   * A group that already has settings of its own is not linked, because a link would silently
   * throw them away; `:hi!` says to do it anyway. Vim reports that refusal as `E414` and this
   * returns it instead of throwing, so the caller can stay quiet for a `default` link, which is
   * what every syntax file in the world uses.
   */
  fun link(name: String, to: String?, isDefault: Boolean = false, force: Boolean = false): LinkResult {
    val key = name.lowercase()
    val existing = entries[key]
    if (isDefault && existing != null) return LinkResult.IGNORED_DEFAULT
    if (!force && existing is Entry.Defined) return LinkResult.HAS_SETTINGS

    spellings.getOrPut(key) { name }
    entries[key] = if (to == null) Entry.Disabled(isDefault) else Entry.Linked(to, isDefault)
    return LinkResult.LINKED
  }

  /** `:hi clear {group}` and `:hi {group} NONE` - the group stops painting, and stays known. */
  fun disable(name: String) {
    val key = name.lowercase()
    spellings.getOrPut(key) { name }
    entries[key] = Entry.Disabled()
  }

  /** `:hi clear` - every group goes back to the host's own idea of it. */
  fun clearAll() {
    entries.clear()
    spellings.clear()
  }

  /** True when [name] has been mentioned at all, which is what `:hi {group}` needs to know. */
  fun isKnown(name: String): Boolean = entries.containsKey(name.lowercase())

  /** The names that have been mentioned, in the order they were first mentioned. */
  fun names(): List<String> = entries.keys.map { spellings[it] ?: it }

  /**
   * [name] as a host receives it: the name asked for, and what it resolved to, or null for neither.
   *
   * The depth limit is for a link that eventually points at itself. Vim refuses to make such a
   * link at all; doing that here would mean walking the chain on every `:hi link`, and a session
   * that has built a loop is better served by a group that paints nothing than by one that hangs
   * the editor on the next keystroke.
   */
  fun group(name: String): HighlightGroup = HighlightGroup(name, resolve(name, depth = 0))

  private fun resolve(name: String, depth: Int): HighlightAttributes? {
    if (depth > MAX_LINK_DEPTH) return HighlightAttributes.NONE
    return when (val entry = entries[name.lowercase()]) {
      null -> null
      is Entry.Disabled -> HighlightAttributes.NONE
      is Entry.Linked -> resolve(entry.to, depth + 1)
      is Entry.Defined -> attributesOf(entry.settings)
    }
  }

  /**
   * One line of `:highlight` output, or null when the group is unknown.
   *
   * Vim's own layout: the name, three spaces of `xxx` shown in the group's colours, then either
   * the settings, `links to {group}`, or `cleared`. The `xxx` is a swatch in Vim and plain text
   * here, since the output panel neither host offers is not a place colours can be shown.
   */
  fun describe(name: String): String? {
    val key = name.lowercase()
    val entry = entries[key] ?: return null
    val spelling = spellings[key] ?: name
    val body = when (entry) {
      is Entry.Disabled -> "cleared"
      is Entry.Linked -> "links to ${entry.to}"
      is Entry.Defined -> KEYS.mapNotNull { setting ->
        entry.settings[setting]?.let { "$setting=$it" }
      }.joinToString(" ")
    }
    return spelling.padEnd(NAME_WIDTH) + "xxx " + body
  }

  /** Collapses Vim's three attribute sets and two colour notations into one paintable answer. */
  private fun attributesOf(settings: Map<String, String>): HighlightAttributes {
    // `gui` first because both hosts are graphical, then `cterm`, then `term`. Vim in a GUI reads
    // only `gui` and ignores the other two, and that is worth diverging from: a great many configs
    // set nothing but `cterm`, and honouring Vim exactly would show those users a group that has
    // clearly been defined and paints nothing.
    val attributes = (settings["gui"] ?: settings["cterm"] ?: settings["term"] ?: "")
      .split(',').map { it.trim().lowercase() }.toSet()

    return HighlightAttributes(
      foreground = colour(settings, "guifg", "ctermfg"),
      background = colour(settings, "guibg", "ctermbg"),
      special = colour(settings, "guisp", "ctermul"),
      bold = "bold" in attributes,
      italic = "italic" in attributes,
      standout = "standout" in attributes,
      reverse = "reverse" in attributes || "inverse" in attributes,
      strikethrough = "strikethrough" in attributes,
      underline = when {
        "undercurl" in attributes -> UnderlineStyle.CURL
        "underdouble" in attributes -> UnderlineStyle.DOUBLE
        "underdotted" in attributes -> UnderlineStyle.DOTTED
        "underdashed" in attributes -> UnderlineStyle.DASHED
        "underline" in attributes -> UnderlineStyle.STRAIGHT
        else -> UnderlineStyle.NONE
      },
    )
  }

  private fun colour(settings: Map<String, String>, guiKey: String, ctermKey: String): String? {
    settings[guiKey]?.let { return if (isTheEditorsOwn(it)) null else HighlightColors.gui(it) }
    settings[ctermKey]?.let { return if (isTheEditorsOwn(it)) null else HighlightColors.cterm(it) }
    return null
  }

  /**
   * `fg`, `bg` and `ul` name the `Normal` group's colours, which here are the theme's.
   *
   * Vim resolves them at the moment the command runs, against colours it knows because it drew
   * them. A host that draws with a theme it did not choose cannot answer, and painting the text in
   * the colour it already has is the same as not painting it - so these become "leave it alone",
   * which is true rather than approximate.
   */
  private fun isTheEditorsOwn(value: String): Boolean =
    value.lowercase() in setOf("fg", "bg", "ul", "foreground", "background")

  @TestOnly
  fun reset() {
    clearAll()
  }

  private const val NAME_WIDTH = 15
  private const val MAX_LINK_DEPTH = 32
}
