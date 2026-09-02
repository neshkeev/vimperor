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

/* The two line endings VS Code has. A document reports which it uses and the buffer normalises. */
const EndOfLine = { LF: 1, CRLF: 2 }

/*
 * A selection, which VS Code checks with `instanceof` before it will accept one - so the stub has to
 * be a class here too, or it would take what a real window refuses.
 */
class Selection {
  constructor(anchor, active) {
    this.anchor = anchor
    this.active = active
    this.start = anchor
    this.end = active
  }
}

const TextEditorRevealType = {
  Default: 0,
  InCenter: 1,
  InCenterIfOutsideViewport: 2,
  AtTop: 3,
}

/*
 * The kind of tab that holds an ordinary file. A class, because the extension narrows a tab's input
 * with `instanceof` - which is what VS Code documents, since the other kinds have a different shape.
 */
class TabInputText {
  constructor(uri) {
    this.uri = uri
  }
}

/*
 * Tabs, which a test can arrange: `:tabclose` and `:tabmove` read how many there are and which one
 * is current before they can work out what to tell VS Code to do, so a stub with no tabs would let
 * both of them pass while doing nothing. `:ls` reads more of them - the file behind each tab, and
 * whether it is dirty - and reads them across every group, so this holds groups rather than one
 * list of tabs.
 */
function tab(path, isActive, isDirty) {
  return {
    label: path.split('/').pop(),
    isActive,
    isDirty: isDirty === true,
    input: new TabInputText({ scheme: 'file', path, fsPath: path }),
  }
}

function group(tabs, isActive) {
  return {
    tabs,
    get activeTab() {
      return tabs.find((each) => each.isActive)
    },
    isActive,
  }
}

const tabGroups = {
  _groups: [group([tab('/one', true)], true)],

  /**
   * Replaces every group with one holding these files, the first of them current, and hands the
   * tabs back so a test can mark one dirty.
   */
  _openFiles(paths) {
    this._groups = [group(paths.map((path, index) => tab(path, index === 0)), true)]
    return this._groups[0].tabs
  },

  /** Puts the files in two groups, so that a list built across groups can be told from one that is not. */
  _openInTwoGroups(first, second) {
    this._groups = [
      group(first.map((path, index) => tab(path, index === 0)), true),
      group(second.map((path, index) => tab(path, index === 0)), false),
    ]
    return this._groups
  },

  get all() {
    return this._groups
  },
  get activeTabGroup() {
    return this._groups.find((each) => each.isActive) || this._groups[0]
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

module.exports = { Position, Range, Selection, Uri, TabInputText, EndOfLine, TextEditorRevealType, StatusBarAlignment, ThemeColor, window, commands, workspace, env }
