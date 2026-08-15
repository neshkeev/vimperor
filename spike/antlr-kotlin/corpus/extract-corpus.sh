#!/usr/bin/env bash
# spike/antlr-kotlin/corpus/extract-corpus.sh
# Extracts literal Vimscript commands from the IntelliJ-bound test suite.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"

grep -rhoE 'enterCommand\("([^"\\]|\\.)*"\)' "$REPO_ROOT/src/test" --include=*.kt \
  | sed -E 's/^enterCommand\("//; s/"\)$//' \
  | grep -v '\$' \
  | python3 -c '
import sys
for line in sys.stdin:
    line = line.rstrip("\n")
    # Unwind Kotlin string escapes: \\ -> \, \" -> ", \t, \n
    out, i = [], 0
    while i < len(line):
        if line[i] == "\\" and i + 1 < len(line):
            nxt = line[i+1]
            out.append({"\\": "\\", "\"": "\"", "t": "\t", "n": "\n", "$": "$"}.get(nxt, "\\" + nxt))
            i += 2
        else:
            out.append(line[i]); i += 1
    joined = "".join(out)
    if joined.strip() and "\n" not in joined:
        print(joined)
' \
  | sort -u
