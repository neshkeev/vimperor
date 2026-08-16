# Controller rulings — phase 0 gate

Seven decisions made by the controller during the run, each overriding or amending the
plan. Preserved here because they changed the shape of the deliverable and otherwise
existed only in the gitignored SDD workspace, which was deleted at the end of the run.

Each records what was decided, why, and what it would have cost if wrong.

---

**Ruling 1 — Add the foojay toolchain resolver to the spike build.**
Add `org.gradle.toolchains.foojay-resolver-convention` 1.0.0 to the spike's
`settings.gradle.kts` pluginManagement, mirroring the root build.
*Why:* the plan mandates `jvmToolchain(21)` but PATH java was 17. Gradle auto-detection
would probably find the installed 21.0.8 — but "probably" is a bad property for the first
task of a gate whose entire purpose is unambiguous signal. The root build already solves
this exact problem this way.
*Cost if wrong:* one extra plugin line in a throwaway build. Negligible.

---

**Ruling 2 — Task 6 may create a temporary test inside `vim-engine/src/test/`.**
Notwithstanding the Global Constraint "do not modify `vim-engine/`". The constraint's
intent is that the main build is unmodified when the phase *ends*, and the step already
mandates deleting the file. Task 6 must additionally verify `git status --short vim-engine src`
is empty before committing.
*Why:* the alternative — a separate Gradle module built solely to reach the Java parser —
costs far more than a create-then-delete plus a verification step.
*Cost if wrong:* a stray test file left in `vim-engine`, caught by the added check.
*Outcome:* verified clean twice, and the final review confirmed per-commit (not merely
net) that `GoldenDumpTest.kt` never reached any commit.

---

**Ruling 3 — `VimscriptCorpusTest.kt` goes in `src/jvmTest/`, not `src/commonTest/`.**
The plan's File Structure section listed `commonTest`; the Task 6 step body said `jvmTest`
and explained why (file I/O is awkward in JS commonTest). The File Structure listing was stale.
*Why:* Task 5 independently establishes JVM/JS runtime parity, so the differential test
gains nothing from also running on JS.
*Cost if wrong:* trivial — a compile error would surface it immediately.
*Consequence carried into the verdict:* this is why the Vimscript differential has JVM-only
behavioral evidence (verdict §4, caveat 7).

---

**Ruling 4 — Rename `start=` → `rangeStart=` in the SPIKE'S COPY of `RegexParser.g4` only.**
The original in `vim-engine/src/main/antlr/` stays untouched. Task 3's ported
`CollectionElementVisitor` then reads `ctx.rangeStart.text`.
*Why:* this overrode the brief's "RegexParser.g4 stays byte-identical", but the plan already
sanctions editing the spike's copy of a grammar — Steps 2–3 do exactly that to `RegexLexer.g4`.
Verified semantically inert: `CollectionElementVisitor.kt:27` reads `ctx.start.text`, and for
the `RangeColElem` alternative the labelled token *is* the rule's start token, so Java resolves
both to the same `Token` today. The `end=` label does not collide (the runtime member is `stop`).
*Cost if wrong:* collection-range regex tests fail loudly in Task 4. Not a silent failure.
*Gate significance:* caveat-class. Phase 1 must apply this rename to the real grammar and update
`CollectionElementVisitor`. It also reveals that the current Java code depends on silent field
hiding of an inherited runtime member.
*Generalisation (Task 2 evidence):* via `javap` on `ParserRuleContext`, ANY token label in ANY
grammar named `children`, `start`, `stop`, `exception`, `payload` or `parent` hits the same
Kotlin-final wall. Phase 1 must audit all three grammars against that list, not just fix the
one instance found here.

---

**Ruling 5 — Preserve the per-task reports into `findings/` before deleting the workspace.**
Task 8's scope amended accordingly: copy the per-task reports in as appendices, not merely
summarise them.
*Why:* `findings/` is committable; the SDD workspace is entirely gitignored AND the process
ends by deleting it. At the time of the ruling, ~1,000 lines of friction log — the primary
evidence behind a GO/NO-GO worth months of engineering — existed only on disk, outside git,
scheduled for `rm -rf`. The usual "git history is the record now" justification for deleting
the workspace does not hold when the material was never in git.
*Cost if wrong:* a slightly bulkier `findings/`. Trivial against losing the evidence.
*Outcome:* all seven appendices verified byte-identical to their originals before deletion.

---

**Ruling 6 — The plan's three-bin failure taxonomy is inadequate; add a fourth bin.**
Bins were grammar / error-strategy / spike-artifact. Added: **runtime/library divergence** —
a defect in antlr-kotlin's shared runtime infrastructure, independent of any grammar. Task 4's
single failure was reclassified into it.
*Why:* the failure reproduces through the bare `CharStreams.fromString`/`LA()` API with no
grammar and no generated ATN involved, so "grammar divergence" as the plan defines it
("antlr-kotlin generates a parser that accepts/rejects differently") is factually wrong. It is
not error-strategy either: the Java pipeline *succeeds* on this input while the Kotlin one fails
a layer earlier, at input decoding, never reaching the parser. The distinction drives the phase 1
consequence — a grammar-generation defect would mean the toolchain cannot reproduce IdeaVim's
grammar semantics (structural); a CharStream bug in one runtime version is narrow, reproducible
under one stated condition, and plausibly upstream-patchable.
*Cost if wrong:* the gate report describes one narrow bug slightly too charitably. Mitigated
because the condition and repro are recorded verbatim, so a reader can re-judge.
*Integrity note:* reclassifying a failure after seeing it is the classic way to launder a bad
result. The final whole-branch review checked this specifically and found it clean — the new
bin is disclosed in the verdict (§3), and decisively, it is **never used to satisfy the decision
rule**. The rule's assertion clause is scored ❌ on the raw 63/64.

---

**Ruling 7 — Briefs for Tasks 3–7 carry a stale `git add spike/antlr-kotlin findings`.**
`findings/` does not exist until Task 8 creates it. Implementers commit only
`spike/antlr-kotlin` until then; this correction was carried into every remaining dispatch.
*Cost if wrong:* a harmless git error message. Trivial.
*Note:* the ruling stops applying at Task 8, which is the task that creates `findings/`.
