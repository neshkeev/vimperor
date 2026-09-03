/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.highlight

/**
 * Turning what a `~/.vimrc` writes after `guifg=` or `ctermfg=` into a colour an editor can draw.
 *
 * There are three notations and they do not mean the same thing:
 *
 *  - `#rrggbb`, which means itself.
 *  - a GUI colour name - `guifg=DarkCyan` - which is an X11 name. Vim looks these up in the X
 *    server's `rgb.txt` where there is one and carries [GUI_NAMES] where there is not; that table
 *    is Vim's own, copied from `gui_get_color_cmn`, so a name resolves here to the value Vim would
 *    have used on Windows and GTK.
 *  - a terminal colour - `ctermfg=DarkCyan`, `ctermfg=81` - which is an *index* into a palette the
 *    terminal owns, not a colour. There is no terminal here, so the index is resolved through
 *    xterm's defaults ([XTERM_256]), which is the palette almost every terminal ships with and the
 *    one the numbers in a config were chosen against.
 *
 * The name-to-index step is the part that surprises people, and it is Vim's rule rather than a
 * simplification: `ctermfg=Blue` is *not* index 4. Vim numbers the colour names after the
 * MS-Windows console, where `Blue` is the bright one, so on an ansi terminal it becomes 12. Vim's
 * own table spells this as an NR-8 column with a star meaning "add 8"; [CTERM_NAMES] holds the
 * result of that addition, so `Blue` is 12 here for the same reason it is 12 in a terminal.
 */
object HighlightColors {

  /** `#rrggbb` for [name], or null if it is not a colour this knows. `NONE` is the caller's job. */
  fun gui(name: String): String? {
    if (isHexColour(name)) return name.lowercase()
    return GUI_NAMES[name.lowercase()]
  }

  /** `#rrggbb` for a `cterm*` argument: an index 0-255, or one of Vim's colour names. */
  fun cterm(name: String): String? {
    if (isHexColour(name)) return name.lowercase()
    val index = name.toIntOrNull() ?: CTERM_NAMES[name.lowercase()] ?: return null
    return XTERM_256.getOrNull(index)
  }

  private fun isHexColour(text: String): Boolean =
    text.length == 7 && text[0] == '#' && text.drop(1).all { it in "0123456789abcdefABCDEF" }

  /**
   * Vim's built-in GUI colour names, from `gui_get_color_cmn` in `gui.c`.
   *
   * Not every X11 name - Vim itself only carries these when there is no `rgb.txt` to read, and a
   * config that names `PapayaWhip` is asking a question this fork cannot answer either way.
   */
  private val GUI_NAMES: Map<String, String> = mapOf(
    "black" to "#000000",
    "blue" to "#0000ff",
    "brown" to "#a52a2a",
    "cyan" to "#00ffff",
    "darkblue" to "#00008b",
    "darkcyan" to "#008b8b",
    "darkgray" to "#a9a9a9",
    "darkgreen" to "#006400",
    "darkgrey" to "#a9a9a9",
    "darkmagenta" to "#8b008b",
    "darkred" to "#8b0000",
    "darkyellow" to "#bbbb00",
    "gray" to "#bebebe",
    "green" to "#00ff00",
    "grey" to "#bebebe",
    "grey40" to "#666666",
    "grey50" to "#7f7f7f",
    "grey90" to "#e5e5e5",
    "lightblue" to "#add8e6",
    "lightcyan" to "#e0ffff",
    "lightgray" to "#d3d3d3",
    "lightgreen" to "#90ee90",
    "lightgrey" to "#d3d3d3",
    "lightmagenta" to "#ff8bff",
    "lightred" to "#ff8b8b",
    "lightyellow" to "#ffffe0",
    "magenta" to "#ff00ff",
    "orange" to "#ffa500",
    "purple" to "#a020f0",
    "red" to "#ff0000",
    "seagreen" to "#2e8b57",
    "white" to "#ffffff",
    "yellow" to "#ffff00",
  )

  /**
   * Vim's `cterm-colors` table, already resolved for an ansi terminal.
   *
   * Vim documents two columns, NR-16 and NR-8, and says an xterm uses NR-8 with the star meaning
   * "add 8". These are those sums, which is why `Blue` is 12 and `DarkBlue` is 4.
   */
  private val CTERM_NAMES: Map<String, Int> = mapOf(
    "black" to 0,
    "darkblue" to 4,
    "darkgreen" to 2,
    "darkcyan" to 6,
    "darkred" to 1,
    "darkmagenta" to 5,
    "brown" to 3,
    "darkyellow" to 3,
    "lightgray" to 7,
    "lightgrey" to 7,
    "gray" to 7,
    "grey" to 7,
    "darkgray" to 8,
    "darkgrey" to 8,
    "blue" to 12,
    "lightblue" to 12,
    "green" to 10,
    "lightgreen" to 10,
    "cyan" to 14,
    "lightcyan" to 14,
    "red" to 9,
    "lightred" to 9,
    "magenta" to 13,
    "lightmagenta" to 13,
    "yellow" to 11,
    "lightyellow" to 11,
    "white" to 15,
  )

  /**
   * xterm's 256 colours: sixteen the terminal chooses, a 6x6x6 cube, and twenty-four greys.
   *
   * Only the first sixteen are really xterm's - they are the ones a colour scheme redefines - and
   * the rest are fixed by the formula every 256-colour terminal implements. The cube's six levels
   * are 0 and then 95 plus 40 each step, which is not a rounding of 255/5 but the actual constants
   * in xterm's source; a config that says `ctermbg=234` picked its grey from this table.
   */
  private val XTERM_256: List<String> = buildList {
    addAll(
      listOf(
        "#000000", "#cd0000", "#00cd00", "#cdcd00", "#0000ee", "#cd00cd", "#00cdcd", "#e5e5e5",
        "#7f7f7f", "#ff0000", "#00ff00", "#ffff00", "#5c5cff", "#ff00ff", "#00ffff", "#ffffff",
      ),
    )
    val levels = listOf(0, 95, 135, 175, 215, 255)
    for (r in levels) for (g in levels) for (b in levels) add(hex(r, g, b))
    for (step in 0 until 24) {
      val grey = 8 + step * 10
      add(hex(grey, grey, grey))
    }
  }

  private fun hex(r: Int, g: Int, b: Int): String = "#" + byte(r) + byte(g) + byte(b)

  private fun byte(value: Int): String = value.toString(16).padStart(2, '0')
}
