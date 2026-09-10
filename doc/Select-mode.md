# FAQ

## What is select mode?

This mode is where the selection works the same as a selection in any other editor. When you start typing, the text in the selected area is removed and replaced by the new characters that are being typed in.

## How do I start select mode?

- `gh` in Normal mode starts characterwise Select mode, and `gH` linewise Select mode.
- `<C-G>` switches between Visual mode and Select mode, in either direction.
- With `set selectmode=cmd`, `v`, `V` and `<C-V>` start Select mode instead of Visual mode.
- With `set selectmode=key` and `set keymodel+=startsel`, a shifted cursor key such as `<S-Right>` starts Select mode.

## How do I leave it?

`<Esc>` returns to Normal mode. With `stopselect` in `'keymodel'`, which is the default, a cursor key pressed without Shift also ends the selection.

## Does renaming a symbol start select mode?

No. VS Code asks for the new name in an input box of its own, so the mode does not change.

# See Also

* Vimperor options: [set-commands.md](set-commands.md)  
* Vim documentation about select mode: https://vimhelp.org/visual.txt.html#Select-mode  
* Stackoverflow explanation: https://vi.stackexchange.com/questions/4891/what-is-the-select-mode-and-when-is-it-relevant-to-use-it
