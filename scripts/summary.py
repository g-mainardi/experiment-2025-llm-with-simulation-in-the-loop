#!/usr/bin/env python3
"""Summary of a feedback comparison dir (scripts/matrix.sh) as CSV, one row per task x cell plus "all" tasks.

Success comes from the oracle (evaluation.passed), never from the outcome: a run stopped at the limit can still have
simulated a correct program. olaussonN and olaussonPassAtN, on the base-none rows: chance that at least one of N
independent no-tool runs succeeds, N being the mean model requests of base-full on the same task (Olausson et al.,
ICLR 2024: same budget, sampling instead of repairing).

    python3 scripts/summary.py <dir>    # writes <dir>/summary.csv and prints it
"""

import csv
import json
import sys
from collections import defaultdict
from pathlib import Path
from statistics import mean

directory = Path(sys.argv[1])
groups = defaultdict(list)
for path in sorted(directory.glob("*--*.json")):
    record = json.loads(path.read_text())
    if "evaluation" not in record:
        sys.exit(f"{path} has no evaluation: run Evaluate first")
    cell = path.name.split("--")[0]
    groups[(record["task"], cell)].append(record)
    groups[("all", cell)].append(record)


def row(task, cell, records):
    outcomes = [r["outcome"] for r in records]
    return {
        "task": task,
        "cell": cell,
        "runs": len(records),
        "passed": sum(r["evaluation"]["passed"] for r in records),
        "successRate": round(mean(r["evaluation"]["passed"] for r in records), 3),
        "answered": outcomes.count("answered"),
        "limit": outcomes.count("limit"),
        "error": outcomes.count("error"),
        # One request per round of tool calls plus the final answer, retries aside
        "meanRoundTrips": round(mean(r["modelRequests"] - r["retries"] - 1 for r in records), 2),
        "meanRequests": round(mean(r["modelRequests"] for r in records), 2),
        "meanCompileCalls": round(mean(r["compileCalls"] for r in records), 2),
        "meanSimulateCalls": round(mean(r["simulateCalls"] for r in records), 2),
        "meanInputTokens": round(mean(r["inputTokens"] for r in records)),
        "meanOutputTokens": round(mean(r["outputTokens"] for r in records)),
        "totalRequests": sum(r["modelRequests"] for r in records),
        "totalInputTokens": sum(r["inputTokens"] for r in records),
        "totalOutputTokens": sum(r["outputTokens"] for r in records),
        "meanSeconds": round(mean(r["durationSeconds"] for r in records), 1),
        "olaussonN": "",
        "olaussonPassAtN": "",
    }


cells = ["base-none", "base-compile", "base-full", "prof-full"]
tasks = sorted({t for t, _ in groups if t != "all"}) + ["all"]
rows = {key: row(*key, records) for key, records in groups.items()}
for task in tasks:
    none, full = rows.get((task, "base-none")), rows.get((task, "base-full"))
    if none and full and task != "all":
        n = full["meanRequests"]
        none["olaussonN"] = n
        none["olaussonPassAtN"] = round(1 - (1 - none["successRate"]) ** n, 3)

ordered = [rows[(t, c)] for t in tasks for c in cells if (t, c) in rows]
out = directory / "summary.csv"
with out.open("w", newline="") as f:
    writer = csv.DictWriter(f, fieldnames=list(ordered[0]))
    writer.writeheader()
    writer.writerows(ordered)
print(out.read_text(), end="")
errors = list((directory / "errors").glob("*.json"))
if errors:
    lost = sum(json.loads(p.read_text())["modelRequests"] for p in errors)
    print(f"{len(errors)} runs failed on the infrastructure and were run again, {lost} requests (not in the table)")
