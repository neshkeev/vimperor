Using a command from another extension is the same as using any of VS Code's own commands: every command has an id, and a mapping can run it with `<Action>(...)`. As an example, let's create a mapping that turns the word under the caret to upper case when you press `<leader>u`.

**Steps to make this mapping**

1. Install the extension that provides the command from the Extensions view. This example uses a command VS Code already has, so there is nothing to install for it.
2. Find the command's id. You can find it by one of the following ways:
    * Open *Preferences: Open Keyboard Shortcuts* from the Command Palette, search for the command by its title, right-click it and choose *Copy Command ID*
    * Execute `:actionlist` in Vimperor. It lists every command this VS Code has, with the keys bound to each, in the Vimperor output channel
3. Try the command before mapping it, with `:action editor.action.transformToUppercase`
4. Copy the command id into the following mapping in your `~/.vimperorrc`:
```vim
nnoremap <leader>u <Action>(editor.action.transformToUppercase)
```
