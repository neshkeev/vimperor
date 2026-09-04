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

  override fun isDirectory(path: String): Boolean = try {
    fs.statSync(path).isDirectory() as Boolean
  } catch (e: Throwable) {
    false
  }

  /**
   * The names directly inside a directory, for `:vimgrep`'s globs.
   *
   * Synchronous, like everything else here: `:vimgrep` fills the quickfix list and jumps to the
   * first entry inside the command, and a promise cannot answer a command that has returned.
   */
  override fun listDirectory(path: String): List<String> = try {
    val entries = fs.readdirSync(path)
    val count = entries.length as Int
    (0 until count).map { entries[it] as String }
  } catch (e: Throwable) {
    emptyList()
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
 * Every configuration file this host will read, in the order it looks for them.
 *
 * Three families, most specific first. Within a family: the dotted name in the home directory, the
 * underscored one for Windows habits, then the XDG location - and for Vim's family, `~/.vim/vimrc`
 * as well, which is where people who keep everything under `~/.vim` put it.
 *
 * Vim's own three are in Vim's own order, from `:h vimrc`: `$HOME/.vimrc`, `$HOME/.vim/vimrc`,
 * `$XDG_CONFIG_HOME/vim/vimrc`, with `_vimrc` tried beside `.vimrc` on any system.
 */
private fun vimRcCandidates(home: String, xdgConfigHome: String): List<String> = listOf(
  // Written for this editor. Wins over everything, wherever it is.
  "$home/.vimperorrc",
  "$home/_vimperorrc",
  "$xdgConfigHome/vimperor/vimperorrc",

  // Written for IdeaVim, and shared with the IntelliJ plugin unchanged.
  "$home/.ideavimrc",
  "$home/_ideavimrc",
  "$xdgConfigHome/ideavim/ideavimrc",

  // Written for Vim. The point of reading it is the person who has never installed IdeaVim: they
  // already have a config, and requiring them to copy it to a new name before anything works is a
  // worse first five minutes than the handful of lines this fork will not understand.
  "$home/.vimrc",
  "$home/_vimrc",
  "$home/.vim/vimrc",
  "$xdgConfigHome/vim/vimrc",
)

/**
 * Whether the config that was found is Vim's own, rather than one written for this fork.
 *
 * Worth saying out loud at startup, and only for this family. A `.vimrc` is the one file here that
 * was written for a different program: the lines in it this fork does not implement are expected
 * rather than a mistake, and - this is the part that would otherwise be mystifying - they are
 * skipped **in silence**. `executeFile` runs a config with `indicateErrors = false`, which is
 * IdeaVim's behaviour and the right one for a file that runs before there is a window to complain
 * in. The cost is that a config which half worked looks exactly like one that worked, so the one
 * place that can say "some of this was for a different editor" is here, once, at startup.
 */
fun isVimsOwnConfig(path: String): Boolean =
  path.endsWith("/.vimrc") || path.endsWith("/_vimrc") || path.endsWith("vim/vimrc")

/**
 * Where a user's configuration lives, in the order this host looks for it.
 *
 * `~/.vimperorrc`, then `~/.ideavimrc`, then `~/.vimrc` - and a whole family is searched before the
 * next one starts, so an XDG `vimperor/vimperorrc` also beats a `~/.ideavimrc`, even though the home
 * directory is searched first within any one family. **The name is the intent.** Somebody who wrote
 * a file called `vimperorrc` meant it for this editor, and it should not lose to a config that
 * happens to sit in a directory that gets looked at earlier.
 *
 * The last family is what makes this usable by somebody who has never installed IdeaVim. Their
 * `~/.vimrc` will contain lines this fork does not implement - a plugin manager above all - and
 * those report errors and are stepped over, the way Vim itself carries on past a bad line. A config
 * that half works is worth more than no config at all, and it is the reason `:syntax`,
 * `:colorscheme` and `:filetype` are accepted rather than rejected.
 *
 * Nothing is merged: the first file found is the only one read. That is what `.ideavimrc` already
 * promised, and it is what makes `~/.vimperorrc` useful as "everything I already had, plus the
 * lines that only make sense in VS Code" - `source ~/.vimrc` on its first line does the rest, and
 * does it explicitly, where a merge would have to guess at an order.
 *
 * The IntelliJ plugin reads `.ideavimrc` and only `.ideavimrc`. This function is host-local for
 * that reason: sharing a config between the two editors is what the second family is *for*, and
 * teaching the plugin to read `.vimperorrc` would defeat it.
 *
 * `IDEA_VIM_CUSTOM_VIMRC` still overrides all of it, which is what tests and dotfile managers use.
 */
fun findVimRc(files: NodeFileSystem, environment: (String) -> String?): String? {
  environment("IDEA_VIM_CUSTOM_VIMRC")?.takeIf { it.isNotEmpty() }?.let { custom ->
    if (files.exists(custom)) return custom
  }

  val home = environment("HOME") ?: environment("USERPROFILE") ?: return null

  val xdgConfigHome = environment("XDG_CONFIG_HOME")
    ?.takeIf { it.isNotEmpty() }
    ?.let { if (it.startsWith("~/")) home + it.substring(1) else it }
    ?: "$home/.config"

  return vimRcCandidates(home, xdgConfigHome).firstOrNull { files.exists(it) }
}

/** `fs` is a Node module rather than a global, so it has to be required by name. */
private val fs: dynamic = require("fs")

private external fun require(module: String): dynamic
