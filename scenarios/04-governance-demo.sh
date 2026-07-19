#!/usr/bin/env bash
# Scenario 4: GOVERNANCE CONTROLS - bounded retry + rollback, and guardrail block
#
# Two clearly-labeled demo triggers (see TestAgent.java and MockLlmClient.java)
# make these reproducible on demand instead of relying on an LLM misbehaving
# on cue:
#   - "DEMO_RETRY_TRIGGER" in the requirement forces TESTING to fail twice,
#     exercising the bounded retry loop back to IMPLEMENTATION, then succeed.
#   - "DEMO_GUARDRAIL_TRIGGER" makes the mock Implementation Agent emit a
#     file containing a real AWS-key-shaped secret, so the Guardrail Agent's
#     regex genuinely catches it and BLOCKS the run.
set -euo pipefail
cd "$(dirname "$0")"
source _lib.sh

echo "############################################"
echo "# PART A: bounded retry -> eventual success #"
echo "############################################"
RESPONSE=$(start_run "DEMO_RETRY_TRIGGER: add a health check endpoint." "GREENFIELD")
RUN_ID=$(field "$RESPONSE" "runId")
echo "runId=$RUN_ID"
approve_gate "$RUN_ID" "approved" > /dev/null
RESPONSE=$(approve_gate "$RUN_ID" "approved")
echo "Status after architecture gate: $(field "$RESPONSE" "status")"
echo "-- Audit log should show: FAILURE -> retry attempt 1 -> FAILURE -> retry attempt 2 -> SUCCESS --"
get_audit "$RUN_ID" | pretty
echo "-- Metrics: totalRetries should be 2 --"
get_metrics "$RUN_ID" | pretty

echo ""
echo "############################################"
echo "# PART B: guardrail catches a real secret  #"
echo "############################################"
RESPONSE=$(start_run "DEMO_GUARDRAIL_TRIGGER: add a config helper class." "GREENFIELD")
RUN_ID=$(field "$RESPONSE" "runId")
echo "runId=$RUN_ID"
approve_gate "$RUN_ID" "approved" > /dev/null
RESPONSE=$(approve_gate "$RUN_ID" "approved")
STATUS=$(field "$RESPONSE" "status")
echo "Status after architecture gate: $STATUS   (expected: BLOCKED)"
echo "$RESPONSE" | pretty

if [ "$STATUS" = "BLOCKED" ]; then
  echo "-- Resolving the block (simulating a human fixing the flagged file) --"
  resolve_block "$RUN_ID" "Removed the hardcoded AWS key; secrets now come from environment variables." | pretty
fi

echo "== Final audit log for Part B =="
get_audit "$RUN_ID" | pretty
