# NERDTree

Vimperor supports the NERDTree plugin over VS Code's Explorer. Update your `~/.vimperorrc` to turn it on:
```vim
Plug 'preservim/nerdtree'
```
or
```vim
set NERDTree
```
Use `set noNERDTree` to disable this extension.

<details>
<summary>Full list of aliases</summary>

```vim
set NERDTree
set nerdtree
Plug 'preservim/nerdtree'
Plug 'https://github.com/preservim/nerdtree'
Plug 'https://github.com/scrooloose/nerdtree'
Plug 'scrooloose/nerdtree'
Plug 'nerdtree'
```
</details>

### Supported commands

These work from any editor, and act on VS Code's Explorer:

- `:NERDTree`
- `:NERDTreeFocus`
- `:NERDTreeToggle`
- `:NERDTreeClose`
- `:NERDTreeFind`
- `:NERDTreeRefreshRoot`

### Keys in the Explorer

A key pressed in VS Code's sidebar never reaches an extension, so these keys are keybindings in Vimperor's
manifest, each running one of VS Code's own commands. They apply while the Explorer has focus and no input box
is open, and only while NERDTree is enabled. The `g:NERDTreeMap*` variables cannot change them; to use other
keys, change the bindings in VS Code's *Keyboard Shortcuts*.

| Key     | Description                                        | VS Code command                               |
|---------|----------------------------------------------------|-----------------------------------------------|
| `j`     | Move down                                          | `list.focusDown`                              |
| `k`     | Move up                                            | `list.focusUp`                                |
| `gg`    | Move to the first node                             | `list.focusFirst`                             |
| `G`     | Move to the last node                              | `list.focusLast`                              |
| `o`     | Open a file, or open or close a directory          | `list.select`                                 |
| `s`     | Open the selected file beside the current editor   | `explorer.openToSide`                         |
| `O`     | Recursively open every directory                   | `list.expandAll`                              |
| `x`     | Close the current directory                        | `list.collapse`                               |
| `r`     | Refresh the Explorer                               | `workbench.files.action.refreshFilesExplorer` |
| `R`     | Refresh the Explorer                               | `workbench.files.action.refreshFilesExplorer` |
| `q`     | Close the sidebar                                  | `workbench.action.closeSidebar`               |
| `n`     | Create File                                        | `explorer.newFile`                            |
| `N`     | Create Directory                                   | `explorer.newFolder`                          |
| `d`     | Delete file or directory                           | `deleteFile`                                  |
| `y`     | Copy file or directory                             | `filesExplorer.copy`                          |
| `v`     | Paste what was copied                              | `filesExplorer.paste`                         |
| `<C-R>` | Rename file or directory                           | `renameFile`                                  |

Not every VS Code build has `list.expandAll`. When it is missing, Vimperor lists it among the commands it could
not find in its output channel at startup, and `O` does nothing.

### Not available

- `t`, `T`, `i`, `gi`, `gs` and `go`: VS Code opens a file in place or beside the current editor, and has no
  other way to open one.
- `p`, `P`, `J`, `K`, `<C-J>` and `<C-K>`: VS Code's list commands move by row and know nothing about depth.
- `X`, `I`, `f`, `F`, `B`, `m` and `A`.
- Everything that changes the tree's root: the Explorer is rooted at the workspace folder, and an extension
  cannot move it.

These keys are left alone rather than bound to something approximate.
