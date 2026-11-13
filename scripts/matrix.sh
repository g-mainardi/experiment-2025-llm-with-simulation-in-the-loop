#!/usr/bin/env bash
# Feedback comparison: runs every task x cell k times on Flash Lite, `jobs` runs at a time, with the records in one dir,
# and judges the records while the runs go on. The MCP server must be running (./mill mcp_server.run). Run it again
# after an interruption: runs already recorded are skipped.
#   scripts/matrix.sh [dir (default runs/matrix-<date>)] [k (default 10)] [jobs (default 4)]
set -euo pipefail
cd "$(dirname "$0")/.."
dir=$(realpath -m "${1:-runs/matrix-$(date +%Y%m%d)}")
k=${2:-10}
jobs=${3:-4}
model=gemini-3.5-flash-lite
set -a
# shellcheck source=/dev/null
. ./.env
set +a
mkdir -p "$dir/errors" "$dir/logs" "$dir/pending"

tasks=(circle-control circle-bug-half circle-bug-maxby circle-bug-absolute)
# Cell = <prompt>-<condition>. The full information base adapted to the condition (no piece that needs simulate_code
# without it), and the bare original prompt (no pieces: no goal, no hints) only with full feedback.
cells=(base-none base-compile base-full bare-full)
declare -A pieces=(
  [base-none]="goal,procedure-none,scala3,classpath,api"
  [base-compile]="goal,procedure-compile,scala3,classpath,api"
  [base-full]="goal,procedure-full,scala3,parameters,classpath,api,simulate-first,stop"
  [bare-full]=""
)

# Agent and evaluator run from their classpath, not through mill: concurrent mill clients in one worktree once ran an
# agent twice in one invocation. mill only builds them, here.
agent=$(./mill show simulation_agent.launcher | tr -d '"' | sed 's/^ref:[^:]*:[^:]*://')
classpath=$(./mill show mcp_server.runClasspath |
  python3 -c 'import json, sys; print(":".join(p.split(":", 3)[3] for p in json.load(sys.stdin)))')
evaluate() { java -Dsun.java2d.opengl=false -cp "$classpath" it.unibo.llm.mcp.server.Evaluate "$dir" "$@"; }

# One run; the record lands in the dir only when complete, so the evaluator never reads a half-written one
run() {
  local cell=$1 task=$2 name=$3 status=0
  local args=("--condition=${cell#*-}" --max-round-trips=20 "--record=$dir/pending/$name.json")
  [[ -n ${pieces[$cell]} ]] && args+=("--pieces=${pieces[$cell]}")
  AGENT_LOG="$dir/logs/$name.debug.log" "$agent" "$model" "$task" "${args[@]}" >"$dir/logs/$name.log" 2>&1 || status=$?
  if [[ ! -e $dir/pending/$name.json ]]; then
    echo "!! $name: no run record, see logs/$name.log"
  elif ((status == 0)); then
    mv "$dir/pending/$name.json" "$dir/$name.json"
    echo "== $name"
  else
    # Infrastructure failure (model API, MCP server), not the model's: kept for the cost, run again in the next pass
    mv "$dir/pending/$name.json" "$dir/errors/$name-$(date +%s).json"
    echo "!! $name: $(grep -m1 'Agent failed' "$dir/logs/$name.log" || echo error)"
  fi
}

# Judges the records as they arrive, on few threads: the runs mostly wait for the model, but simulate_code needs CPU
rm -f "$dir/pending/.runs-done"
(
  while [[ ! -e $dir/pending/.runs-done ]]; do
    evaluate --threads=4 >>"$dir/logs/evaluate.log" 2>&1 || echo "!! Evaluate failed, see logs/evaluate.log"
    sleep 30
  done
) &
evaluator=$!

missing() {
  for rep in $(seq -f %02g 1 "$k"); do
    for task in "${tasks[@]}"; do
      for cell in "${cells[@]}"; do
        [[ -e $dir/$cell--$task--r$rep.json ]] || echo "$cell $task $cell--$task--r$rep"
      done
    done
  done
}

# Repetition outermost: an interrupted matrix stays balanced across cells. Failed runs get two more passes.
for pass in 1 2 3; do
  todo=$(missing)
  [[ -z $todo ]] && break
  echo "-- pass $pass: $(wc -l <<<"$todo") runs"
  while read -r cell task name; do
    while (($(jobs -rp | wc -l) > jobs)); do wait -n || true; done # > jobs: the evaluator is a job too
    run "$cell" "$task" "$name" &
  done <<<"$todo"
  while (($(jobs -rp | wc -l) > 1)); do wait -n || true; done
done

touch "$dir/pending/.runs-done"
# Its round on few threads can last minutes: stop it (records are written atomically) and finish on all threads
children=$(pgrep -P "$evaluator" || true)
kill "$evaluator" 2>/dev/null || true
wait "$evaluator" 2>/dev/null || true
# shellcheck disable=SC2086 # one pid per word
[[ -n $children ]] && kill $children 2>/dev/null || true
evaluate >>"$dir/logs/evaluate.log" 2>&1
tail -1 "$dir/logs/evaluate.log"
python3 scripts/summary.py "$dir"
if [[ -n $(missing) ]]; then
  echo "$(missing | wc -l) runs still missing after three passes: run the script again" >&2
  exit 1
fi
