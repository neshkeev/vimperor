/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

/*
 * Loads the extension the way VS Code does and types into it.
 *
 * The Kotlin tests drive the host directly. This one goes in through the front door: it activates
 * the bundle, takes the `type` handler the extension registered with VS Code, and sends the keys a
 * user would press. Everything between a keypress and a document edit is exercised, including the
 * wiring that only exists in `activate` and that no unit test can see.
 *
 * `vscode` is injected by the extension host and cannot be installed, so the module is stubbed -
 * the parts of it this extension calls, and nothing else. The document and editor mimic the three
 * behaviours that matter: synchronous reads, deferred writes, and ranges resolved against the
 * pre-edit document.
 */

const assert = require('assert')
const path = require('path')
const Module = require('module')

const extensionRoot = path.resolve(__dirname, '..', '..')
const manifest = require(path.join(extensionRoot, 'package.json'))

const output = []
const registeredCommands = new Map()
const disposable = () => ({ dispose() {} })

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

function makeEditor(text) {
  const document = {
    uri: { scheme: 'file', path: '/test/buffer.txt', fsPath: '/test/buffer.txt' },
    fileName: '/test/buffer.txt',
    isUntitled: false,
    version: 1,
    get lineCount() {
      return document._text.split('\n').length
    },
    _text: text,
    getText: (range) => (range ? document._text.slice(document.offsetAt(range.start), document.offsetAt(range.end)) : document._text),
    offsetAt: (position) => {
      const lines = document._text.split('\n')
      let offset = 0
      for (let i = 0; i < position.line && i < lines.length; i++) offset += lines[i].length + 1
      return offset + Math.min(position.character, (lines[position.line] || '').length)
    },
    positionAt: (offset) => {
      const clamped = Math.max(0, Math.min(offset, document._text.length))
      const before = document._text.slice(0, clamped)
      const line = before.split('\n').length - 1
      return new Position(line, clamped - (before.lastIndexOf('\n') + 1))
    },
  }

  return {
    document,
    selection: { anchor: new Position(0, 0), active: new Position(0, 0) },
    selections: [{ anchor: new Position(0, 0), active: new Position(0, 0) }],
    revealRange() {},
    edit(callback) {
      const edits = []
      callback({
        replace: (range, value) => edits.push([document.offsetAt(range.start), document.offsetAt(range.end), value]),
        insert: (position, value) => edits.push([document.offsetAt(position), document.offsetAt(position), value]),
        delete: (range) => edits.push([document.offsetAt(range.start), document.offsetAt(range.end), '']),
      })
      // Back to front, so earlier offsets stay valid - which is what VS Code's "resolved against
      // the pre-edit document" guarantee amounts to.
      for (const [start, end, value] of edits.sort((a, b) => b[0] - a[0])) {
        document._text = document._text.slice(0, start) + value + document._text.slice(end)
      }
      if (edits.length > 0) document.version++
      return { then: (onFulfilled) => (onFulfilled(true), { then: () => {} }) }
    },
  }
}

const editor = makeEditor('')

const vscode = {
  Position,
  Range,
  StatusBarAlignment: { Left: 1, Right: 2 },
  TextEditorRevealType: { Default: 0, InCenter: 1, InCenterIfOutsideViewport: 2, AtTop: 3 },
  window: {
    activeTextEditor: editor,
    visibleTextEditors: [editor],
    createOutputChannel(name) {
      assert.strictEqual(name, 'IdeaVim')
      return { ...disposable(), appendLine: (line) => output.push(line), show() {} }
    },
    createStatusBarItem: () => ({ ...disposable(), text: '', tooltip: '', show() {}, hide() {} }),
    showInformationMessage: () => undefined,
    onDidChangeActiveTextEditor: () => disposable(),
    onDidChangeTextEditorSelection: () => disposable(),
  },
  commands: {
    registerCommand(command, callback) {
      registeredCommands.set(command, callback)
      return disposable()
    },
    executeCommand: () => ({ then: () => {} }),
  },
  workspace: {
    onDidChangeTextDocument: () => disposable(),
  },
}

// VS Code resolves `vscode` itself; from Node it has to be intercepted before the bundle asks.
const load = Module._load
Module._load = function (request, ...rest) {
  return request === 'vscode' ? vscode : load.call(this, request, ...rest)
}

// Through `main`, so a manifest pointing at a path that does not exist fails here rather than in
// a VS Code window with no explanation.
assert.ok(manifest.main, 'package.json declares no `main`.')
const extension = require(path.resolve(extensionRoot, manifest.main))

assert.strictEqual(typeof extension.activate, 'function', 'the bundle exports no `activate`')
assert.strictEqual(typeof extension.deactivate, 'function', 'the bundle exports no `deactivate`')

const subscriptions = []
extension.activate({ subscriptions })

// Taking over `type` is how an extension sees ordinary typing at all; without it every letter goes
// straight into the document and Vim never hears about it.
assert.ok(registeredCommands.has('type'), 'the extension did not take over the `type` command')
assert.ok(registeredCommands.has('ideavim.key'), 'the extension registered no handler for named keys')

// Every keybinding in the manifest has to reach a command that exists, or the key does nothing and
// VS Code reports no error worth reading.
for (const binding of manifest.contributes.keybindings) {
  assert.ok(
    registeredCommands.has(binding.command),
    `the manifest binds ${binding.key} to \`${binding.command}\`, which the extension never registers`,
  )
}

const type = (text) => registeredCommands.get('type')({ text })
const press = (notation) => registeredCommands.get('ideavim.key')(notation)

// A user typing: insert, some text, escape, then a normal-mode command.
type('i')
for (const character of 'hello') type(character)
press('<Esc>')

assert.strictEqual(editor.document._text, 'hello', `typing did not reach the document. Got: ${editor.document._text}`)

type('x')
assert.strictEqual(editor.document._text, 'hell', `x did not delete through the front door. Got: ${editor.document._text}`)

// Which proves the keys were interpreted rather than inserted: `x` was typed exactly like the
// letters before it, and this time it deleted.
type('d')
type('b')
assert.strictEqual(editor.document._text, 'l', `db did not delete backwards a word. Got: ${editor.document._text}`)

assert.ok(subscriptions.length >= 4, 'the extension registered too little for VS Code to dispose')

extension.deactivate()

console.log(`The extension activated, took over typing, and ran Vim commands on a document. Output: ${output.join(' | ')}`)
