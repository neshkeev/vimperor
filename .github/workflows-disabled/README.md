# Workflows, switched off

GitHub reads workflows from `.github/workflows` and nowhere else, so moving them
here disables every one of them without changing a line of their content.

They are JetBrains' automation for the IdeaVim plugin, inherited with the fork.
Four of them are on crons and would have started running against this repository
the first time `master` was pushed:

| Workflow                     | Trigger              | What it would do here                          |
|------------------------------|----------------------|------------------------------------------------|
| `updateChangelogClaude.yml`  | daily, 05:00 UTC     | Open a PR editing `CHANGES.md`, IdeaVim's marketplace changelog |
| `codebaseMaintenance.yml`    | weekly, Tue 06:00    | Run `.claude/maintenance-instructions.md` over a random file    |
| `testsMaintenance.yml`       | daily, 07:00 UTC     | Run the `tests-maintenance` skill and open a PR                 |
| `youtrackAutoAnalysis.yml`   | weekly, Mon 09:00    | Reach for a YouTrack project this fork does not own             |
| `syncDoc.yml`                | every push to master | Sync docs to JetBrains' documentation site                      |

The rest - CI, the UI test suites, CodeQL, the Dependabot merger, the release
scripts - are off for the same reason the crons are: they were written for
JetBrains' runners, secrets and marketplace listing, and none of that is
configured here. They are kept rather than deleted so the real CI can be brought
back one file at a time, on purpose.

To re-enable one, move it back:

    git mv .github/workflows-disabled/pr-verification.yml .github/workflows/

Check what it needs before you do. Most of them expect secrets
(`YOUTRACK_TOKEN`, marketplace credentials, an Anthropic key) and a `master`
that tracks JetBrains' repository.
