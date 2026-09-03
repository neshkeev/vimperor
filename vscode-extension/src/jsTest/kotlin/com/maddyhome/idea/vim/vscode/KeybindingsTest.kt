/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading VS Code's keymap, which VS Code will not summarise.
 *
 * There is no API that answers what is bound to what, so `:actionlist` reads the three files the
 * workbench builds its keymap out of. Two of those are the user's own and are therefore arbitrary
 * text, and the third is a document served over an undocumented URI - so everything here is about
 * the reading being wrong rather than about the reading failing. A file with a comment in it, a
 * chord that is itself a comment marker, an entry that takes a binding away: each of those printed
 * as a chord that does not exist would be worse than the blank column this replaces.
 *
 * See [KeybindingTable] for why the three are held apart rather than merged as they arrive.
 */
class KeybindingsTest {

  // Reading the entries.

  @Test
  fun `test a command and its chord are read`() {
    val bindings = keybindingsFromJson("""[{ "key": "shift+cmd+f", "command": "workbench.action.findInFiles" }]""", "mac")

    assertEquals(listOf(Keybinding("workbench.action.findInFiles", "shift+cmd+f")), bindings)
  }

  /**
   * A manifest gives one chord per platform, and the wrong one is worse than none: `ctrl+shift+f`
   * beside a command on a Mac is a chord that does something else there.
   */
  @Test
  fun `test the platform decides which chord is read`() {
    val json = """[{ "command": "git.pull", "key": "ctrl+g", "mac": "cmd+g", "when": "editorFocus" }]"""

    assertEquals("cmd+g", keybindingsFromJson(json, "mac").single().key)
    assertEquals("ctrl+g", keybindingsFromJson(json, "linux").single().key)
    assertEquals("ctrl+g", keybindingsFromJson(json, "win").single().key)
  }

  @Test
  fun `test a command with no chord for this platform falls back to the shared one`() {
    val json = """[{ "command": "git.pull", "key": "ctrl+g", "win": "alt+g" }]"""

    assertEquals("ctrl+g", keybindingsFromJson(json, "mac").single().key)
    assertEquals("alt+g", keybindingsFromJson(json, "win").single().key)
  }

  /** `-command` is how `keybindings.json` unbinds, and it keeps the key it is unbinding. */
  @Test
  fun `test a leading minus is a removal rather than a command`() {
    val binding = keybindingsFromJson("""[{ "key": "cmd+z", "command": "-undo" }]""", "mac").single()

    assertEquals("undo", binding.command)
    assertEquals("cmd+z", binding.key)
    assertTrue(binding.removes)
  }

  /**
   * `"command": ""` is how VS Code's own defaults file writes a chord that has been taken away from
   * everything, and there is nothing to print it beside.
   */
  @Test
  fun `test an entry with no command or no chord is skipped`() {
    val json = """
      [
        { "key": "cmd+k", "command": "" },
        { "key": "cmd+j" },
        { "command": "workbench.action.files.save" },
        { "key": "cmd+s", "command": "workbench.action.files.save" }
      ]
    """.trimIndent()

    assertEquals(listOf(Keybinding("workbench.action.files.save", "cmd+s")), keybindingsFromJson(json, "mac"))
  }

  /** Both files are the user's to break, and a broken one is no keybindings rather than a crash. */
  @Test
  fun `test unreadable json is no keybindings`() {
    assertEquals(emptyList(), keybindingsFromJson("{ not json", "mac"))
    assertEquals(emptyList(), keybindingsFromJson("", "mac"))
    assertEquals(emptyList(), keybindingsFromJson("""{ "key": "cmd+f" }""", "mac"), "an object is not a list of them")
  }

  // JSONC, which is what both files actually are.

  @Test
  fun `test comments and a trailing comma do not stop the file being read`() {
    val json = """
      // Place your key bindings in this file to override the defaults
      [
        /* the one I always forget */
        { "key": "cmd+shift+f", "command": "workbench.action.findInFiles" }, // find in files
      ]
    """.trimIndent()

    assertEquals(listOf(Keybinding("workbench.action.findInFiles", "cmd+shift+f")), keybindingsFromJson(json, "mac"))
  }

  /**
   * The reason this is a scanner and not two regexes.
   *
   * `cmd+/` is a real chord - it is comment-line in every VS Code - and a `when` clause is an
   * expression that can hold anything. Cutting at the first `//` would take the rest of the file
   * with it.
   */
  @Test
  fun `test a comment marker inside a string is part of the string`() {
    val json = """[{ "key": "cmd+/", "command": "editor.action.commentLine", "when": "a == 'b//c'" }]"""

    assertEquals(listOf(Keybinding("editor.action.commentLine", "cmd+/")), keybindingsFromJson(json, "mac"))
  }

  @Test
  fun `test an escaped quote does not end the string it is in`() {
    val json = """[{ "key": "cmd+1", "command": "say", "when": "x == '\"' // not a comment" }]"""

    assertEquals(listOf(Keybinding("say", "cmd+1")), keybindingsFromJson(json, "mac"))
  }

  /** A trailing comma inside the entry as well as after it - both are legal in a settings file. */
  @Test
  fun `test a trailing comma anywhere does not stop the file being read`() {
    val json = """[ { "key": "cmd+f", "command": "actions.find", }, ]"""

    assertEquals(listOf(Keybinding("actions.find", "cmd+f")), keybindingsFromJson(json, "mac"))
  }

  // Putting the three together.

  /**
   * The defaults arrive last and have to apply first.
   *
   * They come over a promise while the other two are readable the moment activation runs, so a
   * table that merged in arrival order would let VS Code's own chord overwrite the user's - which
   * is the one direction that must never happen.
   */
  @Test
  fun `test the user's file wins however late the defaults arrive`() {
    val table = KeybindingTable()
    table.setUser(listOf(Keybinding("undo", "cmd+z", removes = true), Keybinding("undo", "cmd+u")))
    table.setDefaults(listOf(Keybinding("undo", "cmd+z")))

    assertEquals(listOf("cmd+u"), table.shortcutsFor("undo"))
  }

  @Test
  fun `test a command bound in two places keeps both chords`() {
    val table = KeybindingTable()
    table.setDefaults(listOf(Keybinding("git.pull", "cmd+g")))
    table.setContributed(listOf(Keybinding("git.pull", "cmd+shift+g")))

    assertEquals(listOf("cmd+g", "cmd+shift+g"), table.shortcutsFor("git.pull"))
  }

  /** The same chord from two sources is one chord, not a column that reads `cmd+g  cmd+g`. */
  @Test
  fun `test the same chord twice is printed once`() {
    val table = KeybindingTable()
    table.setDefaults(listOf(Keybinding("git.pull", "cmd+g")))
    table.setContributed(listOf(Keybinding("git.pull", "cmd+g")))

    assertEquals(listOf("cmd+g"), table.shortcutsFor("git.pull"))
  }

  @Test
  fun `test a command nothing binds has no chords`() {
    val table = KeybindingTable()
    table.setDefaults(listOf(Keybinding("undo", "cmd+z"), Keybinding("undo", "cmd+z", removes = true)))

    assertEquals(emptyList(), table.shortcutsFor("undo"))
    assertEquals(emptyList(), table.shortcutsFor("never.heard.of.it"))
    assertEquals(0, table.commandCount, "a command whose every chord was taken away is not a command with chords")
  }

  @Test
  fun `test a table nobody has filled answers rather than throwing`() {
    assertEquals(emptyList(), KeybindingTable().shortcutsFor("undo"))
  }

  // Finding the user's file.

  /**
   * Two directories up from the storage VS Code hands out, which is the only way to find it that
   * holds for Insiders, VSCodium, a portable install and a remote window alike.
   */
  @Test
  fun `test the user's keybindings sit two directories above the extension's storage`() {
    assertEquals(
      "/Users/x/Library/Application Support/Code/User/keybindings.json",
      userKeybindingsPath("/Users/x/Library/Application Support/Code/User/globalStorage/neshkeev.vimperor"),
    )
  }

  @Test
  fun `test a Windows path keeps its separators`() {
    assertEquals(
      """C:\Users\x\AppData\Roaming\Code\User\keybindings.json""",
      userKeybindingsPath("""C:\Users\x\AppData\Roaming\Code\User\globalStorage\neshkeev.vimperor"""),
    )
  }

  /** A guess would read some other VS Code's file on a machine with several. */
  @Test
  fun `test a path that is not the shape VS Code documents is refused`() {
    assertNull(userKeybindingsPath(null))
    assertNull(userKeybindingsPath(""))
    assertNull(userKeybindingsPath("/somewhere/else/entirely"))
  }

  @Test
  fun `test the platform names are VS Code's rather than Node's`() {
    assertEquals("mac", currentPlatform("darwin"))
    assertEquals("win", currentPlatform("win32"))
    assertEquals("linux", currentPlatform("linux"))
    assertEquals("linux", currentPlatform(null), "an unknown platform reads the shared chord")
  }

  // The file on disk.

  @Test
  fun `test the user's file is read when it is there and shrugged off when it is not`() {
    val directory = "${temporaryDirectory()}/vimperor-keybindings/User"
    val path = "$directory/keybindings.json"
    writeTextTo(path, """[{ "key": "cmd+shift+u", "command": "workbench.action.output.toggleOutput" }]""")

    val files = NodeFileSystem()
    assertEquals(
      listOf(Keybinding("workbench.action.output.toggleOutput", "cmd+shift+u")),
      userKeybindings(path, files, "mac"),
    )
    assertEquals(emptyList(), userKeybindings("$directory/nothing-here.json", files, "mac"))
    assertEquals(emptyList(), userKeybindings(null, files, "mac"), "a user whose storage was not the documented shape")
  }
}
