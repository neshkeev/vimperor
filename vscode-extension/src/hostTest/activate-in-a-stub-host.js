/*
 * Copyright 2003-2026 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

/*
 * Loads the extension the way VS Code does, and checks that the engine is alive inside it.
 *
 * Nothing else in the build can make this claim. The Kotlin/JS tests prove the engine works when
 * compiled *with* a test; they say nothing about the bundle a host loads, and phase 4 is the reason
 * that distinction is not academic - the engine's library passed every test while compiling to a
 * 561-byte shell. A static check on the bundle is no better: removing every engine reference from
 * the extension still leaves a `require` of the engine and 579KB of it on disk, because the
 * engine's own `@JsExport` is re-exported through the extension. Only running it separates an
 * engine that is live from one that is merely present.
 *
 * `vscode` is injected by the extension host and cannot be installed, so the module is stubbed:
 * the parts of it this extension calls, and nothing else.
 */

const assert = require('assert')
const path = require('path')
const Module = require('module')

const extensionRoot = path.resolve(__dirname, '..', '..')
const manifest = require(path.join(extensionRoot, 'package.json'))

const output = []
const registeredCommands = new Map()

const disposable = () => ({ dispose() {} })

const vscode = {
  window: {
    createOutputChannel(name) {
      assert.strictEqual(name, 'IdeaVim')
      return { ...disposable(), appendLine: (line) => output.push(line), show() {} }
    },
    showInformationMessage: () => undefined,
  },
  commands: {
    registerCommand(command, callback) {
      registeredCommands.set(command, callback)
      return disposable()
    },
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

assert.ok(
  output.some((line) => line.includes('Vim pattern compiled: valid')),
  `activation did not compile a Vim pattern. Output was:\n${output.join('\n')}`,
)

// Every command the manifest contributes has to exist, or the palette offers an entry that errors.
for (const { command } of manifest.contributes.commands) {
  assert.ok(registeredCommands.has(command), `the manifest contributes \`${command}\`, which the extension never registers`)
}

// The engine has to *discriminate*, not merely return. A constant `"valid"` would pass everything
// above; only a pattern Vim rejects shows the parser is really running.
output.length = 0
registeredCommands.get('ideavim.checkPattern')('\\(foo')
assert.ok(
  output.some((line) => line.includes('not valid')),
  `an unclosed group was accepted, so the engine is not parsing. Output was:\n${output.join('\n')}`,
)

assert.ok(subscriptions.length >= 2, 'the extension registered nothing for VS Code to dispose')

extension.deactivate()

console.log(`The extension activated, registered ${registeredCommands.size} command(s), and compiled Vim patterns through the engine.`)
