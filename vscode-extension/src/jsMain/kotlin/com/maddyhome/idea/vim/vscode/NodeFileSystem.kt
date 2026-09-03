/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vscode

import com.maddyhome.idea.vim.api.VimFileReadException
import com.maddyhome.idea.vim.api.VimFileSystem

/**
 * Files, through Node.
 *
 * The VS Code extension host is a Node process, so `fs` is there - and `readFileSync` is
 * synchronous, which is what the engine needs. VS Code's own `workspace.fs` is promise-only and
 * would have been the wrong choice for the same reason `showInputBox` was: `:source` has to return
 * with the file's contents, not with the promise of them.
 *
 * A remote workspace is where this stops being true. `fs` reads the machine the extension host runs
 * on, which for SSH and dev containers *is* the remote machine, so a `.ideavimrc` there is found
 * correctly. A web-only workspace has no Node at all, and this will need `workspace.fs` and an
 * asynchronous load at startup.
 */
class NodeFileSystem : VimFileSystem {
  /**
   * The file's text, with `\r\n` collapsed to `\n`.
   *
   * Every caller wants lines rather than bytes: `:source` and the `.ideavimrc` hand this to the
   * Vimscript parser, and `:read` puts it in a buffer that is normalised. A carriage return at the
   * end of every line would end up in a `:map` right-hand side and in the buffer's text - and the
   * file that most often has them is a Windows user's `_ideavimrc`, which is the one file this has
   * to read correctly on the platform IdeaVim's own host normalises for free.
   */
  override fun readText(path: String): String = try {
    (fs.readFileSync(path, "utf8") as String).replace("\r\n", "\n")
  } catch (e: Throwable) {
    // The message is shown to the user verbatim when `:source` fails, and Node puts the useful part
    // - "no such file or directory" - in the message rather than in a code.
    throw VimFileReadException(path, e.message)
  }

  override fun exists(path: String): Boolean = try {
    fs.existsSync(path) as Boolean
  } catch (e: Throwable) {
    false
  }

  /**
   * `:w file`, which writes a file that is not the one in the editor.
   *
   * Node again rather than `workspace.fs`, and here the reason is stronger than convenience: `:w`
   * has to report `E212` when the write fails, and a promise cannot answer a command that has
   * already returned. Returns the failure rather than throwing, because Vim prints it.
   */
  override fun writeText(path: String, content: String): String? = try {
    fs.writeFileSync(path, content)
    null
  } catch (e: Throwable) {
    e.message ?: "write failed"
  }
}

/**
 * Where a user's configuration lives, in the order Vim and IdeaVim look for it.
 *
 * The same names IdeaVim uses on the JVM: `~/.ideavimrc`, `~/_ideavimrc` for Windows habits, then
 * the XDG location. `IDEA_VIM_CUSTOM_VIMRC` overrides all of it, which is what tests and dotfile
 * managers use.
 */
fun findVimRc(files: NodeFileSystem, environment: (String) -> String?): String? {
  environment("IDEA_VIM_CUSTOM_VIMRC")?.takeIf { it.isNotEmpty() }?.let { custom ->
    if (files.exists(custom)) return custom
  }

  val home = environment("HOME") ?: environment("USERPROFILE") ?: return null

  for (name in listOf(".ideavimrc", "_ideavimrc")) {
    val candidate = "$home/$name"
    if (files.exists(candidate)) return candidate
  }

  val xdgConfigHome = environment("XDG_CONFIG_HOME")
    ?.takeIf { it.isNotEmpty() }
    ?.let { if (it.startsWith("~/")) home + it.substring(1) else it }
    ?: "$home/.config"

  val xdg = "$xdgConfigHome/ideavim/ideavimrc"
  return if (files.exists(xdg)) xdg else null
}

/** `fs` is a Node module rather than a global, so it has to be required by name. */
private val fs: dynamic = require("fs")

private external fun require(module: String): dynamic
