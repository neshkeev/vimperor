/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

/*
 * The tutor text itself is licensed under the Vim license, not MIT. It lives in
 * vim-engine now; see the header of VimTutor.kt. What is left here is only
 * IdeaVim's own half of it.
 */

package com.maddyhome.idea.vim.ui

import com.maddyhome.idea.vim.tutor.TutorHost
import com.maddyhome.idea.vim.tutor.vimTutor

/**
 * IdeaVim's tutor: Vim's lessons, with IdeaVim's answers to the parts about the editor.
 *
 * The lessons used to live here as one 900-line string. They moved to `vim-engine` when a second
 * host wanted them, because a tutor that teaches `dw` is teaching Vim rather than teaching an IDE,
 * and only the handful of places where it stops teaching Vim needed to differ.
 *
 * The margins below are load-bearing: this is fixed-width text spliced into more of it, so every
 * line carries the indentation it lands at. `trimMargin` says so rather than leaving it to be
 * counted.
 */
private val IdeaVimTutorHost = TutorHost(
  name = "IdeaVim",

  whatItIs = """
    |     IdeaVim is a Vim engine for JetBrains IDEs, such as IntelliJ IDEA, PyCharm,
    |     and more. It allows you to use Vim-style editing inside of a full-fledged IDE.
  """.trimMargin(),

  notCovered = """
    |     This tutorial doesn't cover a few things that are explained in Vim tutor,
    |     but aren’t relevant to IdeaVim (for example, how to exit Vim). If you’re
    |     still interested in them, see the last section.
  """.trimMargin(),

  pendingCommand = """
    |  NOTE: The letter  d  will appear in the status bar as you type it.
    |        Vim is waiting for you to type  w .  If you see another character
    |        than  d  you typed something wrong; press  <ESC>  and start over.
  """.trimMargin(),

  startupScript = """
    |            ** Enable IdeaVim features **
    |
    |  IdeaVim and Vim have many more features than Vi, but most of them are disabled
    |  by default. To start using more features, first create an "ideavimrc" file.
    |  For Vim it’s a "vimrc" file.
    |
    |  To do so, click the IdeaVim status bar icon
    |  (the green V letter) and click "Create ~/.ideavimrc". This will create a
    |  ".ideavimrc" file in your home directory.
    |
    |  Add the following lines to the file:
    |
    |        Plug 'machakann/vim-highlightedyank'
    |        set incsearch
    |
    |  Click on the reload icon in the upper-right corner of the editor.
    |
    |  With these commands, you've enable the "highlightedyank" plugin and incremental
    |  search. The "highlightedyank" plugin highlights the text that was yanked.
    |  The incremental search feature shows search matches as you type the query.
    |
    |  You can find more plugins by clicking “Status bar icon | Plugins…”.
    |
    |  You can add all of your preferred settings to this "ideavimrc" file.
  """.trimMargin(),

  startupScriptSummary = "  1. Create an ideavimrc startup script to keep your preferred settings.",

  conclusionIntro = """
    |  This concludes the IdeaVim Tutor.  It was intended to give a brief overview of
    |  the IdeaVim plugin, just enough to allow you to use the editor fairly easily.
    |  It is far from complete as IdeaVim and Vim have many many more commands.
  """.trimMargin(),

  learnMore = """
    |  To learn more about IdeaVim, visit the official GitHub repository:
    |  https://github.com/JetBrains/ideavim
  """.trimMargin(),

  modifiedBy = "  Modified for IdeaVim by Alex Plate.",

  appendixNote = """
    |  We removed the Vim Tutor sections that are related to the Vim editor,
    |  but not really applicable to the IdeaVim plugin.
    |  We recommend running these steps in an actual Vim editor.
  """.trimMargin(),
)

// Declared after the host, and that is not a style choice: Kotlin initialises top-level properties
// in declaration order, so with these the other way round `tutor` was built from a host that did
// not exist yet and the compiler said so. The same rule bites in Kotlin/JS, where it says nothing
// at all - see the note on `pushedSelections` in VsCodeEditor.
internal val tutor: String = vimTutor(IdeaVimTutorHost)
