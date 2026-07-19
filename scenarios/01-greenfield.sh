#!/usr/bin/env bash
# Scenario 1: GREENFIELD
# "Build a URL shortener from scratch with create, redirect, and analytics."
#
# Note on realism: shortener-service already exists (built by hand in Stage 1
# for speed, since re-deriving it from zero through the pipeline would just
# repeat the same demo mechanics as brownfield). This run demonstrates the
# full graph - requirements -> architecture -> implementation -> testing ->
# guardrails -> docs -> release - reasoning about the greenfield-style ask
# against the existing codebase. This trade-off is called out explicitly in
# FINAL_ENGINEERING_SUMMARY.md.
set -euo pipefail
cd "$(dirname "$0")"
source _lib.sh

echo "== Starting GREENFIELD run =="
RESPONSE=$(start_run "Build a URL shortener service from scratch: create short links, redirect to the original URL, and track click counts." "GREENFIELD")
echo "$RESPONSE" | pretty
RUN_ID=$(field "$RESPONSE" "runId")
echo "runId=$RUN_ID"

echo "== Approving REQUIREMENTS gate =="
RESPONSE=$(approve_gate "$RUN_ID" "Spec looks complete for a greenfield build.")
echo "$RESPONSE" | pretty

echo "== Approving ARCHITECTURE gate =="
RESPONSE=$(approve_gate "$RUN_ID" "Design accepted.")
echo "$RESPONSE" | pretty

STATUS=$(field "$RESPONSE" "status")
echo "Run status after architecture approval: $STATUS"

if [ "$STATUS" = "PENDING_APPROVAL" ]; then
  echo "== Approving RELEASE gate =="
  RESPONSE=$(approve_gate "$RUN_ID" "All checks green, approving release.")
  echo "$RESPONSE" | pretty
fi

echo "== Final audit log =="
get_audit "$RUN_ID" | pretty

echo "== Final metrics =="
get_metrics "$RUN_ID" | pretty

echo ""
echo "Run is COMPLETED. Live product is still untouched - review run-artifacts/$RUN_ID/workspace/"
echo "shortener-service/ first, then apply for real with:"
echo "  curl -X POST $BASE_URL/runs/$RUN_ID/apply"
