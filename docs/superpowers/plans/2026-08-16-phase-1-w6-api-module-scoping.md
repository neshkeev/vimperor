# W6 — give `:api` a `commonMain`: scoping

**Raised by:** phase 1 task 5, which found `com.intellij.vim.api.*` unresolvable in
`vim-engine`'s `commonMain`. Not a workstream the spec had.
**Question:** does W6 belong in phase 1, or in phase 3 with W1–W4?

**Recommendation: phase 3. Do not do it now.** It is cheap, and it buys exactly nothing
until the core SCC is freed.

---

## 1. Cost: low

`:api` is 33 files with **4 direct blockers**, all of them the same thing —
`org.jetbrains.annotations`, a Java-only library:

| File | Uses |
|---|---|
| `VimApi.kt` | `@ApiStatus.Experimental` |
| `VimInitApi.kt` | `@ApiStatus.Experimental` |
| `models/Mark.kt` | `@Range(from = 0, to = Int.MAX_VALUE.toLong())` on `line`, `col` |
| `models/Jump.kt` | `@Range(from = 0, to = Int.MAX_VALUE.toLong())` on `line`, `col` |

Leaving them in place gives 16 of 33 common. Removing them gives **33 of 33** — `:api`
becomes fully multiplatform.

Both annotations are **advisory only**: no runtime semantics, no behaviour change, nothing
in the engine reads them. Deleting them costs two things, both small and both real:

- IntelliJ stops flagging `VimApi` / `VimInitApi` as experimental to downstream plugin
  authors. That marker is a deliberate public-API signal and deleting it is a public-API
  change, not a refactor.
- Static analysis loses the `0..Int.MAX_VALUE` bound on four `line`/`col` fields.

The honest alternative to deleting them is an `expect`/`actual` typealias, which the phase 1
plan explicitly reserves for phase 3.

## 2. Payoff right now: zero files

| | `vim-engine` `commonMain` |
|---|---:|
| today, W6 unresolved | 68 / 823 |
| W6 resolved, `:api` fully common | **68 / 823** |

Not "a little" — **zero**.

27 engine files reference `:api`. 24 of them have W6 as their *only direct* blocker, and all
24 live in `thinapi/`. That is the bridge layer between `:api` and the engine core, so every
one of them also reaches `VimEditor` / `Command` / `VimCaret` — the 88-file strongly-connected
component from task 4. They are transitively jvm-bound no matter what `:api` does.

The remaining 3 carry W2/W3/W4 blockers of their own.

## 3. Why this is the expected answer

It is the same structural fact task 4 found, showing up in a new place: **no workstream pays
off alone**, because everything downstream of the core SCC is gated on the SCC, not on its own
imports. W6 behaves exactly like W1, W2 and W3 did in task 4's counterfactual — a few files,
or none, until the last blocker falls.

W6 is therefore not a *prerequisite* for anything in phase 1. It is one more item in the set
that must all land together in phase 3.

## 4. What to carry forward

- W6 is **real** and belongs in the phase 3 work-list; the spec did not have it.
- It is the **cheapest** of the workstreams — 4 files, 2 annotations, no `expect`/`actual`
  needed if the public-API markers are considered expendable.
- Sequence it **with** W1–W4, not before them.
- The public-API question (delete `@ApiStatus.Experimental`, or typealias it) is a decision
  for whoever owns `:api`'s compatibility promise. It does not need answering in phase 1.
