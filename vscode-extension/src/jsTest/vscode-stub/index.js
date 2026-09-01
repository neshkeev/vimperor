/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

/*
 * Stands in for `vscode`, which cannot be installed - the extension host injects it, and
 * `require("vscode")` resolves only inside that host. Declared as a local npm dependency of the
 * test compilation so `@file:JsModule("vscode")` resolves under Node and the VS Code-facing code
 * can be tested as ordinary Kotlin.
 *
 * Only the values that must exist when the module loads. Documents and editors are built in Kotlin,
 * where the tests can see them; the two classes below are here because VS Code constructs them with
 * `new` and Kotlin declares them as external classes, so they have to come from the module.
 */

class Position {
  constructor(line, character) {
    this.line = line
    this.character = character
  }
}

class Range {
  constructor(start, end) {
    this.start = start
    this.end = end
  }
}

const TextEditorRevealType = {
  Default: 0,
  InCenter: 1,
  InCenterIfOutsideViewport: 2,
  AtTop: 3,
}

/*
 * Tabs, which a test can arrange: `:tabclose` and `:tabmove` read how many there are and which one
 * is current before they can work out what to tell VS Code to do, so a stub with no tabs would let
 * both of them pass while doing nothing.
 */
const tabGroups = {
  _tabs: [{ label: 'one', isActive: true }],
  get all() {
    return [this.activeTabGroup]
  },
  get activeTabGroup() {
    return {
      tabs: this._tabs,
      activeTab: this._tabs.find((tab) => tab.isActive),
      isActive: true,
    }
  },
}

const window = {
  activeTextEditor: undefined,
  visibleTextEditors: [],
  tabGroups,
  createStatusBarItem: () => ({ text: '', tooltip: '', show() {}, hide() {}, dispose() {} }),
  createTextEditorDecorationType: (options) => ({ options, dispose() {} }),
  onDidChangeActiveTextEditor: () => ({ dispose() {} }),
  onDidChangeTextEditorSelection: () => ({ dispose() {} }),
  onDidChangeWindowState: () => ({ dispose() {} }),
  createOutputChannel: (name) => ({
    name,
    appendLine() {},
    show() {},
    dispose() {},
  }),
  showInformationMessage: () => undefined,
}

const commands = {
  registerCommand: () => ({ dispose() {} }),
  executeCommand: () => ({ then: () => {} }),
}

const env = {
  /** What `gx` and `:help` handed to the operating system, so a test can read it back. */
  opened: [],
  openExternal(uri) {
    env.opened.push(uri.toString())
    return { then: (onFulfilled) => (onFulfilled(true), { then: () => {} }) }
  },
  clipboard: {
    _text: '',
    readText() {
      const text = this._text
      return { then: (onFulfilled) => (onFulfilled(text), { then: () => {} }) }
    },
    writeText(value) {
      this._text = value
      return { then: (onFulfilled) => (onFulfilled(undefined), { then: () => {} }) }
    },
  },
}

const workspace = {
  onDidChangeTextDocument: () => ({ dispose() {} }),
  // No folder open, which is a real state - a single loose file has an editor and no workspace -
  // and the one that keeps `:e` tests honest: every path they use has to be absolute, so nothing
  // passes because a stub happened to root it somewhere convenient.
  workspaceFolders: undefined,
}

/*
 * `Uri` is a class in VS Code with both an instance side and a static one. Only `file` and `parse`
 * are used, and only to hand the result straight back to `vscode.open`.
 */
const Uri = {
  file: (path) => ({ scheme: 'file', path, fsPath: path }),
  parse: (value) => ({ scheme: value.split(':')[0], path: value, fsPath: value, toString: () => value }),
}

const StatusBarAlignment = { Left: 1, Right: 2 }

class ThemeColor {
  constructor(id) {
    this.id = id
  }
}

module.exports = { Position, Range, Uri, TextEditorRevealType, StatusBarAlignment, ThemeColor, window, commands, workspace, env }
