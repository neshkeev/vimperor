/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.engineCommandProvider
import com.maddyhome.idea.vim.api.injector
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every key the engine registers, pressed, to find out which ones land on a part of this host that
 * is not built yet.
 *
 * The port is a set of host services behind an engine that assumes all of them exist. The ones this
 * host has not written throw `NotImplementedError` when reached, which is the right thing for a
 * half-built host to do - but only if somebody finds out. Until this test there was no way to know
 * which keys were live and which were mines, and the way it got discovered was a crash: nothing had
 * ever used a bracket text object, so nothing had noticed `psiService` was a `TODO()`, until a test
 * for something else walked into it.
 *
 * So this presses everything. The list below is the whole remaining gap, and it is asserted rather
 * than printed: implementing a service shrinks it, and a service that quietly stops being reached -
 * or starts being reached - shows up as a diff. It is not a list of bugs. Most of these are
 * features this host does not have yet and one or two are features it can never have.
 *
 * A key is listed once per distinct message, deduplicated across the modes it is registered in.
 */
class VsCodeUnimplementedTest {

  @Test
  fun `test the keys that reach an unbuilt part of the host are the ones listed here`() {
    assertEquals(EXPECTED.trim(), sweep().trim())
  }

  private fun sweep(): String {
    val found = mutableMapOf<String, MutableSet<String>>()

    // A first injector purely to have a parser to name keys with; each press then gets its own.
    injector = VsCodeInjector().also { it.register(VsCodeEditor(FakeEditor(""))) }
    val commands = engineCommandProvider.getCommands() + VsCodeCommandProvider.getCommands()

    for (command in commands) {
      for (keys in command.keys) {
        val notation = injector.parser.toKeyNotation(keys)
        try {
          val fake = FakeEditor("one two\nthree four\nfive six")
          injector = VsCodeInjector().also { it.register(VsCodeEditor(fake)) }
          engineCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
          VsCodeCommandProvider.getCommands().forEach { injector.keyGroup.registerCommandAction(it) }
          injector.functionService.registerHandlers()
          val editor = injector.editorGroup.getEditors().first() as VsCodeEditor
          editor.primaryCaret().moveToOffsetNative(4)
          val handler = KeyHandler.getInstance()
          handler.fullReset(editor)
          for (stroke in keys) {
            handler.handleKey(editor, stroke, VsCodeExecutionContext, handler.keyHandlerState)
          }
          editor.flush()
        } catch (e: NotImplementedError) {
          val what = (e.message ?: "?").removePrefix("An operation is not implemented: ")
          found.getOrPut(what) { mutableSetOf() } += notation
        }
        // Anything else a key does here - an error message, a beep, nothing at all - is that key
        // working. This test is only asking which parts of the host are missing.
      }
    }

    return found.entries.sortedBy { it.key }.joinToString("\n") { (what, keys) ->
      "$what\n    ${keys.sorted().joinToString(" ")}"
    }
  }

  private companion object {
    /**
     * What is not built. Each line is one missing piece of host and the keys that reach it.
     *
     * Roughly in order of what they would cost: folds, windows, tabs and `:w`/`:q` each need a VS
     * Code command and a way to wait for it, and between them they are most of what is left. `S`
     * wants the file's indent settings, which means declaring `TextEditor.options`. `U` needs a
     * history this host does not keep. A spellchecker and a language server it does not have at
     * all, so `[m`, `]s` and `z=` will most likely stay here.
     */
    const val EXPECTED = """
VS Code host: U needs the same host history undo does
    U
VS Code host: findMethodEnd needs a language server
    [M ]M
VS Code host: findMethodStart needs a language server
    [m ]m
VS Code host: findMisspelledWord needs a spellchecker
    [s ]s
VsCodeEditor.createIndentBySize
    S
the VS Code host does not provide file yet
    <C-6> <C-G> <C-G>u <C-S-6> <C-^> ZQ ZZ g8 g<C-G>
the VS Code host does not provide searchWindowGroup yet
    q/ q: q?
the VS Code host does not provide spellcheckerService yet
    z= zg zw
"""
  }
}
