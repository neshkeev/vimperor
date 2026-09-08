/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.api.Options
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.options.OptionAccessScope
import com.maddyhome.idea.vim.options.helpers.LangMapOptionHelper
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `'keyboardlayout'`, pressed rather than parsed.
 *
 * [com.github.neshkeev.vimperor.keyboard.KeyboardLayoutsTest] checks the tables; this checks that a
 * Cyrillic character arriving through VS Code's `type` command reaches the same command a Latin one
 * does. That is not the same question - `'langmap'` translation is gated on the mode and on what
 * the command builder is waiting for, and a register name, a mark name and a count each take a
 * different route to it.
 *
 * Most tests here are differential: the same edit driven twice, once in Latin with no layout set
 * and once in Cyrillic with one, asserting the two agree. A table can be wrong in a way that still
 * looks plausible in an expected-text string; it cannot be wrong in a way that agrees with the
 * Latin keys by accident.
 */
class KeyboardLayoutTest {

  private class Session {
    val fake = FakeEditor("one two three\nfour five six\nseven (eight) nine")
    val host = VimHost(
      sink = object : MessageSink {
        override fun message(text: String?) {}
        override fun error(text: String?) {}
        override fun status(text: String?) {}
      },
      runCommand = { _, _, onDone -> onDone(true) },
    ).also { it.start() }
    val editor = host.editorFor(fake)

    init {
      KeyHandler.getInstance().fullReset(editor)
    }

    fun press(keys: String) {
      var i = 0
      while (i < keys.length) {
        val close = if (keys[i] == '<') keys.indexOf('>', i) else -1
        if (close > i) {
          host.key(fake, keys.substring(i, close + 1))
          i = close + 1
        } else {
          host.type(fake, keys[i].toString())
          i++
        }
      }
    }
  }

  /** Runs [keys] with [layout] enabled (empty for none) and returns the text and the mode. */
  private fun run(keys: String, layout: String = "", langmap: String = ""): String {
    val session = Session()
    injector.optionGroup.setOptionValue(Options.keyboardlayout, OptionAccessScope.GLOBAL(null), VimString(layout))
    injector.optionGroup.setOptionValue(Options.langmap, OptionAccessScope.GLOBAL(null), VimString(langmap))
    session.host.key(session.fake, "<Esc>")
    session.press(keys)
    return session.fake.document.content + "\n--- " + session.editor.mode::class.simpleName
  }

  /** The same edit twice: US keys with no layout, Cyrillic keys with one. They must agree. */
  private fun same(latin: String, cyrillic: String, layout: String = "russian") {
    assertEquals(run(latin), run(cyrillic, layout), "`$cyrillic` should do what `$latin` does")
  }

  @Test
  fun `test i enters Insert mode`() = same("i", "ш")

  @Test
  fun `test an operator and a text object`() = same("daw", "вфц")

  @Test
  fun `test change to end of word`() = same("ce<Esc>", "су<Esc>")

  @Test
  fun `test a doubled operator`() = same("dd", "вв")

  @Test
  fun `test a count in front of a doubled operator`() = same("2dd", "2вв")

  @Test
  fun `test a count between the operator and the motion`() = same("d2w", "в2ц")

  @Test
  fun `test yank and put`() = same("yyp", "ннз")

  @Test
  fun `test a register name is translated`() {
    // A register name is not read through the command trie - it is an argument the command builder
    // asks for by type, and `'langmap'` has to know to translate that too.
    same("\"qyyj\"qp", "ЭйнноЭйз")
  }

  @Test
  fun `test a mark name is translated`() = same("majgg`ax", "ьфопп ёфч".replace(" ", ""))

  @Test
  fun `test Visual mode`() = same("vex", "муч")

  @Test
  fun `test linewise Visual mode`() = same("Vd", "Мв")

  @Test
  fun `test repeat`() = same("x.", "чю")

  @Test
  fun `test undo`() = same("ddu", "ввг")

  @Test
  fun `test indent`() = same(">>", "ЮЮ")

  @Test
  fun `test a text object with brackets`() = same("3Gci(X<Esc>", "3Псш(X<Esc>")

  @Test
  fun `test the command line opens on the key that carries a colon`() {
    same(":s/one/ONE/<CR>", "Жs/one/ONE/<CR>")
  }

  @Test
  fun `test Insert mode is not translated`() {
    // The point of `'langmap'` rather than mappings: typed text stays typed text. `ш` is `i`, and
    // everything after it is the word the user meant to write.
    assertEquals(
      "фывone two three\nfour five six\nseven (eight) nine\n--- INSERT",
      run("шфыв", "russian"),
    )
  }

  @Test
  fun `test a search pattern is not translated`() {
    // Command-line mode is never translated, which is Vim's rule and the right one - the pattern
    // is text, and the text in this buffer is Latin. Only the `/` that opens it comes from the
    // layout, and on this one it does not: see the note on ASCII sources.
    assertEquals(run("/five<CR>x"), run("/five<CR>ч", "russian"))
  }

  @Test
  fun `test the Latin layout still works when a layout is set`() {
    // The reason the tables stop at the non-ASCII characters. A user switches back and forth all
    // day; a `'keyboardlayout'` that took `.` or `:` away from the Latin keyboard would be worse
    // than no support at all.
    for (keys in listOf("dw", "x.", "yyp", "2dd", ">>", "ce<Esc>", ":s/one/ONE/<CR>", "/five<CR>x", "fo;x")) {
      assertEquals(run(keys), run(keys, "russian"), "`$keys` in Latin must be unaffected by the layout")
    }
  }

  @Test
  fun `test langmap wins over the layout`() {
    // Documented order: a pair written by hand corrects the table without replacing it.
    assertEquals(run("dq"), run("вй", "russian", "йq"))
    assertEquals(run("dw"), run("вц", "russian", "йq"))
  }

  @Test
  fun `test ukrainian letters that russian does not have`() {
    // `і`, `ї`, `є` and `ґ` sit where russian has `ы`, `ъ`, `э` and the backslash.
    same("sX<Esc>", "іX<Esc>", "ukrainian")
    same("d''", "вєє", "ukrainian")
  }

  @Test
  fun `test belarusian letters that russian does not have`() {
    // `ў` replaces `щ` and `і` replaces `и`.
    same("o<Esc>", "ў<Esc>", "belarusian")
    same("wbx", "ціч", "belarusian")
  }

  /** The `:set langmap=` line the README offers, typed exactly as it is printed there. */
  private val optIn = "set langmap=\\\"@\\\\;${'$'}:^?&./\\\\,?/\\|"

  @Test
  fun `test the opt-in line in the README reaches the punctuation keys`() {
    // A wrong line in a document is the failure this whole option exists to remove, and it is
    // silent - a config runs with `indicateErrors = false`. So the README's line is typed here
    // rather than described, and the seven keys it claims are checked one at a time.
    val session = Session()
    session.host.key(session.fake, "<Esc>")
    session.press(":set keyboardlayout=russian<CR>")
    session.press(":" + optIn)
    session.host.key(session.fake, "<CR>")

    assertEquals(
      mapOf(';' to '$', ':' to '^', '.' to '/', ',' to '?', '/' to '|', '"' to '@', '?' to '&'),
      mapOf(
        ';' to LangMapOptionHelper.mapChar(';'),
        ':' to LangMapOptionHelper.mapChar(':'),
        '.' to LangMapOptionHelper.mapChar('.'),
        ',' to LangMapOptionHelper.mapChar(','),
        '/' to LangMapOptionHelper.mapChar('/'),
        '"' to LangMapOptionHelper.mapChar('"'),
        '?' to LangMapOptionHelper.mapChar('?'),
      ),
    )
    // And the layout still answers for the letters, because `'langmap'` only adds to it.
    assertEquals('d', LangMapOptionHelper.mapChar('в'))
  }

}
