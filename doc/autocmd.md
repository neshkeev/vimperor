Autocommands
============

Vimperor supports Vim's `:autocmd` for running commands on editor events.
Handlers are registered from `~/.vimperorrc` or interactively in Command-line mode.
Every effort is made to match Vim's behaviour, but some differences are inevitable
because VS Code's event model doesn't map 1:1 onto Vim's.

Syntax
------

```
autocmd [group] {event}[,{event}...] {pattern} {command}
autocmd!
```

- `{event}` — one or more comma-separated event names (see below).
- `{pattern}` — file pattern (see "Patterns" below).
- `{command}` — any Ex command or Vimscript expression.
- `autocmd!` — clears the handlers of the enclosing `augroup`, or every handler when used outside one.

```vim
augroup my_group
  autocmd!
  autocmd BufWritePost *.py echo "saved python"
augroup END
```

Patterns
--------

Autocmd file patterns support the following glob syntax:

| Pattern     | Matches                                  |
|-------------|------------------------------------------|
| `*`         | Any characters except path separators    |
| `**`        | Any characters including path separators |
| `?`         | Any single non-separator character       |
| `[abc]`     | Any character in the set                 |
| `{foo,bar}` | Either `foo` or `bar`                    |

If the pattern contains `/` or `\`, it matches against the buffer's full name;
otherwise it matches against the filename only.

A buffer's full name begins with its URI scheme — `file:///home/me/notes.txt`, or
`untitled://Untitled-1` for a buffer that has never been saved — so a pattern that
names a directory should begin with `**/` or with `file://`:

```vim
autocmd BufWritePost **/src/*.py echo "saved a source file"
autocmd BufEnter file:///home/me/notes/* setlocal wrap
```

Supported events
----------------

### Buffers

| Event          | Fires when                                                                         |
|----------------|------------------------------------------------------------------------------------|
| `BufEnter`     | An editor becomes the active one                                                   |
| `BufLeave`     | Another editor is about to become active, for the editor being left                |
| `BufWritePost` | A document has been written to disk, however the save was started                 |

### Focus

| Event         | Fires when                         |
|---------------|------------------------------------|
| `FocusGained` | The VS Code window gains focus     |
| `FocusLost`   | The VS Code window loses focus     |

### Event order

When switching editors:

```
BufLeave → BufEnter
```

Events that do not fire
-----------------------

`InsertEnter`, `InsertLeave`, `BufRead`, `BufReadPost`, `BufNewFile`, `BufWrite`,
`BufWritePre`, `WinEnter`, `WinLeave` and `FileType` are accepted, so a config that
registers handlers for them loads without errors, but nothing fires them.
`:doautocmd {event}` runs their handlers by hand.

**`BufWritePre`** is left out on purpose. VS Code asks for the edits a save should
include before it writes the file, and Vimperor applies its edits asynchronously, so an
autocommand that changed the buffer — stripping trailing whitespace, say — could land
after the file was written. Firing nothing is better than firing too late.
