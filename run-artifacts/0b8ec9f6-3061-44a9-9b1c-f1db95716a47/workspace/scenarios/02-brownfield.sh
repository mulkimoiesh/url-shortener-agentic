#!/usr/bin/env bash
# Scenario 2: BROWNFIELD
# "Add custom alias support to short URLs" - a real change to an existing
# codebase. The Architecture Agent reads the actual shortener-service source
# tree (CodebaseContextService) to identify impacted files instead of guessing.
set -euo pipefail
cd "$(dirname "$0")"
source _lib.sh

echo "== Starting BROWNFIELD run =="
RESPONSE=$(start_run "Add support for user-supplied custom aliases when creating a short URL, instead of only auto-generated codes. Reject aliases that are already taken." "BROWNFIELD")
echo "$RESPONSE" | pretty
RUN_ID=$(field "$RESPONSE" "runId")
echo "runId=$RUN_ID"

echo "== Approving REQUIREMENTS gate =="
approve_gate "$RUN_ID" "Approved - alias charset restricted to [a-zA-Z0-9-_], max 30 chars, enforced by validation." | pretty

echo "== Inspecting run state - impactedComponents should reference real files from shortener-service =="
get_run "$RUN_ID" | pretty

echo "== Approving ARCHITECTURE gate =="
RESPONSE=$(approve_gate "$RUN_ID" "Design accepted - impacted files correctly identified.")
echo "$RESPONSE" | pretty

STATUS=$(field "$RESPONSE" "status")
if [ "$STATUS" = "PENDING_APPROVAL" ]; then
  echo "== Approving RELEASE gate =="
  approve_gate "$RUN_ID" "All checks green." | pretty
fi

echo "== Final audit log (decision lineage across all stages) =="
get_audit "$RUN_ID" | pretty
