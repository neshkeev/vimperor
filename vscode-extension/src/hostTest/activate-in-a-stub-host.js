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
const dispatchedCommands = []
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

    // How the file is indented, which VS Code resolves and Vim's Tab and `S` both ask for. Two
    // spaces rather than VS Code's default four, so that a scenario asserting the indent is
    // asserting that this was read and not that a constant happened to match.
    options: { tabSize: 2, insertSpaces: true },

    // A viewport, ten lines tall, because `visibleRanges` is half of VS Code's scrolling API and
    // the commands that use it read the view back to work out where to scroll next.
    viewportHeight: 10,
    topLine: 0,
    get visibleRanges() {
      const last = document.lineCount - 1
      const top = Math.min(Math.max(this.topLine, 0), last)
      const bottom = Math.min(Math.max(top + this.viewportHeight - 1, 0), last)
      return [new Range(new Position(top, 0), new Position(bottom, 0))]
    },
    revealRange(range, revealType) {
      const last = document.lineCount - 1
      const start = range.start.line
      const end = range.end.line
      const middle = start - Math.floor((this.viewportHeight - 1) / 2)
      let top
      if (revealType === 3) top = start
      else if (revealType === 1) top = middle
      else if (revealType === 2) {
        const outside = start < this.topLine || end > this.topLine + this.viewportHeight - 1
        top = outside ? middle : this.topLine
      } else if (start < this.topLine) top = start
      else if (end > this.topLine + this.viewportHeight - 1) top = end - this.viewportHeight + 1
      else top = this.topLine
      this.topLine = Math.min(Math.max(top, 0), last)
    },
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
    // One tab, which is what a stub host has. `:tabclose` reads this before it decides what to do.
    tabGroups: {
      get all() {
        return [this.activeTabGroup]
      },
      activeTabGroup: {
        tabs: [{ label: 'buffer.txt', isActive: true }],
        activeTab: { label: 'buffer.txt', isActive: true },
        isActive: true,
      },
    },
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
    // Records what was asked for and resolves at once. Resolving matters: the extension holds the
    // user's keys until a command it is waiting on lands, so a stub whose promise never settled
    // would silently swallow every scenario after the first fold.
    executeCommand(command) {
      dispatchedCommands.push(command)
      return { then: (onFulfilled) => (onFulfilled(undefined), { then: () => {} }) }
    },
    // Deliberately short. This is the one list the extension cannot check anywhere but at runtime -
    // every command id it sends is a string written from the documentation - so what is being tested
    // here is that it asks, and that it reports what it did not find. A stub returning everything
    // would only prove the reporting can stay silent.
    getCommands() {
      const known = ['undo', 'redo', 'workbench.action.files.save']
      return { then: (onFulfilled) => (onFulfilled(known), { then: () => {} }) }
    },
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

// `:w` and `:q`, which were reporting "Not implemented yet :(" until now and which nothing had
// noticed, because the sweep that finds holes presses keys and these are typed at a colon.
reset()
dispatchedCommands.length = 0
for (const character of ':wq') type(character)
press('<CR>')
assert.deepStrictEqual(
  dispatchedCommands,
  ['workbench.action.files.save', 'workbench.action.closeActiveEditor'],
  `:wq did not save and close. Got: ${dispatchedCommands.join(', ')}`,
)

// The commands only VS Code can run. Folding, changing editor and jumping to a definition are not
// things this extension can do to a buffer - it asks VS Code by name, and the name is the whole of
// the contract, since nothing here can check what VS Code then did.
reset()
dispatchedCommands.length = 0
type('z')
type('o')
type('g')
type('t')
assert.deepStrictEqual(
  dispatchedCommands,
  ['editor.unfold', 'workbench.action.nextEditor'],
  `the wrong commands went to VS Code: ${dispatchedCommands.join(', ')}`,
)

// Blockwise Visual, which is the only mode that needs the editor to have more than one caret. The
// block covers the first two columns of three lines; `d` takes that rectangle out of each of them,
// and the editor is left with a single caret afterwards.
reset()
type('i')
for (const character of 'one two') type(character)
press('<CR>')
for (const character of 'three four') type(character)
press('<CR>')
for (const character of 'five six') type(character)
press('<Esc>')
for (const character of 'gg') type(character)
press('<C-V>')
for (const character of 'jjld') type(character)

assert.strictEqual(
  editor.document._text,
  'e two\nree four\nve six',
  `<C-V> did not delete a rectangle. Got: ${editor.document._text}`,
)
assert.strictEqual(
  editor.selections.length,
  1,
  `the block left ${editor.selections.length} carets behind after the delete`,
)

// Scrolling, which is the one thing in this file that needs the stub to have a viewport at all.
// Vim moves the view and VS Code will only reveal a range, so every scroll command reads
// `visibleRanges` back to work out where to reveal next - and a stub whose view never moved would
// let all of them pass while doing nothing. Twenty lines, a ten-line window: `<C-E>` moves the view
// down one and leaves the caret where it was, and `zt` puts the caret's line at the top.
reset()
type('i')
for (let line = 0; line < 20; line++) {
  if (line > 0) press('<CR>')
  for (const character of `line ${line}`) type(character)
}
press('<Esc>')
for (const character of 'gg') type(character)

press('<C-E>')
assert.strictEqual(editor.topLine, 1, `<C-E> did not scroll the view. Top line: ${editor.topLine}`)

for (const character of '12G') type(character)
for (const character of 'zt') type(character)
assert.strictEqual(editor.topLine, 11, `zt did not put the caret's line at the top. Top line: ${editor.topLine}`)

type('x')
assert.ok(
  editor.document._text.split('\n')[11] === 'ine 11',
  `zt left the caret somewhere other than line 11. Got: ${editor.document._text.split('\n')[11]}`,
)

// Enter, in Normal mode. An ordinary key, and until the host could answer "no, there is no live
// template running" it threw and took the plugin down with it. Vim moves down a line to the first
// non-blank, so the `x` lands on the `t` and not on the indent.
reset()
type('i')
for (const character of 'one') type(character)
press('<CR>')
for (const character of '  two') type(character)
press('<Esc>')
for (const character of 'gg') type(character)
press('<CR>')
type('x')

assert.strictEqual(
  editor.document._text,
  'one\n  wo',
  `Enter in Normal mode did not move to the first non-blank below. Got: ${editor.document._text}`,
)

// A bracket text object, which is the one text object that has to know what it is looking at. The
// bracket inside the string is not a bracket, so `di(` takes the call's arguments and not the two
// characters between the string's own parenthesis and the closing one.
reset()
type('i')
for (const character of 'f("(", x)') type(character)
press('<Esc>')
for (const character of '0fxdi(') type(character)

assert.strictEqual(
  editor.document._text,
  'f()',
  `di( did not skip the parenthesis inside the string. Got: ${editor.document._text}`,
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

// Tab in insert mode, and `S`, which both take their indent from `editor.options` rather than from
// a Vim option - the engine has none, because IdeaVim asks the IDE. The stub says two spaces, so
// four would mean the setting was never read.
reset()
type('i')
press('<Tab>')
for (const character of 'x') type(character)
press('<Esc>')

assert.strictEqual(
  editor.document._text,
  '  x',
  `Tab did not indent the way the editor is configured to. Got: ${JSON.stringify(editor.document._text)}`,
)

// Two lines, because `cc` on a file that is one line long empties the buffer and the engine starts
// a fresh insert there rather than rebuilding an indent that has nothing to sit on.
reset()
type('i')
for (const character of '    one two') type(character)
press('<CR>')
for (const character of 'rest') type(character)
press('<Esc>')
type('g')
type('g')
type('S')
for (const character of 'new') type(character)
press('<Esc>')

assert.strictEqual(
  editor.document._text,
  '    new\nrest',
  `S did not keep the line's indent. Got: ${JSON.stringify(editor.document._text)}`,
)

// The command-id check, which only exists because a command id cannot be checked at build time.
const commandCheck = output.find((line) => line.includes('commands'))
assert.ok(commandCheck, `the extension did not report on the commands it uses. Output:\n${output.join('\n')}`)
assert.ok(
  commandCheck.includes('editor.fold'),
  `a command this stub does not have was not reported missing. Got: ${commandCheck}`,
)
assert.ok(
  !commandCheck.includes('undo'),
  `a command this stub does have was reported missing. Got: ${commandCheck}`,
)

assert.ok(subscriptions.length >= 4, 'the extension registered too little for VS Code to dispose')

extension.deactivate()

console.log(`The extension activated, took over typing, and ran Vim commands on a document. Output: ${output.join(' | ')}`)
