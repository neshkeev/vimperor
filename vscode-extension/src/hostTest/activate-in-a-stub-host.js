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
const fs = require('fs')
const os = require('os')
const Module = require('module')

/*
 * A home directory of this test's own.
 *
 * The extension reads the user's `.ideavimrc` at activation, so without this the test would load
 * whatever config the machine running it happens to have - passing here, failing on a laptop whose
 * owner remapped one of the keys below. It also lets the config path be tested rather than merely
 * survived: the file written here has a mapping that the assertions look for.
 */
const home = path.join(os.tmpdir(), 'ideavim-stub-host')
fs.rmSync(home, { recursive: true, force: true })
fs.mkdirSync(home, { recursive: true })
fs.writeFileSync(
  path.join(home, '.ideavimrc'),
  [
    'nnoremap Q db',
    // An 'operatorfunc' for the `g@` scenario below. Defining it here rather than in the test is
    // the point: it makes the config path carry a function declaration and an option, not just a
    // mapping, and it is the only place a Vimscript function is defined the way a user would.
    'function! Cut(type)',
    "  '[,']d",
    'endfunction',
    'set operatorfunc=Cut',
    '',
  ].join('\n'),
)
process.env.HOME = home
process.env.USERPROFILE = home
process.env.XDG_CONFIG_HOME = path.join(home, 'config')
delete process.env.IDEA_VIM_CUSTOM_VIMRC

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
    decorations: new Map(),
    setDecorations(type, ranges) {
      this.decorations.set(type, ranges)
    },
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
  // VS Code constructs these with `new`, so a plain object here would pass the call and fail the
  // construction - which is exactly how this was found.
  ThemeColor: class ThemeColor {
    constructor(id) {
      this.id = id
    }
  },
  TextEditorRevealType: { Default: 0, InCenter: 1, InCenterIfOutsideViewport: 2, AtTop: 3 },
  window: {
    activeTextEditor: editor,
    visibleTextEditors: [editor],
    createOutputChannel(name) {
      assert.strictEqual(name, 'IdeaVim')
      return { ...disposable(), appendLine: (line) => output.push(line), show() {} }
    },
    createStatusBarItem: () => ({ ...disposable(), text: '', tooltip: '', show() {}, hide() {} }),
    createTextEditorDecorationType: (options) => ({ ...disposable(), options }),
    showInformationMessage: () => undefined,
    onDidChangeActiveTextEditor: () => disposable(),
    onDidChangeTextEditorSelection: () => disposable(),
    onDidChangeWindowState: () => disposable(),
  },
  commands: {
    registerCommand(command, callback) {
      registeredCommands.set(command, callback)
      return disposable()
    },
    executeCommand: () => ({ then: () => {} }),
  },
  env: {
    clipboard: {
      text: '',
      readText() {
        const text = this.text
        return { then: (onFulfilled) => (onFulfilled(text), { then: () => {} }) }
      },
      writeText(value) {
        this.text = value
        return { then: (onFulfilled) => (onFulfilled(undefined), { then: () => {} }) }
      },
    },
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

// Tab has to yield to ghost text, or Copilot cannot be accepted with it: a key this extension
// claims never reaches VS Code, and the extension cannot hand it back at runtime because context
// keys are write-only. This is a check on the manifest, not on behaviour - only VS Code evaluates
// a `when` clause - but the guard going missing is the failure that would be silent.
const tab = manifest.contributes.keybindings.find((binding) => binding.key === 'tab')
for (const contextKey of ['inlineSuggestionVisible', 'inlineEditIsVisible']) {
  assert.ok(
    tab.when.includes(`!${contextKey}`),
    `tab claims the key while ${contextKey} is set, so a suggestion cannot be accepted with it. when: ${tab.when}`,
  )
}

const type = (text) => registeredCommands.get('type')({ text })
const press = (notation) => registeredCommands.get('ideavim.key')(notation)

/*
 * Empties the document between scenarios.
 *
 * Twice now an assertion here has been written against a fresh buffer while the previous scenario's
 * text was still in it - and both times the code was right and the expectation was wrong, which is
 * the expensive way round. Each scenario starts from a known state instead.
 */
const reset = () => {
  editor.document._text = ''
  editor.selections = [{ anchor: new Position(0, 0), active: new Position(0, 0) }]
  editor.selection = editor.selections[0]
  press('<Esc>')
}

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

// The mapping from the `.ideavimrc` written above: `Q` is a Vim command of its own, and the config
// remapped it. This is the only check that the configuration path runs at activation.
assert.ok(
  output.some((line) => line.includes('.ideavimrc')),
  `the extension did not report loading a config. Output was:\n${output.join('\n')}`,
)

reset()
type('i')
for (const character of 'alpha beta') type(character)
press('<Esc>')
type('Q')
assert.strictEqual(
  editor.document._text,
  'alpha a',
  `Q was not remapped by the .ideavimrc. Got: ${editor.document._text}`,
)

// `g@` - the operator that is not an operator, handing a motion's range to the function named by
// 'operatorfunc'. Everything a plugin needs is in this one scenario: the config defined `Cut` and
// set the option at activation, `g@` worked out what `l` covered, set `'[` and `']` around it, and
// called the function, whose `:'[,']d` read those marks back. The motion is charwise and on the
// first line, so only the first line goes.
reset()
type('i')
for (const character of 'alpha') type(character)
press('<CR>')
for (const character of 'beta') type(character)
press('<Esc>')
for (const character of 'ggg@l') type(character)

assert.strictEqual(
  editor.document._text,
  'beta',
  `g@ did not run the .ideavimrc's operatorfunc over the first line. Got: ${editor.document._text}`,
)

// The system clipboard, which is what `"+` is. Yanking has to reach it, since that is how text
// leaves the editor for the rest of the machine.
reset()
type('i')
for (const character of 'copied text') type(character)
press('<Esc>')
type('0')
for (const character of '"+y$') type(character)

assert.strictEqual(
  vscode.env.clipboard.text,
  'copied text',
  `a yank to "+ did not reach the system clipboard. Got: ${vscode.env.clipboard.text}`,
)

// And through the `:` prompt, which is where a user reaches everything that is not a keystroke.
reset()
type('i')
for (const character of 'one two one') type(character)
press('<Esc>')
type(':')
for (const character of 's/one/ONE/g') type(character)
press('<CR>')

assert.strictEqual(
  editor.document._text,
  'ONE two ONE',
  `a substitution typed at the prompt did not run. Got: ${editor.document._text}`,
)

// `:registers` prints to the IdeaVim output channel, which is the only place output goes.
reset()
type('i')
for (const character of 'yanked') type(character)
press('<Esc>')
type('0')
for (const character of '"ay$') type(character)
type(':')
for (const character of 'registers') type(character)
press('<CR>')

assert.ok(
  output.some((line) => line.includes('yanked')),
  `:registers printed nothing to the output channel. Output was:\n${output.join('\n')}`,
)

// `.` and `J`, which the engine does not declare - they are the host's own commands, registered
// alongside the generated ones. Nothing but this test runs them through the real bundle.
reset()
type('i')
for (const character of 'one two three') type(character)
press('<Esc>')
type('0')
for (const character of 'dw') type(character)
type('.')

assert.strictEqual(
  editor.document._text,
  'three',
  `dot did not repeat the last change. Got: ${editor.document._text}`,
)

reset()
type('i')
for (const character of 'one') type(character)
press('<CR>')
for (const character of '    two') type(character)
press('<Esc>')
type('g')
type('g')
type('J')

assert.strictEqual(
  editor.document._text,
  'one two',
  `J did not join the two lines. Got: ${editor.document._text}`,
)

assert.ok(subscriptions.length >= 4, 'the extension registered too little for VS Code to dispose')

extension.deactivate()

console.log(`The extension activated, took over typing, and ran Vim commands on a document. Output: ${output.join(' | ')}`)
