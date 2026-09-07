/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.GlobalOptions
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.VimProcessGroupBase

/**
 * `:!cmd`, run through Node.
 *
 * The extension host is a Node process, so `child_process.spawnSync` is there - and *sync* is what
 * makes this possible at all. Every other way of running a command from a VS Code extension is a
 * promise or a terminal, and the engine needs the output before `:%!sort` can replace the lines it
 * was given. This is the third time the synchronous Node API has been the answer where the VS Code
 * one was the wrong shape, after `readFileSync` and `writeFileSync`.
 *
 * The shell comes from `'shell'` and `'shellcmdflag'`, which is Vim's own configuration and not the
 * host's. `'shellxescape'` and `'shellxquote'` are Windows `cmd.exe` quoting and are not applied:
 * the command goes to the shell as one argument, so nothing here needs to quote it.
 */
internal class NodeProcessGroup(
  private val workspaceRoot: () -> String? = { workspace.workspaceFolders?.firstOrNull()?.uri?.fsPath },
  private val run: (String, String, String, String?, String?) -> ProcessResult = ::spawnSync,
) : VimProcessGroupBase() {

  override fun executeCommand(
    editor: VimEditor,
    command: String,
    input: CharSequence?,
    currentDirectoryPath: String?,
    options: GlobalOptions,
  ): String? {
    val shell = options.shell.ifEmpty { "/bin/sh" }
    val flag = options.shellcmdflag.ifEmpty { "-c" }
    val result = run(shell, flag, command, input?.toString(), currentDirectoryPath ?: workspaceRoot())
    lastExitCode = result.exitCode
    // Vim shows both streams. A command that failed usually said why on stderr and nothing on
    // stdout, and dropping that would leave `:!` reporting an exit code and no reason for it.
    return result.output + result.error
  }
}

/** What a finished process left behind. Separated so a test can run one without a shell. */
internal data class ProcessResult(val output: String, val error: String, val exitCode: Int?)

private fun spawnSync(
  shell: String,
  flag: String,
  command: String,
  input: String?,
  workingDirectory: String?,
): ProcessResult {
  val options: dynamic = js("({})")
  options.encoding = "utf8"
  if (input != null) options.input = input
  if (workingDirectory != null) options.cwd = workingDirectory
  val result = childProcess.spawnSync(shell, arrayOf(flag, command), options)
  // `error` is set when the shell itself could not be started - a missing `'shell'`, most likely -
  // and there is no exit code in that case, only a reason.
  val failure = result.error
  if (failure != null) return ProcessResult("", (failure.message as String?) ?: "could not run $shell", null)
  return ProcessResult(
    (result.stdout as String?).orEmpty(),
    (result.stderr as String?).orEmpty(),
    (result.status as Int?),
  )
}

/** `child_process` is a Node module rather than a global, so it has to be required by name. */
private val childProcess: dynamic = require("child_process")

private external fun require(module: String): dynamic
