/*
 * Copyright 2026 Nikita Eshkeev
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.github.neshkeev.vimperor.vscode

import com.maddyhome.idea.vim.api.ScheduledTask
import com.maddyhome.idea.vim.api.injector

/** What `'vimrcreload'` can mean. See [VsCodeOptions.vimrcreload]. */
internal enum class VimrcReload {
  /** Do not watch at all. */
  NONE,

  /** Ask, which is the default. */
  PROMPT,

  /** Re-source without asking. */
  AUTO,
}

/** The answer to the question, and the three things the user can press. */
internal enum class VimrcAnswer(val label: String) {
  RELOAD("Reload"),
  RESTART("Restart Extensions"),
  DISMISS("Not now"),
}

/**
 * Noticing that the config changed on disk, and deciding what to do about it.
 *
 * ## Why the policy is here and not in `Extension.kt`
 *
 * `Extension.kt` is the only file in this module no unit test loads - it is the activation script,
 * and the one thing that exercises it is `activate-in-a-stub-host.js`. So everything decidable
 * lives here, where a test can drive it: which paths count, what makes a change worth mentioning,
 * the three modes, and what each answer does. What is left over there is three lines that create
 * VS Code's watchers and forward a path. Same split as [DocumentSymbols] and its `Settle`.
 *
 * ## What counts as a change
 *
 * Not every event the watcher delivers. A save fires more than once - a change and a create, or
 * two changes, depending on how the editor writes - so the work is debounced. And the file's text
 * is compared against what was last loaded, because a rewrite that produced identical bytes is not
 * a change the user made: format-on-save and some editors' atomic writes both do that, and being
 * asked to reload a config that did not change is worse than not asking.
 *
 * A change to a file that is *not* the one in force still counts, and that is deliberate rather
 * than sloppy. [vimRcPaths] is every path a config could arrive at, because creating
 * `~/.vimperorrc` while only `~/.vimrc` is loaded changes which file wins - and so does deleting
 * the one that won. Whether that *is* a change is settled by re-running [findVimRc] and comparing
 * the path as well as the text.
 */
internal class VimrcWatch(
  private val host: VimHost,
  private val environment: (String) -> String? = { injector.systemInfoService.getenv(it) },
  private val readText: (String) -> String? = ::textOrNull,
  private val locate: ((String) -> String?) -> String? = { env -> findVimRc(NodeFileSystem(), env) },
  private val ask: (String, List<String>, (String?) -> Unit) -> Unit = ::showVimrcQuestion,
  private val announce: (String) -> Unit = {},
  private val mode: () -> VimrcReload = ::vimrcReloadMode,
) {

  /** The path and text of the config as it was last run, which is what a change is measured against. */
  private var loadedPath: String? = null
  private var loadedText: String? = null

  private var pending: ScheduledTask = ScheduledTask.NONE

  /** True while a reload of this host's own doing is in flight, so it cannot ask about itself. */
  private var reloading = false

  /** Every path to watch. Empty under [VimrcReload.NONE], which is how the watchers come to not exist. */
  fun pathsToWatch(): List<String> = if (mode() == VimrcReload.NONE) emptyList() else vimRcPaths(environment)

  /**
   * Remembers what is in force. Called after the config is run, by startup and by a reload alike.
   *
   * [path] is null when there was no config to run, which is a state worth holding rather than
   * ignoring: the interesting change then is a file *appearing*.
   */
  fun remember(path: String?) {
    loadedPath = path
    loadedText = path?.let(readText)
  }

  /** Where the debounce has got to, so a test can run it without waiting. */
  internal val isPending: Boolean get() = pending !== ScheduledTask.NONE

  /** A watcher fired. Debounced, because one save is more than one event. */
  fun noticed() {
    if (reloading) return
    pending.cancel()
    pending = injector.application.schedule(DEBOUNCE_MILLIS) { settle() }
  }

  /** The debounce elapsed: work out whether anything actually changed, and act on it. */
  internal fun settle() {
    pending = ScheduledTask.NONE
    val mode = mode()
    if (mode == VimrcReload.NONE) return

    val path = locate(environment)
    val text = path?.let(readText)
    if (path == loadedPath && text == loadedText) return

    when (mode) {
      VimrcReload.AUTO -> reload()
      VimrcReload.PROMPT -> askAbout(path)
      VimrcReload.NONE -> Unit
    }
  }

  /**
   * The question, and it is as short as it is on purpose.
   *
   * **A notification button cannot carry a tooltip**, which is worth writing down because it is the
   * obvious next idea: `MessageItem` has two fields, `title` and `isCloseAffordance`, and neither
   * is a hover - `tooltip` appears 37 times in `vscode.d.ts` and not once near a notification.
   * `MessageOptions.detail` is the nearest thing and fails twice over, belonging to the message
   * rather than to a button and being documented as rendered only for a *modal* message, which an
   * unsolicited "your file changed" must not be.
   *
   * So the sentence is the only place anything about the buttons could go, and it is deliberately
   * not used for that: what the two do differently belongs in the README, where there is room to be
   * accurate about it, rather than in a notification that has to be read at a glance. The short
   * version is that re-running a file applies what it says and is silent about what it no longer
   * says - see [VimHost.reloadVimRc].
   */
  private fun askAbout(path: String?) {
    // The verb is the button's, in all three: a question that says "Load" over a button that says
    // "Reload" makes the reader check whether they are the same thing.
    val message = when {
      path == null -> "Your Vimperor config is gone. Reload to go back to the defaults?"
      path != loadedPath -> "$path is now your Vimperor config. Reload it?"
      else -> "$path changed. Reload it?"
    }
    val answers = VimrcAnswer.entries.map { it.label }
    ask(message, answers) { pressed ->
      when (VimrcAnswer.entries.firstOrNull { it.label == pressed }) {
        VimrcAnswer.RELOAD -> reload()
        VimrcAnswer.RESTART -> host.run(VsCodeCommands.RESTART_EXTENSION_HOST, waitForIt = false)
        // Dismissed, or the notification closed itself. Either way the new text becomes the
        // baseline: being asked once about an edit is help, and being asked again about the same
        // edit on the next unrelated save is nagging.
        VimrcAnswer.DISMISS, null -> remember(path)
      }
    }
  }

  /**
   * Re-runs the config the way startup does, after dropping what the last run mapped.
   *
   * [reloading] guards the window in which this is writing: `loadVimRc` can touch the file through
   * `:source`, and an event arriving from that would ask the user about a change they had just
   * approved.
   */
  private fun reload() {
    reloading = true
    val path = try {
      host.reloadVimRc()
    } finally {
      reloading = false
    }
    remember(path)
    announce(if (path == null) "No config to load." else "Loaded $path")
  }

  private companion object {
    /**
     * Long enough that one save is one question.
     *
     * A save is at least two events and sometimes three - a write, a rename, a create - and they
     * arrive within a few milliseconds of each other. The symbol cache debounces for the same
     * reason and this is the same order of magnitude; the cost of being slow here is nothing,
     * because nobody is waiting.
     */
    const val DEBOUNCE_MILLIS = 250
  }
}

/**
 * A file's text, or null when there is none to read.
 *
 * `readText` throws for a missing file, which is right for `:source` - the user named it - and
 * wrong here, where the file not being there is one of the answers.
 */
private fun textOrNull(path: String): String? = try {
  NodeFileSystem().readText(path)
} catch (e: Throwable) {
  null
}

/** The real question, which is a VS Code notification with buttons. */
private fun showVimrcQuestion(message: String, answers: List<String>, onAnswer: (String?) -> Unit) {
  window.showInformationMessage(message, *answers.toTypedArray())
    .then({ pressed -> onAnswer(pressed) }, { onAnswer(null) })
}
