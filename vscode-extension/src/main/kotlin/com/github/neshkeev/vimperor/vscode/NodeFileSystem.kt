/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

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

  /**
   * Adding to the end of a file, which Node can do in one call.
   *
   * Overridden because the engine's default is a read and a whole-file write, and the one caller -
   * `keylog` - appends for as long as the session lasts. `appendFileSync` creates the file when it
   * is not there, which is what the default's `exists` check is for.
   */
  override fun appendText(path: String, content: String): String? = try {
    fs.appendFileSync(path, content)
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
private fun vimRcFamilies(home: String, xdgConfigHome: String): List<List<String>> = listOf(
  // Written for this editor. Wins over everything, wherever it is.
  listOf(
    "$home/.vimperorrc",
    "$home/_vimperorrc",
    "$xdgConfigHome/vimperor/vimperorrc",
  ),

  // Written for IdeaVim, and shared with the IntelliJ plugin unchanged.
  listOf(
    "$home/.ideavimrc",
    "$home/_ideavimrc",
    "$xdgConfigHome/ideavim/ideavimrc",
  ),

  // Written for Vim. The point of reading it is the person who has never installed IdeaVim: they
  // already have a config, and requiring them to copy it to a new name before anything works is a
  // worse first five minutes than the handful of lines this fork will not understand.
  listOf(
    "$home/.vimrc",
    "$home/_vimrc",
    "$home/.vim/vimrc",
    "$xdgConfigHome/vim/vimrc",
  ),
)

/** The families flattened, most specific first, which is the order a config is searched for. */
private fun vimRcCandidates(home: String, xdgConfigHome: String): List<String> =
  vimRcFamilies(home, xdgConfigHome).flatten()

/**
 * Every config to run, in the order they are sourced: **least specific first**.
 *
 * One file from each family that has one - `~/.vimrc`, then `~/.ideavimrc`, then `~/.vimperorrc` -
 * and the last one read wins wherever two of them disagree. The order is the whole point, and it is
 * the search order read from the other end: the preference that puts `~/.vimperorrc` first when
 * looking for a config is the same preference that gives it the last word when running them all.
 *
 * **This is a divergence from Vim and IdeaVim, which each read one file**, and it is deliberate.
 * Vimperor is the third program to read these files rather than the first, so somebody arriving with
 * a `~/.vimrc` and a `~/.ideavimrc` has two configs that both say something true about how they
 * want to work; taking only the most specific one threw the rest away silently. Anything that should
 * apply here and not elsewhere goes in `~/.vimperorrc`, which runs last.
 *
 * Within a family the first that exists wins: `~/.vimrc` and `~/_vimrc` are two spellings of one
 * file, not two files to run.
 *
 * `IDEA_VIM_CUSTOM_VIMRC` is the one exception, and it is read alone. A path named outright is an
 * instruction rather than a preference, so layering other files under something the user pointed at
 * by hand would be the opposite of what they asked for.
 */
fun vimRcFilesToLoad(files: NodeFileSystem, environment: (String) -> String?): List<String> {
  environment("IDEA_VIM_CUSTOM_VIMRC")?.takeIf { it.isNotEmpty() }?.let { custom ->
    if (files.exists(custom)) return listOf(custom)
  }
  val home = environment("HOME") ?: environment("USERPROFILE") ?: return emptyList()
  val xdg = xdgConfigHome(home, environment)
  return vimRcFamilies(home, xdg).mapNotNull { family -> family.firstOrNull { files.exists(it) } }.asReversed()
}

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
 * Every path a config could arrive at, whether or not one is there now.
 *
 * What a file watcher needs, and it is deliberately not "the files that loaded". A family gaining
 * a file changes what runs - *creating* `~/.vimperorrc` adds a config that then has the last word -
 * and so does a family losing one, which is a change watching only the loaded files would miss
 * entirely. Every spelling is included for the same reason.
 *
 * `IDEA_VIM_CUSTOM_VIMRC` comes first when it is set, as it does in [vimRcFilesToLoad], and is
 * included even when the file is not there yet for the same reason as the rest.
 */
fun vimRcPaths(environment: (String) -> String?): List<String> {
  val custom = environment("IDEA_VIM_CUSTOM_VIMRC")?.takeIf { it.isNotEmpty() }
  val home = environment("HOME") ?: environment("USERPROFILE") ?: return listOfNotNull(custom)
  return listOfNotNull(custom) + vimRcCandidates(home, xdgConfigHome(home, environment))
}

/** `$XDG_CONFIG_HOME`, with Vim's own default and the `~/` a shell would have expanded. */
private fun xdgConfigHome(home: String, environment: (String) -> String?): String =
  environment("XDG_CONFIG_HOME")
    ?.takeIf { it.isNotEmpty() }
    ?.let { if (it.startsWith("~/")) home + it.substring(1) else it }
    ?: "$home/.config"

/** `fs` is a Node module rather than a global, so it has to be required by name. */
private val fs: dynamic = require("fs")

private external fun require(module: String): dynamic
