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

const window = {
  activeTextEditor: undefined,
  visibleTextEditors: [],
  createStatusBarItem: () => ({ text: '', tooltip: '', show() {}, hide() {}, dispose() {} }),
  createTextEditorDecorationType: (options) => ({ options, dispose() {} }),
  onDidChangeActiveTextEditor: () => ({ dispose() {} }),
  onDidChangeTextEditorSelection: () => ({ dispose() {} }),
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

const workspace = {
  onDidChangeTextDocument: () => ({ dispose() {} }),
}

const StatusBarAlignment = { Left: 1, Right: 2 }

class ThemeColor {
  constructor(id) {
    this.id = id
  }
}

module.exports = { Position, Range, TextEditorRevealType, StatusBarAlignment, ThemeColor, window, commands, workspace }
