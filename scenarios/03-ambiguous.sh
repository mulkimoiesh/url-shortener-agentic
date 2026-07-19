#!/usr/bin/env bash
# Scenario 3: AMBIGUOUS
# A genuinely underspecified ask. The Requirements Agent must surface
# ambiguities rather than silently guessing - inspect the "ambiguities"
# array in the response below. In mock mode you'll see a canned placeholder
# noting where a real model would list its specific open questions
# (e.g. "better how - more metrics? a dashboard? real-time vs batch?
# what's the retention period?"); switch app.llm.mode=anthropic to see this
# for real.
set -euo pipefail
cd "$(dirname "$0")"
source _lib.sh

echo "== Starting AMBIGUOUS run =="
RESPONSE=$(start_run "Make the analytics better." "AMBIGUOUS")
echo "$RESPONSE" | pretty
RUN_ID=$(field "$RESPONSE" "runId")
echo "runId=$RUN_ID"

echo "== Requirement spec - check the ambiguities[] and assumptions[] arrays =="
get_run "$RUN_ID" | pretty

echo ""
echo "At this point a human reviewer should read the ambiguities and either:"
echo "  (a) resolve them via notes on the approval call, or"
echo "  (b) reject and restart with a clarified requirement."
echo "This script takes path (a) for demo purposes:"

echo "== Approving REQUIREMENTS gate with clarification =="
approve_gate "$RUN_ID" "Clarifying: 'better' = add a per-day click count breakdown to the stats endpoint. Out of scope: dashboards, real-time streaming, data export." | pretty

echo "== Approving ARCHITECTURE gate =="
RESPONSE=$(approve_gate "$RUN_ID" "Design accepted given the clarified scope.")
echo "$RESPONSE" | pretty

# AMBIGUOUS runs can need an extra human round-trip: re-approving ARCHITECTURE
# after clarification produces a NEW architecture result that itself pauses at
# a fresh gate before cascading to RELEASE. Loop (bounded) instead of a single
# conditional check, so we don't stop one gate short of COMPLETED.
STATUS=$(field "$RESPONSE" "status")
ATTEMPTS=0
while [ "$STATUS" = "PENDING_APPROVAL" ] && [ "$ATTEMPTS" -lt 5 ]; do
  GATE=$(field "$RESPONSE" "pendingGateStage")
  echo "== Approving $GATE gate =="
  RESPONSE=$(approve_gate "$RUN_ID" "All checks green.")
  echo "$RESPONSE" | pretty
  STATUS=$(field "$RESPONSE" "status")
  ATTEMPTS=$((ATTEMPTS + 1))
done

echo "== Final audit log =="
get_audit "$RUN_ID" | pretty
