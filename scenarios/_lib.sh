#!/usr/bin/env bash
# Shared helpers sourced by the scenario scripts in this directory.
# Requires: curl, python3 (for pretty-printing/JSON field extraction).

BASE_URL="${BASE_URL:-http://localhost:8081/api/v1/workflow}"

start_run() {
  local requirement="$1"
  local scenario_type="$2"
  curl -s -X POST "$BASE_URL/runs" \
    -H "Content-Type: application/json" \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"rawRequirement": sys.argv[1], "scenarioType": sys.argv[2]}))' "$requirement" "$scenario_type")"
}

approve_gate() {
  local run_id="$1"
  local notes="$2"
  curl -s -X POST "$BASE_URL/runs/$run_id/approve" \
    -H "Content-Type: application/json" \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"approvedBy": "interview-demo", "notes": sys.argv[1]}))' "$notes")"
}

resolve_block() {
  local run_id="$1"
  local notes="$2"
  curl -s -X POST "$BASE_URL/runs/$run_id/resolve-block" \
    -H "Content-Type: application/json" \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"resolvedBy": "interview-demo", "notes": sys.argv[1]}))' "$notes")"
}

get_run() {
  curl -s "$BASE_URL/runs/$1"
}

get_audit() {
  curl -s "$BASE_URL/runs/$1/audit"
}

get_metrics() {
  curl -s "$BASE_URL/runs/$1/metrics"
}

field() {
  # field '<json>' 'dotted.path' -> value (best-effort, string/number/bool)
  python3 -c '
import json, sys
data = json.loads(sys.argv[1])
path = sys.argv[2].split(".")
for p in path:
    data = data[p]
print(data)
' "$1" "$2"
}

pretty() {
  python3 -m json.tool
}
