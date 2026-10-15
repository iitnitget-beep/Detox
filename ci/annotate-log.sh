#!/usr/bin/env bash
# Turns interesting lines of a CI log into GitHub annotations, so failures (and test totals) are visible in the
# Checks UI and through the API without downloading the full log.
#
# Usage: ci/annotate-log.sh <logfile> <error|warning|notice> <extended-regex> [title]
set -uo pipefail
log="$1"; level="$2"; pattern="$3"; title="${4:-CI log}"
[ -f "$log" ] || { echo "::$level title=$title::log file $log not found"; exit 0; }

# Matching lines with 2 lines of context, at most 400 lines in total.
grep -E -A2 -- "$pattern" "$log" | grep -v -- '^--$' | head -n 400 > /tmp/annotate.$$ || true
if [ ! -s /tmp/annotate.$$ ]; then
  [ "$level" = "error" ] && tail -n 60 "$log" > /tmp/annotate.$$
fi

# GitHub shows at most 10 annotations of each level per step: pack ~40 lines into each one.
split -l 40 /tmp/annotate.$$ /tmp/annotate.$$.part. 2>/dev/null || true
n=0
for part in /tmp/annotate.$$.part.*; do
  [ -f "$part" ] || continue
  n=$((n + 1)); [ "$n" -gt 10 ] && break
  body=$(sed -e 's/%/%25/g' -e 's/\r//g' "$part" | awk 'BEGIN{ORS="%0A"} {print}')
  echo "::$level title=$title ($n)::$body"
done
rm -f /tmp/annotate.$$ /tmp/annotate.$$.part.*
exit 0
