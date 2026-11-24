#!/usr/bin/env bash
# PreToolUse hook for Bash: before a `git commit`, run the two working-tree checks the commit must pass:
# scripts/check-line-length.sh (lines over 125 characters that the tree adds relative to HEAD, staged or not) and
# scripts/check-doc-references.sh (citations of notes, sections, tests and UPSTREAM.md entries that resolve). Both run
# even when the first fails, so one blocked commit reports everything. Exit 2 blocks the call and hands the reports to
# the model; anything else lets it through.
input=$(cat)
printf '%s' "$input" | grep -Eq '"command"[[:space:]]*:[[:space:]]*"([^"\\]|\\.)*git( -C [^ ]+)? commit' || exit 0
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
failed=0
report=$(scripts/check-line-length.sh 2>&1) || {
  { echo "Lines over 125 characters in this change (scripts/check-line-length.sh):"; echo "$report"; } >&2
  failed=1
}
report=$(scripts/check-doc-references.sh 2>&1) || {
  { echo "Dangling documentation references (scripts/check-doc-references.sh):"; echo "$report"; } >&2
  failed=1
}
[ "$failed" -eq 0 ] || exit 2
exit 0
