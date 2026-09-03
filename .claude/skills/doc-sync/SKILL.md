---
name: doc-sync
description: Keeps this repository's documentation in sync with the code. Use to verify documentation accuracy after code changes, or to check whether the READMEs, doc/, CONTRIBUTING.md or CLAUDE.md still match the codebase. Works in both directions - docs to code, or code changes to docs.
---

# Doc Sync

Keep documentation in sync with the code by finding discrepancies and fixing the
ones that are real.

## Where the documentation is

| File | What it covers | How fast it goes stale |
|------|----------------|------------------------|
| `vscode-extension/DEVELOPMENT.md` | Vimperor: what works, how it is tested, what only a real window found | **Fast.** The port moves under it weekly |
| `vscode-extension/README.md` | The Marketplace page. What the extension is, for a user | **Slow.** Only when what a user sees changes |
| `README.md` | The IdeaVim plugin | Slow |
| `CONTRIBUTING.md` | Architecture, testing guidance, corner cases | Slow, but the architecture section is now partly wrong about module layout |
| `CLAUDE.md` | Commands and conventions read at the start of every session | **Fast, and costly.** A wrong command here is followed, not read |
| `doc/` | IdeaVim's user-facing documentation | Slow |

Two more that are documentation in everything but name, and are worth the same
scrutiny:

- `vscode-extension/src/jsTest/fixtures/known-fixture-failures.txt` - grouped
  explanations of every fixture that does not pass
- KDoc on the extension's tests, which is where the port's reasoning lives

`syncDoc.yml`, which used to push `doc/` to JetBrains' documentation site, is
disabled with the rest of the inherited workflows.

## Core mindset

After code changes, documentation is **guilty until proven innocent**.

❌ "Be conservative, only update if clearly wrong"
✅ "Be aggressive finding issues, conservative making fixes"

Trust order:

1. Working code (highest truth)
2. The API or interface definition
3. Documentation (assume outdated until checked)

## Phase 0: search before you read

### 1. Find working examples

Read at least one working implementation before judging any doc. That is what
"correct" looks like.

```bash
# The extension's own entry point and host
sed -n '1,80p' vscode-extension/src/jsMain/kotlin/Extension.kt

# A recent test, for the conventions actually in use
ls -t vscode-extension/src/jsTest/kotlin/com/maddyhome/idea/vim/vscode/*.kt | head -3
```

### 2. Check what recently changed - especially deletions

```bash
git log --oneline -10 -- '**/[ChangedFile]*'
git log --grep="remove\|rename\|no longer\|drop" --oneline -15
git show <commit> --stat
```

Deletions break documentation far more often than additions do.

### 3. Verify every command a doc tells someone to run

This is the highest-yield check in this repository, because a wrong command in
`CLAUDE.md` or a README is *executed*, not read:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew tasks --all --console=plain | grep -i "<the task the doc names>"
```

`ktlintCheck` and `ktlintFormat` were documented for months and do not exist.
`--tests` is documented for Gradle test tasks and is rejected by `jsNodeTest`.
Both were found this way.

### 4. Grep the docs for signatures and paths

```bash
grep -rEn 'fun \w+\(|\./gradlew [a-zA-Z:]+' *.md doc/*.md vscode-extension/*.md .claude/
grep -rn "src/main/kotlin" *.md doc/*.md .claude/    # vim-engine is KMP now: commonMain
```

## Two modes

### Mode A: documentation → code

1. Find a working implementation as ground truth (Phase 0)
2. Read the documentation
3. Extract **every** code example, signature, path and command
4. For each one: does it exist? Do the parameter names exist? Does the pattern
   match the working code? Does the command run?
5. Fix what is actually wrong

### Mode B: code changes → documentation

1. Understand what was **removed** (Phase 0)
2. Read the diff
3. Find documentation referencing those files, features or APIs
4. Compare each example against the working implementation
5. Update to the correct pattern

## When to update

✅ **Do:**
- A signature changed - parameters added, removed, renamed
- Something was renamed or moved (`src/main/kotlin` → `src/commonMain/kotlin`)
- Behaviour fundamentally changed
- A feature was added or removed
- A path or command in the doc is wrong
- A code example no longer works
- A count or claim is now false ("83 declarations" when there are 102)

❌ **Don't:**
- Internal implementation changed but the public surface did not
- Wording could be better but is accurate
- Formatting
- Different terminology, same meaning
- Test-only changes with no effect on documented behaviour

## Checklist, per code block

- [ ] Extract the complete example
- [ ] Every function call and its parameters identified
- [ ] Each signature exists in the current API
- [ ] Each parameter name exists
- [ ] The pattern matches working code
- [ ] Every command runs
- [ ] Every path exists

Different from working code → **the documentation is wrong**.

## A note on the extension README

It is unusually specific: it names counts, lists what each guard checks, and
records blind spots as they were discovered. That specificity is what makes it
worth having, and also what makes it rot - a number that is quietly wrong is worse
than no number. When the port changes, check:

- Counts of API declarations, command ids, tests, fixtures
- "What works" against what the tests actually cover
- The blind-spot sections, when a new *kind* of blind spot is found - the list is
  meant to grow, and each entry is a lesson, not a status report

## Output

```
## Documentation Sync Report

### Files checked
- ...

### Discrepancies found
1. **[file]: [issue]**
   - Doc says: [quote]
   - Actual: [what the code does]
   - Severity: [critical/minor]
   - Action: [updated / left alone, because ...]

### Updates made
- [file]: [what changed]

### Notes
- [anything needing a human]
```

## Key lessons

1. **Start with the working code, not the documentation.** The implementation is
   ground truth.
2. **Deletions matter more than additions.** What was removed breaks examples.
3. **Verify parameter names, not just function names.** A named parameter that
   does not exist is a critical bug in a doc.
4. **Run the commands.** A documented command that does not exist is the most
   expensive kind of wrong, because it is followed.
5. **Compare patterns, not just signatures.** A function can exist and still be
   used wrongly in the example.
6. **Git history tells the story.** Commits saying "remove", "rename" or "no
   longer" are red flags for nearby documentation.

**Be aggressive in finding issues, conservative in making fixes.**
