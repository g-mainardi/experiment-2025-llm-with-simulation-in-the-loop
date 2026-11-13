#!/usr/bin/env bash
# Feedback comparison: runs every task x cell k times on Flash Lite, with the records in one dir, then judges them.
# The MCP server must be running (./mill mcp_server.run). Run it again after an interruption: cells that already
# have their runs are skipped.
#   scripts/matrix.sh [dir (default runs/matrix-<date>)] [k (default 10)]
set -euo pipefail
cd "$(dirname "$0")/.."
dir=${1:-runs/matrix-$(date +%Y%m%d)}
k=${2:-10}
model=gemini-3.5-flash-lite
set -a
# shellcheck source=/dev/null
. ./.env
set +a
mkdir -p "$dir/errors" "$dir/logs"

tasks=(circle-control circle-bug-half circle-bug-maxby circle-bug-absolute)
# Cell = <prompt>-<condition>. The full information base adapted to the condition (no piece that needs simulate_code
# without it), and the professor's original prompt (no pieces) only with full feedback.
cells=(base-none base-compile base-full prof-full)
declare -A pieces=(
  [base-none]="goal,procedure-none,scala3,classpath,api"
  [base-compile]="goal,procedure-compile,scala3,classpath,api"
  [base-full]="goal,procedure-full,scala3,parameters,classpath,api,simulate-first,stop"
  [prof-full]=""
)

errors=0
# Repetition outermost: an interrupted matrix stays balanced across cells
for rep in $(seq 1 "$k"); do
  for task in "${tasks[@]}"; do
    for cell in "${cells[@]}"; do
      while (($(find "$dir" -maxdepth 1 -name "$cell--*-$task-$model.json" | wc -l) < rep)); do
        echo "== $task $cell, run $rep/$k"
        args=("--condition=${cell#*-}" --max-round-trips=20)
        [[ -n ${pieces[$cell]} ]] && args+=("--pieces=${pieces[$cell]}")
        log=$(mktemp)
        status=0
        ./mill simulation_agent.run "$model" "$task" "${args[@]}" >"$log" 2>&1 || status=$?
        record=$(grep -o 'Run record: [^ ]*' "$log" | cut -d' ' -f3 || true)
        if [[ -z $record ]]; then
          cat "$log"
          echo "No run record: stopping" >&2
          exit 1
        fi
        name=$(basename "$record" .json)
        if ((status == 0)); then
          mv "$record" "$dir/$cell--$name.json"
          errors=0
        else
          # Infrastructure failure (model API, MCP server), not the model's: kept for the cost, then run again
          mv "$record" "$dir/errors/$cell--$name.json"
          grep 'Agent failed' "$log" || true
          if ((++errors == 3)); then
            echo "Three failed runs in a row: stopping" >&2
            exit 1
          fi
        fi
        mv "$log" "$dir/logs/$cell--$name.log"
        done
    done
  done
done

./mill mcp_server.runMain it.unibo.llm.mcp.server.Evaluate "$PWD/$dir"
python3 scripts/summary.py "$dir"
