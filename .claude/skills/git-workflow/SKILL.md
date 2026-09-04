---
name: git-workflow
description: Git conventions for this fork - branches, commit messages, remotes, and what is safe to push. Use when creating commits, managing branches, or any git activity in this repository.
---

# Git Workflow

## Remotes

    origin    git@github.com:neshkeev/vimperor.git    the fork, and where this work goes
    upstream  git@github.com:JetBrains/ideavim.git    read-only, never pushed to

This is a **hard fork**. Nothing here is destined for the upstream project, `vim-engine` may
be changed freely, and there is no upstream review to satisfy. `upstream` is kept
to read from - IdeaVim's history is often the fastest answer to "why is this code
like this" - and must never be pushed to.

## Branching

- **`master`** is the trunk. Keep it working: it is what a `runIde` or an
  extension build is taken from.
- Feature branches are fine for anything long-running. Name them for the work,
  not for a ticket: `block-visual-carets`, `crlf`, `packaging`.
- Rebase rather than merge. Linear history.

## Commits

**Write a subject that says what changed, in plain words.** No ticket prefix -
this fork has no ticket tracker. What the log actually looks like:

    Fix: n on the only match searched the whole file twice
    Correct the repository URLs to the renamed remote
    An icon for Vimperor, and metadata that stops naming IdeaVim
    phase5: the remembered column belongs to the block, not to one caret

Rules that matter here:

- **One focused change per commit.** If the change is large or has several
  logical parts, split it - rename in one commit, update the callers in the next,
  add the new behaviour in a third.
- **The body explains why.** This repository's commit bodies carry the reasoning:
  what was wrong, what Vim does, what was measured, what was ruled out. That is
  deliberate, and it is where most of the port's design record lives. Keep it up.
- **Include the tests** with the behavioural change, in the same commit.
- **Say what you ran.** "IdeaVim's own search suites pass unchanged" is worth a
  line, because an engine change touches both hosts.

`VIM-XXXX` and `fix(VIM-XXXX):` are IdeaVim's conventions and mean nothing here.
The second one used to move a YouTrack ticket to "Ready To Release"; that
automation is not ours and is switched off.

## Pushing

**Nothing has been pushed to `origin` yet, and `master` tracks nothing.** Before
the first push:

- Confirm the user actually wants it. This is a public repository.
- `git push -u origin master` sets the tracking branch. Consider whether a fork's
  `master` - which still carries IdeaVim's entire history - is the default branch
  you want the GitHub page to open on.
- Every GitHub Actions workflow inherited from IdeaVim was deleted in commit
  `146db64f5`, not merely switched off. `.github/workflows/` holds one file, and it
  is this fork's own. `git show 146db64f5` recovers any of the twenty-eight.

## Issue tracking

There isn't one yet. `VIM-XXXX` tickets belong to IdeaVim's own tracker and are not
this fork's to close; `gh issue` would target `neshkeev/vimperor`, where no issues
have been filed. If a bug needs recording, the honest places are a commit body, a
comment at the code, or `known-fixture-failures.txt` - all of which this project
already uses that way.
