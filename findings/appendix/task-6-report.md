# Task 6 Report: Vimscript grammar — differential testing against a real corpus

## Summary

`Vimscript.g4` generated cleanly under antlr-kotlin (no label collisions, no generation errors). A 1,865-command corpus was extracted from IdeaVim's real test suite, run through both the existing Java parser (golden) and the new Kotlin parser, and their normalized parse trees (`toStringTree`) were compared.

**Result: 0 divergences out of 1,865 commands (0.0% divergence rate).** The Kotlin-generated Vimscript parser reproduced the Java-generated parser's parse tree byte-for-byte on every corpus entry, including entries that trigger ANTLR's default error-recovery path (invalid syntax that produces error nodes rather than exceptions).

## Step 1: Grammar generation

```bash
cp vim-engine/src/main/antlr/Vimscript.g4 spike/antlr-kotlin/antlr/
./gradlew -p spike/antlr-kotlin generateKotlinGrammarSource --console=plain
```

`BUILD SUCCESSFUL in 1s`. All expected files were produced:

```
Vimscript.interp  VimscriptBaseListener.kt  VimscriptLexer.interp  VimscriptLexer.kt
VimscriptLexer.tokens  VimscriptListener.kt  VimscriptParser.kt  VimscriptVisitor.kt
VimscriptBaseVisitor.kt  Vimscript.tokens
```

`VimscriptParser.kt` is 16,183 lines. No label-collision issue: grepped `vim-engine/src/main/antlr/Vimscript.g4` for `children|start|stop|exception|payload|parent` used as a rule-element label — zero matches, confirming Task 2's earlier data point.

## Step 2/3: Corpus extraction — a real bug found and fixed in the brief's script

I copied the extraction script from the brief verbatim first, then ran it. Before fixing anything, the raw output was already suspicious enough to investigate per the "if its behavior looks wrong, say so" instruction.

**Bug found:** in the Python escape-unwinding step, the dict key meant to collapse an escaped backslash (`\\` → `\`) was written as `"\\\\"`, which in real Python source is a **2-character string**. But the lookup key is always `nxt = line[i+1]`, a **single character**. A 2-character key can never match a 1-character lookup, so that branch was dead code. Every occurrence of `\\` in a Kotlin string literal (representing one literal backslash at runtime) was left as two literal backslashes in the extracted corpus instead of being unwound to one.

Verified in isolation:
```python
line = "%s/\\\\d/\\\\=21*2"   # == '%s/\\d/\\=21*2' as read from stdin (2 literal backslashes each)
# ... buggy loop ...
# output: '%s/\\\\d/\\\\=21*2'   (unchanged — should have been '%s/\\d/\\=21*2')
```
This directly contradicted the corresponding real source: `SubstituteCommandTest.kt:1024` has `enterCommand("%s/\\d/\\=21*2")`, whose actual runtime value (what the real Vim/Java parser sees, and what the golden dump was built from) is `%s/\d/\=21*2` — single backslashes.

**Fix:** changed the dict key from `"\\\\"` (2-char string) to `"\\"` (1-char string, a single backslash), so it correctly matches `nxt` when the input contains a doubled backslash, collapsing it to one:
```diff
-out.append({"\\\\": "\\", "\"": "\"", "t": "\t", "n": "\n", "$": "$"}.get(nxt, "\\" + nxt))
+out.append({"\\": "\\", "\"": "\"", "t": "\t", "n": "\n", "$": "$"}.get(nxt, "\\" + nxt))
```

**Impact measured:** diffing the buggy-script output against the fixed-script output, **24 of 1,865 lines** (1.3%) changed — all involving doubled-backslash Kotlin escapes (regex `\d`, `\+`, mapping bars `\|`, `<C-SPACE>` in `mapleader`, `\n` inside `foreach`/`echo`/`read!` arguments, etc.). Example:
```
buggy:  %s/\\d/\\=21*2
fixed:  %s/\d/\=21*2
```
The fixed version is what the real Kotlin compiler produces for that string literal and is the true Vimscript command under test. Had this bug shipped uncorrected, 24 corpus entries would have been testing malformed input (with stray extra backslashes) rather than the real commands from the test suite — inflating apparent lexer/parser exercise of backslash-heavy constructs without it being real. I fixed the script (one-line change, dict key only) and regenerated the corpus with the fix before producing the golden file, so the golden and differential results below reflect the corrected corpus throughout.

**Corpus size:** 1,865 lines — this is *above* the brief's expected 1,700–1,815 range, not below, so it does not trigger the "escape handling is wrong, inspect" concern (which was calibrated for counts far *lower* than expected). The extra ~50–150 lines relative to the brief's estimate are most plausibly due to the test suite having grown since the brief's numbers were set (1,815 raw `enterCommand(...)` matches was itself apparently measured at an earlier point — current `grep -rhoE` raw match count is 4,403 occurrences / 1,918 unique before the `$`-filter). No inputs were dropped by a bug; the `$`-interpolation filter alone removed 1,918 − 1,866 = 52 unique lines (correct, expected behavior — those are template strings, not literal commands), and the escape-unwinding step's collapsing of duplicate strings (post-fix) reduced 1,866 → 1,865 (one line, one dedup).

## Step 4: Golden file (Java parser) — Ruling 2

Created the temporary test exactly as specified in the brief at `vim-engine/src/test/kotlin/com/maddyhome/idea/vim/spike/GoldenDumpTest.kt` (with the standard MIT header added, since it's a `.kt` file, even though temporary). Ran with:
```bash
export JAVA_HOME=/Users/neshkeev/Library/Java/JavaVirtualMachines/corretto-21.0.8/Contents/Home
./gradlew :vim-engine:test --tests '*GoldenDumpTest*' --console=plain
```
`BUILD SUCCESSFUL`. (Note: the ambient `JAVA_HOME`/default `java` resolved to JDK 17; the project requires JDK 21. Had to explicitly set `JAVA_HOME` to Corretto 21.0.8 to build — not a project bug, just an environment note for reproducing this run.)

Produced `spike/antlr-kotlin/corpus/vimscript-golden.txt`: 1,865 lines, tab-separated `input\tparseTree`. **0 `PARSE_ERROR` entries** — the Java parser's default ANTLR error-recovery handled every malformed input by producing a (possibly partial/error-containing) parse tree rather than throwing, consistent with ANTLR's default `DefaultErrorStrategy` behavior; it printed diagnostics to stderr but did not abort.

Deleted the temporary test file, then verified:
```
$ git status --short vim-engine src
(empty)
```
**Ruling 2 verification: PASSED.** No trace of the temporary test remains in `vim-engine/` or `src/`.

## Step 5/6: Differential test and results

Wrote `spike/antlr-kotlin/src/jvmTest/kotlin/com/maddyhome/idea/vim/vimscript/VimscriptCorpusTest.kt` exactly per the brief (Ruling 3: `jvmTest`, not `commonTest`), with the standard MIT header. It reads `corpus/vimscript-golden.txt`, reparses each input with the antlr-kotlin-generated `VimscriptLexer`/`VimscriptParser`, and compares `toStringTree()` output against the golden value. It prints, does not assert, per the brief's explicit instruction.

```bash
export JAVA_HOME=/Users/neshkeev/Library/Java/JavaVirtualMachines/corretto-21.0.8/Contents/Home
./gradlew -p spike/antlr-kotlin jvmTest --tests '*VimscriptCorpusTest*' --console=plain --info
```

Test output (`STANDARD_OUT`):
```
corpus size: 1865, divergences: 0
```
Test XML report: `tests="1" failures="0" errors="0"`.

**Divergence count: 0. Divergence rate: 0.0% (0/1865).**

There is no "first 10 divergences" section to report — there are none. This is the deliverable finding for this task: the antlr-kotlin-generated Vimscript parser is tree-identical to the Java-generated parser across every real Vimscript command in IdeaVim's own test suite, including inputs that exercise ANTLR's error-recovery path (`STANDARD_ERROR` for the run shows the expected `mismatched input` / `no viable alternative` diagnostics from ANTLR's default error listener — printed identically in structure by both runtimes' recovery, hence identical resulting error-node trees).

## Classification of divergences

**None to classify — 0 divergences observed.** No grammar divergence, no error-reporting difference, no corpus-extraction artifact reached the differential comparison (the one real corpus-extraction artifact found — the doubled-backslash bug — was caught and fixed *before* generating the golden file and running the differential, so it does not appear as a divergence; it's documented above as a script-defect finding instead, per the "fix the script but say so" instruction).

## Ruling 2 verification output

```
$ git status --short vim-engine src
```
(no output — clean)

## Files changed (all under `spike/antlr-kotlin/`, committed)

- `spike/antlr-kotlin/antlr/Vimscript.g4` — verbatim copy of `vim-engine/src/main/antlr/Vimscript.g4` (diffed identical)
- `spike/antlr-kotlin/corpus/extract-corpus.sh` — brief's script with the one-line escape-unwinding fix described above
- `spike/antlr-kotlin/corpus/vimscript-corpus.txt` — 1,865 lines, generated
- `spike/antlr-kotlin/corpus/vimscript-golden.txt` — 1,865 lines, generated from the Java parser
- `spike/antlr-kotlin/src/jvmTest/kotlin/com/maddyhome/idea/vim/vimscript/VimscriptCorpusTest.kt` — differential test (prints, does not assert)

Commit: `8ded495a5` — "spike: differential-test Vimscript grammar over 1865-command corpus"

Nothing outside `spike/antlr-kotlin/` was committed (no `findings/` path exists yet per Ruling 7; `.omc/` predates this task and was left untracked/unstaged).

## Self-review findings

- Read the full diff (`git show HEAD`) before reporting. Confirmed: `Vimscript.g4` copy is byte-identical to the source; `VimscriptCorpusTest.kt` matches the brief's structure (still prints, still no assertion — did not "improve" it into a gate); MIT headers present on the new `.kt` file.
- Checked the golden/differential tab-split protocol for a latent bug: if any corpus `input` line contained a literal tab character, `input.split('\t', limit = 2)` would misparse the golden line. Verified with `grep -cP '\t'` over the corpus: **0 matches** — the tab-delimited format is safe for this corpus, but this is a fragility worth flagging for Task 8 (see Concerns).
- Verified `git status --short vim-engine src` is empty (Ruling 2) and that only `spike/antlr-kotlin` is staged/committed (Ruling 7) before running `git commit`.
- Double-checked the escape-unwinding fix against a second real example from the test suite (`nnoremap ,f ?\<fun\><CR>`, `imap foo\ bar`, `mapleader = "\<C-SPACE>"`) — all now correctly single-backslash in the corpus, matching what those Kotlin string literals actually evaluate to.

## Concerns for Task 8's GO/NO-GO report

1. **The brief's corpus-extraction script, as written, had a real bug** that silently produced wrong (double-backslash) input for ~1.3% of the corpus. I fixed it before generating the golden file, so this run's results are clean, but the *brief itself* needs a fix if Task 8 or anyone else re-runs the pipeline from the brief document as-authored. The one-line fix is documented above.
2. **Corpus size (1,865) exceeds the brief's stated 1,700–1,815 range.** Not a red flag on its own (traced to the test suite having grown since the brief's baseline was measured), but Task 8 should not treat "close to but not exactly matching the brief's number" as an anomaly — the extraction logic is sound and the discrepancy is explained above.
3. **Zero divergences is a strong, clean result**, consistent with Task 4/5's finding that antlr-kotlin faithfully reproduces ANTLR's Java runtime behavior. Combined with the regex-grammar findings (63/64 pass, JVM/JS parity), this task adds evidence that the *parser generation and runtime fidelity* itself is not the risk in an antlr-kotlin migration — grammar behavior transfers cleanly for both grammars tested so far.
4. **Caveat on coverage:** the corpus is drawn entirely from IdeaVim's own test suite (`enterCommand("...")` call sites), which is a proxy for "real Vimscript" but is itself curated by IdeaVim's engineers and may not exercise every grammar corner (e.g., deeply nested `function`/`try`/`for` blocks, multi-line constructs, or unusual Unicode) as thoroughly as a broader corpus (e.g. real `.vimrc` files in the wild) would. Task 8 should note this is differential testing against IdeaVim's *known* Vimscript surface, not an exhaustive fuzz of the grammar.
5. Tab-delimited golden-file format (input\ttree) is fragile if a future corpus ever contains a literal tab in an input string — not triggered here (verified 0 occurrences) but worth a note if this harness is reused.
