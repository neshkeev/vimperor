# Configuring conflicting keys

Some keys mean something to both VS Code and Vim, such as `<C-C>`, `<C-F>` or `<C-W>`.
In VS Code, the editor decides which of them reaches Vimperor: printable characters arrive through VS Code's
`type` command, and every other key arrives through a keybinding in Vimperor's manifest. So the choice is made
in VS Code's keybindings rather than in `~/.vimperorrc`.

## `:sethandler`

`:sethandler` is accepted, so a config that uses it loads without errors, but it has no effect in Vimperor.
A key VS Code has already handed to Vimperor cannot be handed back from inside the extension.

## The keys Vimperor takes

To see every key Vimperor binds, open *Preferences: Open Keyboard Shortcuts* and search for `vimperor.key`.
They apply while an editor has focus. Besides Escape, Enter, Tab, Backspace, Delete and Insert, they include
Ctrl chords, in two groups.

In every mode:

`<C-[>` `<C-C>` `<C-O>` `<C-R>` `<C-W>` `<C-Up>` `<C-Down>` `<C-Left>` `<C-Right>` `<C-Home>` `<C-End>`
`<C-PageUp>` `<C-PageDown>`

Outside Insert and Replace mode only; while you type, these are VS Code's:

`<C-A>` `<C-B>` `<C-D>` `<C-E>` `<C-F>` `<C-G>` `<C-H>` `<C-I>` `<C-J>` `<C-K>` `<C-L>` `<C-M>` `<C-N>`
`<C-P>` `<C-T>` `<C-U>` `<C-V>` `<C-X>` `<C-Y>` `<C-]>` `<C-\>`, and `<C-^>` on the `6` key

`<C-Up>` and `<C-Down>` also stand aside while the suggestion list is open. On macOS these are the Control key,
not Command.

## Giving a key back to VS Code

Remove Vimperor's binding in `keybindings.json`, which *Preferences: Open Keyboard Shortcuts (JSON)* opens:

```json
[
  { "key": "ctrl+f", "command": "-vimperor.key" }
]
```

`<C-F>` then does whatever VS Code binds it to, in every mode.

## Giving a key back in some modes only

Vimperor keeps the context key `vimperor.mode` set to the current mode, so a binding can apply in some modes and
not in others. To keep `<C-C>` as Vim's everywhere except Insert mode, where VS Code's own binding for it applies,
remove Vimperor's binding and add it back with a condition:

```json
[
  { "key": "ctrl+c", "command": "-vimperor.key" },
  {
    "key": "ctrl+c",
    "command": "vimperor.key",
    "args": "<C-C>",
    "when": "editorTextFocus && vimperor.mode != 'INSERT'"
  }
]
```

The `args` value is the key in Vim's notation, which is what Vimperor receives.

`vimperor.mode` is the name the status bar shows for the mode. The mode lists of `:sethandler` translate like
this:

| `:sethandler` | `when` clause                           |
|---------------|-----------------------------------------|
| `n`           | `vimperor.mode == 'NORMAL'`             |
| `i`           | `vimperor.mode == 'INSERT'`             |
| `x`           | `vimperor.mode =~ /^VISUAL/`            |
| `s`           | `vimperor.mode =~ /^SELECT/`            |
| `v`           | `vimperor.mode =~ /^(VISUAL\|SELECT)/`  |
| `c`           | `vimperor.mode == 'COMMAND'`            |
| `a`           | leave `vimperor.mode` out               |

The other values are `REPLACE` and `OP PENDING`, and Visual and Select mode add ` LINE` or ` BLOCK` to their
names: `VISUAL LINE`, `SELECT BLOCK`.
