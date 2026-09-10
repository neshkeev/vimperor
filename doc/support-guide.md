# Support Guide

This document is for whoever answers Vimperor's issues.
It's not intended to be read by the users as it brings no value to them.

## Support channels

- GitHub issues: https://github.com/neshkeev/vimperor/issues

## Expectations from the support

- Process incoming issues
- Request additional information
- Help users with simple cases, linking to the README where it already answers them
- Try to reproduce the issue
- Try to help the user if this is a misconfiguration or incorrect usage
- Turn a confirmed bug into a failing test before it is fixed

## What to ask for

- **A trace.** With `vimperor.trace` turned on in VS Code's settings, Vimperor writes what every key did - the
  mode, the carets, the selections handed to VS Code - and every selection change VS Code reports to the
  *Vimperor* output channel. A trace from the window where the problem happened is worth more than a description.
- **The editor and the versions.** Vimperor runs in VS Code and in its forks, and `:set ide?` answers with the
  editor's name. The Extensions view shows Vimperor's version.
- **Other Vim extensions.** Two Vim emulators installed at the same time compete for the same keys.

# ~/.vimperorrc file

`~/.vimperorrc` is the file that is used for Vimperor configuration. It may affect behaviour of the program,
so it makes sense to additionally request this file in case the issue can't be reproduced. The first line
Vimperor writes to its output channel names the config file it loaded.
