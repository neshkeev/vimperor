/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

/*
 * The tutor text this file supplies words for is licensed under the Vim license,
 * not MIT. See the header of VimTutor.kt in vim-engine, and ThirdPartyLicenses.md
 * in this directory, which is inside the packaged extension because the Vim
 * license requires its text to travel with the distribution.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.tutor.TutorHost
import com.maddyhome.idea.vim.tutor.vimTutor

/**
 * Vimperor's tutor: Vim's own lessons, opened in a buffer the reader is meant to take apart.
 *
 * Vim ships this as `vimtutor`, a shell script that copies `runtime/tutor/tutor` somewhere
 * writable and opens Vim on the copy. The copy is the point - the lessons say "delete this word"
 * and mean it. VS Code's untitled document is the same idea: editable, never saved unless the
 * reader asks for it, and gone with the tab.
 *
 * The margins are load-bearing. This is fixed-width text spliced into more of it, so every line
 * carries the column it lands at and nothing wraps for you.
 */
private val VimperorTutorHost = TutorHost(
  name = "Vimperor",

  whatItIs = """
    |     Vimperor is Vim for VS Code. It runs the same engine as IdeaVim does -
    |     JetBrains' vim-engine, compiled to JavaScript - inside the extension
    |     host, so what you learn here is Vim rather than an imitation of it.
  """.trimMargin(),

  notCovered = """
    |     A few of Vim's own lessons are about running Vim as a program of its own
    |     rather than about editing. They are in the last section instead of here.
  """.trimMargin(),

  /*
   * IdeaVim tells the reader to watch for a `d` appearing in the status bar. That is `'showcmd'`,
   * which this host does not have, so the advice would send the reader looking for something that
   * is never going to appear and conclude they had typed it wrong.
   */
  pendingCommand = """
    |  NOTE: Nothing visible happens when you press  d , and that is correct: the
    |        operator is waiting for a motion to act on. Type  w  to give it one.
    |        If you think you pressed the wrong key, press  <ESC>  and start over.
  """.trimMargin(),

  startupScript = """
    |            ** Enable more of Vim **
    |
    |  Vim has many more features than Vi, and most of them are off by default. To
    |  turn them on, put the settings you want in a startup script that Vimperor
    |  reads every time it starts.
    |
    |  Vimperor reads the same file IdeaVim does, because it is the same engine and
    |  because that is the file its users already have. Create one of these in your
    |  home directory - the first that exists is the one that is read:
    |
    |        ~/.ideavimrc
    |        ~/_ideavimrc
    |        ${'$'}XDG_CONFIG_HOME/ideavim/ideavimrc
    |
    |  Add these lines to it:
    |
    |        set incsearch
    |        set hlsearch
    |        set scrolloff=5
    |
    |  Then read it back in without restarting, the way Vim does:
    |
    |        :source ~/.ideavimrc
    |
    |  With those, searching shows matches as you type, leaves them highlighted, and
    |  keeps five lines of context around the cursor.
    |
    |  You can read your existing Vim configuration from it, too:
    |
    |        source ~/.vimrc
    |
    |  Vim plugins are not supported yet, so leave "Plug" lines out for now.
  """.trimMargin(),

  startupScriptSummary = "  1. Create an ideavimrc startup script to keep your preferred settings.",

  conclusionIntro = """
    |  This concludes the Vimperor Tutor.  It was intended to give a brief overview
    |  of Vimperor, just enough to allow you to use it fairly easily. It is far from
    |  complete as Vim has many many more commands.
  """.trimMargin(),

  learnMore = """
    |  To learn more about Vimperor, visit the official GitHub repository:
    |  https://github.com/neshkeev/vimperor
  """.trimMargin(),

  modifiedBy = "  Modified for Vimperor by Nikita Eshkeev.",

  /*
   * IdeaVim's note says these were removed as "not really applicable" and points the reader at a
   * real Vim. That is the wrong advice here, and it was checked rather than assumed: `:q`,
   * `:w FILENAME`, `:r FILENAME`, `:!` and `:help` all work in this host. They stay in an appendix
   * only because they are about files and processes rather than about editing text.
   */
  appendixNote = """
    |  These are Vim's own lessons about working with files and with the world
    |  outside the editor. Unlike in some Vim plugins, they do work here:  :q ,
    |  :w FILENAME ,  :r FILENAME ,  :!  and  :help  are all real in Vimperor,
    |  so try them in this buffer rather than taking them on trust.
  """.trimMargin(),
)

/** The tutor text, built once. */
internal val tutorText: String by lazy { vimTutor(VimperorTutorHost) }

/**
 * Opens the tutor in an untitled document.
 *
 * Asynchronous, which is unusual for this host and fine here: this runs because the user picked a
 * command out of the palette, not because a keystroke is waiting on it. Nothing in the engine is
 * mid-command while the promise is outstanding.
 *
 * Both halves of both promises are handled, for the reason [Thenable] gives: a rejection nobody
 * listens for is silent, and "the palette entry did nothing" is the least debuggable bug there is.
 */
internal fun openTutor(onFailure: (Any?) -> Unit) {
  val options = js("{}").unsafeCast<UntitledDocumentOptions>()
  options.content = tutorText
  options.language = "plaintext"
  workspace.openTextDocument(options).then(
    { document -> window.showTextDocument(document).then({ _: TextEditor -> }, onFailure); Unit },
    onFailure,
  )
}
